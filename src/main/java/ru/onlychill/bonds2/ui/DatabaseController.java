// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import ru.onlychill.bonds2.app.ExitCoordinator;
import ru.onlychill.bonds2.app.ExitView;
import ru.onlychill.bonds2.dbservice.CloseResult;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.ErrorMessages;
import ru.onlychill.bonds2.dbservice.OpenResult;
import ru.onlychill.bonds2.dbservice.ShutdownResult;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsStore;

/**
 * FX-адаптер {@link DatabaseService}: подписывается на состояние, переносит его в поток JavaFX и
 * показывает диалоги. Сам с файлом БД не работает; решения о завершении приложения — в {@link
 * ExitCoordinator}, контроллер лишь делегирует и адаптирует диалоги к его портам.
 *
 * <p>Методы вызываются из потока JavaFX, кроме {@link #shutdown()} и {@link
 * #stopForExternalShutdown()}: они выполняются в цепочках завершения — на выделенном потоке
 * завершения приложения и в хуке завершения JVM. Результаты сервиса и состояние доставляются через
 * {@code uiExecutor} ({@code Platform::runLater} в приложении). Состояние видно через {@link
 * #stateProperty()} и {@link #statusProperty()}. Класс не зависит от контролов, поэтому тестируется
 * без показа окон.
 */
final class DatabaseController {

  private static final System.Logger LOG = System.getLogger(DatabaseController.class.getName());

  private final Path defaultDatabaseFile;
  private final DatabaseService service;
  private final Dialogs dialogs;
  private final Executor uiExecutor;

  /** Машина выхода: фазы, сроки и порядок завершения. */
  private final ExitCoordinator exits;

  /** Хранилище настроек: синхронная запись из потока интерфейса. */
  private final SettingsStore store;

  /** Настройки, действующие сейчас (последние сохранённые). */
  private Settings settings;

  private final ReadOnlyObjectWrapper<DbState> state = new ReadOnlyObjectWrapper<>(DbState.CLOSED);
  private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper(StatusText.CLOSED);

  /** Сценарий диалога миграции: вопрос, запуск или отклонение миграции, итог. */
  private final MigrationFlow flow;

  /**
   * Открытие или создание идёт: повторный запрос до доставки результата игнорируется. {@code
   * volatile}: запись и сброс происходят в потоке интерфейса, но исполнитель в тестах отличается.
   */
  private volatile boolean busy;

  /**
   * @param store хранилище настроек; синхронная запись из потока интерфейса
   * @param loaded настройки, прочитанные до создания контроллера
   * @param defaultDatabaseFile файл БД, если в настройках путь не задан
   * @param service сервис жизненного цикла БД; контроллер подписывается на его состояние
   * @param dialogs диалоги пользователя
   * @param exit завершение приложения
   * @param uiExecutor выполняет задачу в потоке JavaFX
   */
  DatabaseController(
      SettingsStore store,
      Settings loaded,
      Path defaultDatabaseFile,
      DatabaseService service,
      Dialogs dialogs,
      Runnable exit,
      Executor uiExecutor) {
    this.defaultDatabaseFile = defaultDatabaseFile;
    this.service = service;
    this.dialogs = dialogs;
    this.uiExecutor = uiExecutor;
    this.store = store;
    this.settings = loaded;
    this.exits =
        new ExitCoordinator(
            service,
            new ExitView() {
              @Override
              public CompletableFuture<Void> warnTimedOutBeforeExit(String message) {
                return dialogs.warnBeforeExit(message);
              }

              @Override
              public void warnMigrationInProgress(String message) {
                dialogs.warn(message);
              }

              @Override
              public void showError(String message) {
                dialogs.error(message);
              }
            },
            exit,
            uiExecutor,
            () -> state.get());
    // Предикаты — живой взгляд на фазу выхода координатора: значения читаются в момент решения
    this.flow = new MigrationFlow(service, dialogs, uiExecutor, exits::exiting, exits::concluded);
    exits.attachMigration(flow);
    service.states().subscribe(new StateSubscriber(uiExecutor, this::apply));
  }

  /** Текущее состояние БД; меню и кнопки подписываются на него. */
  ReadOnlyObjectProperty<DbState> stateProperty() {
    return state.getReadOnlyProperty();
  }

  /** Строка состояния: «БД открыта: путь» или «БД не открыта» с причиной. */
  ReadOnlyStringProperty statusProperty() {
    return status.getReadOnlyProperty();
  }

  /** Настройки, действующие сейчас (последние сохранённые). */
  Settings settings() {
    return settings;
  }

  /** Автооткрытие после показа окна: открывает БД, только если это включено в настройках. */
  void openOnStartup() {
    if (settings.autoOpen()) {
      open(true);
    }
  }

  /** «Файл → Открыть базу»: открывает БД по пути из настроек. */
  void openDatabase() {
    open(true);
  }

  /** Открытие; {@code autoCreate} разрешает создать БД по пути по умолчанию, если файла нет. */
  private void open(boolean autoCreate) {
    if (busy || !state.get().allowsOpen()) {
      return;
    }
    Path configured = settings.databasePath();
    Path file = configured != null ? configured : defaultDatabaseFile;
    deliverOperation(service.open(file, autoCreate && configured == null), this::onOpenResult);
  }

  /**
   * «Файл → Создать базу…»: создаёт БД с актуальной схемой по выбранному пути. Существующий файл не
   * изменяется. Путь записывается в настройки; флаг автооткрытия не меняется.
   */
  void createDatabase(Path file) {
    if (busy || !state.get().allowsCreate()) {
      return;
    }
    deliverOperation(service.create(file), result -> onCreateResult(file, result));
  }

  /** «Файл → Закрыть базу»: закрывает БД на потоке сервиса, окно остаётся отзывчивым. */
  void closeDatabase() {
    if (!state.get().allowsClose()) {
      return;
    }
    deliver(service.close(), this::onCloseResult);
  }

  /**
   * Сохраняет настройки синхронно в потоке интерфейса и применяет их; платформенное хранилище
   * пишется за миллисекунды. Ошибка записи даёт {@link java.io.UncheckedIOException}; прежние
   * настройки остаются действующими.
   */
  void saveSettings(Settings updated) {
    store.save(updated);
    settings = updated;
  }

  /** «Файл → Выход» и закрытие окна кнопкой: решает машина выхода координатора. */
  void requestExit() {
    exits.requestExit();
  }

  /**
   * Итог остановки в потоке интерфейса. Точка доставки из тестов: тесты передают собранные
   * результаты напрямую.
   */
  void onExitResult(ShutdownResult result) {
    exits.onExitResult(result);
  }

  /**
   * Завершение приложения в обход меню; выполняется на выделенном потоке завершения. Ошибки
   * журналируются координатором, завершение продолжается.
   */
  void shutdown() {
    exits.shutdownForExternalTermination();
  }

  /** Синхронная остановка сервиса; точка доставки из тестов. */
  ShutdownResult shutdown(Duration timeout) {
    return exits.shutdownWaiting(timeout);
  }

  /** Действие хука завершения JVM: останавливает сервис не дольше срока координатора. */
  void stopForExternalShutdown() {
    exits.stopForExternalShutdown();
  }

  /** Цепочка штатного завершения: остановка БД → блокировка экземпляра → снятие хука JVM. */
  void runExitChain(Runnable releaseLock, Runnable disarmHook) {
    exits.runExitChain(releaseLock, disarmHook);
  }

  private void onOpenResult(OpenResult result) {
    if (exits.concluded()) {
      return;
    }
    switch (result) {
      case OpenResult.Opened opened -> {}
      case OpenResult.NeedsMigration needs -> flow.askAboutMigration();
      case OpenResult.Failed failure -> dialogs.error(failure.message());
      case OpenResult.Rejected rejected ->
          LOG.log(System.Logger.Level.DEBUG, "Открытие отклонено: " + rejected.message());
    }
  }

  /**
   * Итог создания в потоке интерфейса. Гонка состояния и начатая остановка — не ошибка файла:
   * отклонения тихи, как при открытии; отказ из-за существующего файла пользователь видит. Точка
   * доставки из тестов: тесты передают собранные результаты напрямую.
   */
  void onCreateResult(Path file, OpenResult result) {
    if (exits.concluded()) {
      return;
    }
    if (result instanceof OpenResult.Opened) {
      rememberDatabasePath(file);
    } else if (result instanceof OpenResult.Failed failure) {
      dialogs.error(failure.message());
    } else if (result instanceof OpenResult.Rejected rejected) {
      switch (rejected.cause()) {
        // Гонка состояния или начатая остановка — не ошибка файла: тихо, как при открытии
        case STATE, STOPPED ->
            LOG.log(System.Logger.Level.DEBUG, "Создание отклонено: " + rejected.message());
        // Отказ из-за существующего файла: пользователь выбирает другое имя
        case FILE_EXISTS -> dialogs.error(rejected.message());
      }
    }
  }

  private void onCloseResult(CloseResult result) {
    if (exits.concluded()) {
      return;
    }
    if (result instanceof CloseResult.Closed closed && closed.warning() != null) {
      dialogs.warn(closed.warning());
    } else if (result instanceof CloseResult.Failed failed) {
      dialogs.error(failed.message());
    }
  }

  /** Записывает путь новой БД в настройки; при ошибке записи БД остаётся открытой. */
  private void rememberDatabasePath(Path file) {
    try {
      saveSettings(new Settings(file, settings.autoOpen()));
    } catch (RuntimeException e) {
      dialogs.warn(
          "База создана и открыта, но путь не записан в настройки. " + ErrorMessages.of(e));
    }
  }

  /**
   * Ставит операцию открытия или создания и помечает её идущей ({@code busy}): повторный запрос до
   * доставки результата игнорируется даже если состояние в интерфейсе ещё не обновилось. Признак
   * сбрасывается в потоке JavaFX, когда цепочка доставки доходит до конца: и при результате, и при
   * сбое операции или обработчика.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  private <T> void deliverOperation(CompletableFuture<T> future, Consumer<T> handler) {
    busy = true;
    CompletableFuture<Void> delivered =
        future
            .thenAcceptAsync(handler, uiExecutor)
            .handleAsync(
                (ignored, error) -> {
                  busy = false;
                  return (Void) null;
                },
                uiExecutor);
    delivered.exceptionally(
        error -> {
          LOG.log(System.Logger.Level.ERROR, "Сбой операции с БД", error);
          return null;
        });
  }

  /** Передаёт результат сервиса обработчику в потоке JavaFX; сбой обработчика попадает в журнал. */
  @SuppressWarnings("FutureReturnValueIgnored")
  private <T> void deliver(CompletableFuture<T> future, Consumer<T> handler) {
    future
        .thenAcceptAsync(handler, uiExecutor)
        .exceptionally(
            error -> {
              LOG.log(System.Logger.Level.ERROR, "Сбой операции с БД", error);
              return null;
            });
  }

  /** Применяет состояние в потоке JavaFX: обновляет свойство состояния и строку состояния. */
  private void apply(DbState next) {
    state.set(next);
    status.set(StatusText.of(next));
  }
}
