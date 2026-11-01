// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.ErrorMessages;
import ru.onlychill.bonds2.persistence.DataDirectory;
import ru.onlychill.bonds2.persistence.InstanceLock;
import ru.onlychill.bonds2.persistence.PersistenceException;

/**
 * Главное окно приложения: меню «Файл» (резервная копия, повторное открытие БД, выход) и строка
 * состояния БД внизу. Логика БД — в {@link DatabaseController}; здесь сборка интерфейса, действие
 * остановки сервиса для хука завершения JVM и цепочка штатного выхода: она выполняется на
 * выделенном потоке завершения {@link #EXIT_THREAD_NAME} и не блокирует поток интерфейса.
 */
public final class MainWindowApp extends Application {

  /** Действия пунктов меню «Файл». */
  record MenuActions(Runnable backup, Runnable retryOpen, Runnable exit) {}

  /** Имя потока, на котором выполняется цепочка завершения приложения. */
  static final String EXIT_THREAD_NAME = "bonds2-exit";

  private static final System.Logger LOG = System.getLogger(MainWindowApp.class.getName());

  /**
   * Лучшая попытка остановки сервиса БД для хука завершения JVM; {@code null}, когда приложение ещё
   * не дошло до создания контроллера или уже завершилось штатно. Читается хуком из любого потока.
   */
  private static volatile @Nullable Runnable externalStop;

  private @Nullable DatabaseController controller;
  private @Nullable InstanceLock instanceLock;

  /** Запускатель цепочки завершения; тесты подменяют его, чтобы цепочку не выполнять. */
  private final AtomicReference<Consumer<Runnable>> exitLauncher =
      new AtomicReference<>(MainWindowApp::startExitThread);

  @Override
  public void start(Stage stage) {
    // Блокировка берётся до любых общих файлов: второй экземпляр не должен их трогать
    instanceLock = acquireInstanceLock(DataDirectory.lockFile(), MainWindowApp::showStartupError);
    if (instanceLock == null) {
      Platform.exit();
      return;
    }
    showWindow(stage);
  }

  /**
   * Строит меню, показывает окно и безусловно открывает единственную БД приложения; вызывается в
   * потоке JavaFX. Ошибка открытия показывается диалогом, окно остаётся работоспособным.
   */
  private void showWindow(Stage stage) {
    var db =
        new DatabaseController(
            new DatabaseService(), new FxDialogs(stage), Platform::exit, Platform::runLater);
    controller = db;
    // Пока сервис не остановлен штатно, хук завершения JVM может остановить его без JavaFX
    setExternalStop(db::stopForExternalShutdown);

    var status = new Label();
    status.textProperty().bind(db.statusProperty());
    status.setPadding(new Insets(4, 8, 4, 8));

    var root = new BorderPane();
    root.setTop(createMenuBar(menuActions(stage, db), db.stateProperty()));
    root.setBottom(status);

    stage.setTitle("Bonds");
    stage.setScene(new Scene(root, 800, 600));
    // Закрытие окна кнопкой закрытия — то же, что «Файл → Выход»
    stage.setOnCloseRequest(
        event -> {
          event.consume();
          db.requestExit();
        });
    stage.show();
    // БД открывается после показа окна; ошибка показывается диалогом, окно остаётся рабочим
    db.openOnStartup();
  }

  /**
   * Завершение приложения: возвращается сразу и не блокирует поток интерфейса. Цепочка завершения
   * запускается один раз на выделенном потоке {@link #EXIT_THREAD_NAME}: остановка БД →
   * освобождение блокировки экземпляра → снятие действия хука завершения JVM. Повторный {@code
   * stop()} вторую цепочку не создаёт.
   */
  @Override
  public void stop() {
    launchExit(exitChain(controller, instanceLock));
  }

  /**
   * Цепочка завершения: сначала останавливается работа с БД, и только потом освобождается
   * блокировка экземпляра, чтобы новый экземпляр не открыл файл, пока старый ещё с ним работает.
   * Блокировка освобождается, даже если остановка завершилась ошибкой. Последним снимается действие
   * хука: штатный выход завершён, хук больше ничего не останавливает. Порядок и однократность
   * выполнения держит машина выхода; здесь только сборка цепочки из частей приложения.
   */
  static Runnable exitChain(@Nullable DatabaseController db, @Nullable InstanceLock lock) {
    Runnable releaseLock = lock == null ? () -> {} : lock::close;
    Runnable disarmHook = () -> setExternalStop(null);
    return () -> {
      if (db == null) {
        try {
          releaseLock.run();
        } finally {
          disarmHook.run();
        }
        return;
      }
      db.runExitChain(releaseLock, disarmHook);
    };
  }

  /** Запускает цепочку завершения; однократность выполнения гарантирует машина выхода. */
  private void launchExit(Runnable chain) {
    // Запускатель не null по построению: поле инициализировано и меняется только целиком
    Objects.requireNonNull(exitLauncher.get()).accept(chain);
  }

  /** Тестовый крючок: заменяет запускатель цепочки завершения. */
  void launchExitChainWith(Consumer<Runnable> launcher) {
    exitLauncher.set(launcher);
  }

  /** Запускает цепочку завершения на выделенном потоке {@link #EXIT_THREAD_NAME}. */
  private static void startExitThread(Runnable chain) {
    // Не демон: процесс живёт до конца цепочки и завершается сам с кодом 0
    var thread = new Thread(chain, EXIT_THREAD_NAME);
    thread.setDaemon(false);
    thread.start();
  }

  /**
   * Тело хука завершения JVM: останавливает сервис БД, если штатный выход его ещё не остановил. Не
   * касается JavaFX — тулкит к моменту внешнего завершения (сигнал, остановка из среды разработки)
   * может быть уже мёртв. Ошибки остановки журналируются и завершению не мешают; идемпотентно со
   * штатным выходом, который снимает действие остановки.
   */
  public static void stopDatabaseOnExternalShutdown() {
    Runnable stop = externalStop;
    if (stop == null) {
      return;
    }
    try {
      stop.run();
    } catch (RuntimeException | Error e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось остановить сервис БД при завершении", e);
    }
  }

  /** Заменяет действие остановки для внешнего завершения; {@code null} снимает его. */
  static void setExternalStop(@Nullable Runnable stop) {
    externalStop = stop;
  }

  /**
   * Берёт блокировку единственного экземпляра. Если приложение уже запущено или блокировку взять
   * нельзя, сообщает об этом через {@code report} и возвращает {@code null}.
   */
  static @Nullable InstanceLock acquireInstanceLock(Path lockFile, Consumer<String> report) {
    try {
      Optional<InstanceLock> lock = InstanceLock.tryAcquire(lockFile);
      if (lock.isEmpty()) {
        report.accept("Приложение уже запущено.");
      }
      return lock.orElse(null);
    } catch (PersistenceException e) {
      report.accept("Не удалось запустить приложение: " + ErrorMessages.of(e));
      return null;
    }
  }

  private static void showStartupError(String message) {
    var alert = new Alert(Alert.AlertType.ERROR, message);
    alert.setTitle("Bonds");
    alert.setHeaderText(null);
    alert.showAndWait();
  }

  /** Строка меню с пунктами «Файл»; доступность пунктов следует за состоянием БД. */
  static MenuBar createMenuBar(MenuActions actions, ObservableValue<DbState> state) {
    var exit = new MenuItem("Выход");
    exit.setOnAction(event -> actions.exit().run());
    return new MenuBar(
        new Menu(
            "Файл",
            null,
            menuItem(
                "Сделать резервную копию…", actions.backup(), state.map(s -> !s.allowsBackup())),
            menuItem("Повторить открытие", actions.retryOpen(), state.map(s -> !s.allowsOpen())),
            new SeparatorMenuItem(),
            exit));
  }

  private static MenuItem menuItem(
      String text, Runnable action, ObservableValue<Boolean> disabled) {
    var item = new MenuItem(text);
    item.setOnAction(event -> action.run());
    item.disableProperty().bind(disabled);
    return item;
  }

  private static MenuActions menuActions(Stage stage, DatabaseController db) {
    return new MenuActions(
        () -> chooseBackupTarget(stage).ifPresent(db::backupTo), db::retryOpen, db::requestExit);
  }

  /**
   * Единственный в приложении файловый диалог: выбор места резервной копии. Пустой результат
   * означает отмену. Имя без расширения дополняется {@code .db}, чтобы копия была видна фильтрам
   * диалогов приложения.
   */
  private static Optional<Path> chooseBackupTarget(Stage stage) {
    var chooser = new FileChooser();
    chooser.setTitle("Сделать резервную копию");
    chooser.setInitialFileName("bonds2-copy.db");
    chooser
        .getExtensionFilters()
        .add(new FileChooser.ExtensionFilter("База данных SQLite", "*.db"));
    return Optional.ofNullable(chooser.showSaveDialog(stage))
        .map(File::toPath)
        .map(MainWindowApp::withDbExtension);
  }

  /**
   * Дописывает расширение {@code .db} к выбранному имени без расширения: по последней точке в имени
   * файла. Имя с расширением не меняется.
   */
  static Path withDbExtension(Path chosen) {
    Path fileName = chosen.getFileName();
    if (fileName == null) {
      // Путь без имени файла (корень): дописывать нечего
      return chosen;
    }
    String name = fileName.toString();
    int dot = name.lastIndexOf('.');
    if (dot > 0 && dot < name.length() - 1) {
      return chosen;
    }
    return chosen.resolveSibling(name + ".db");
  }
}
