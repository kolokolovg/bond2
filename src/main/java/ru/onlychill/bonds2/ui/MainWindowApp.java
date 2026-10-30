// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Supplier;
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
import ru.onlychill.bonds2.settings.SettingsStore;
import ru.onlychill.bonds2.settings.SettingsStore.LoadResult;

/**
 * Главное окно приложения: меню «Файл» (настройки, открытие, создание и закрытие БД, выход) и
 * строка состояния БД внизу. Чтение настроек при запуске выполняется вне потока интерфейса, логика
 * БД — в {@link DatabaseController}; здесь сборка интерфейса и действие остановки сервиса для хука
 * завершения JVM.
 */
public final class MainWindowApp extends Application {

  /** Действия пунктов меню «Файл». */
  record MenuActions(
      Runnable settings, Runnable open, Runnable create, Runnable close, Runnable exit) {}

  /** Имя потока, на котором выполняется чтение настроек при запуске. */
  static final String SETTINGS_LOAD_THREAD_NAME = "bonds2-settings-load";

  private static final System.Logger LOG = System.getLogger(MainWindowApp.class.getName());

  /**
   * Лучшая попытка остановки сервиса БД для хука завершения JVM; {@code null}, когда приложение ещё
   * не дошло до создания контроллера или уже завершилось штатно. Читается хуком из любого потока.
   */
  private static volatile @Nullable Runnable externalStop;

  private @Nullable DatabaseController controller;
  private @Nullable InstanceLock instanceLock;

  @Override
  public void start(Stage stage) {
    // Блокировка берётся до чтения настроек: второй экземпляр не должен трогать общие файлы
    instanceLock = acquireInstanceLock(DataDirectory.lockFile(), MainWindowApp::showStartupError);
    if (instanceLock == null) {
      Platform.exit();
      return;
    }
    var store = new SettingsStore(DataDirectory.settingsFile());
    // Чтение настроек идёт вне потока интерфейса: медленный диск не замораживает запуск
    loadSettingsThenShow(store::load, settingsLoader(), loaded -> showWindow(stage, store, loaded));
  }

  /**
   * Читает настройки вне потока интерфейса и по завершении передаёт готовый результат в поток
   * JavaFX: там строится окно и выполняется автооткрытие по прочитанным значениям. Чтение по
   * контракту {@link SettingsStore#load()} не бросает и возвращает значения с признаком сброса,
   * если файл был испорчен.
   *
   * @param load чтение настроек
   * @param loader выполняет чтение вне потока интерфейса
   * @param show строит и показывает окно по готовому результату; вызывается в потоке JavaFX
   */
  static void loadSettingsThenShow(
      Supplier<LoadResult> load, Executor loader, Consumer<LoadResult> show) {
    loader.execute(
        () -> {
          LoadResult loaded = load.get();
          try {
            Platform.runLater(() -> show.accept(loaded));
          } catch (IllegalStateException e) {
            // Тулкит уже завершён: окно не нужно, остановку сервиса делает хук завершения JVM
          }
        });
  }

  /** Исполнитель чтения настроек: отдельный поток-демон читает один раз и завершается. */
  private static Executor settingsLoader() {
    return task -> {
      var thread = new Thread(task, SETTINGS_LOAD_THREAD_NAME);
      thread.setDaemon(true);
      thread.start();
    };
  }

  /**
   * Строит меню, показывает окно и запускает автооткрытие по прочитанным настройкам; вызывается в
   * потоке JavaFX после завершения чтения.
   */
  private void showWindow(Stage stage, SettingsStore store, LoadResult loaded) {
    var db =
        new DatabaseController(
            store,
            loaded,
            DataDirectory.databaseFile(),
            new DatabaseService(),
            new FxDialogs(stage),
            Platform::exit,
            Platform::runLater);
    controller = db;
    // Пока сервис не остановлен штатно, хук завершения JVM может остановить его без JavaFX
    setExternalStop(() -> db.shutdown(DatabaseController.STOP_TIMEOUT));

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

  @Override
  public void stop() {
    DatabaseController db = controller;
    InstanceLock lock = instanceLock;
    shutDownInOrder(db == null ? () -> {} : db::shutdown, lock == null ? () -> {} : lock::close);
    // Штатный выход завершён: хук завершения JVM больше ничего не останавливает
    setExternalStop(null);
  }

  /**
   * Тело хука завершения JVM: останавливает сервис БД, если он ещё не остановлен штатным выходом.
   * Не касается JavaFX — тулкит к моменту внешнего завершения (сигнал, остановка из среды
   * разработки) может быть уже мёртв. Ошибки остановки журналируются и завершению не мешают;
   * идемпотентно со штатным выходом, который снимает действие остановки.
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
   * Завершение: сначала останавливается работа с БД, и только потом освобождается блокировка
   * экземпляра, чтобы новый экземпляр не открыл файл, пока старый ещё с ним работает. Блокировка
   * освобождается, даже если остановка завершилась ошибкой.
   */
  static void shutDownInOrder(Runnable stopDatabase, Runnable releaseLock) {
    try {
      stopDatabase.run();
    } finally {
      releaseLock.run();
    }
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
            menuItem("Настройки…", actions.settings(), state.map(s -> !s.allowsSettings())),
            menuItem("Открыть базу", actions.open(), state.map(s -> !s.allowsOpen())),
            menuItem("Создать базу…", actions.create(), state.map(s -> !s.allowsCreate())),
            menuItem("Закрыть базу", actions.close(), state.map(s -> !s.allowsClose())),
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
        () ->
            new SettingsDialog(stage, db.settings(), DataDirectory.databaseFile(), db::saveSettings)
                .showAndWait(),
        db::openDatabase,
        () -> chooseNewDatabase(stage).ifPresent(db::createDatabase),
        db::closeDatabase,
        db::requestExit);
  }

  /**
   * Диалог выбора файла для новой БД; пустой результат означает отмену. Имя без расширения
   * дополняется {@code .db}, чтобы созданный файл был виден фильтрам диалогов приложения.
   */
  private static Optional<Path> chooseNewDatabase(Stage stage) {
    var chooser = new FileChooser();
    chooser.setTitle("Создать базу данных");
    chooser.setInitialFileName("bonds2.db");
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
