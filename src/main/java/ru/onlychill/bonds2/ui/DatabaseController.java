// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
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
 * показывает диалоги. Сам с файлом БД не работает.
 *
 * <p>Методы вызываются из потока JavaFX, кроме {@link #shutdown()} и {@link
 * #stopForExternalShutdown()}: они выполняются в цепочках завершения — на выделенном потоке
 * завершения приложения и в хуке завершения JVM. При истечении срока остановки в меню-пути
 * пользователь видит предупреждение, и приложение завершается после его подтверждения; в остальных
 * путях истечение срока остаётся в журнале. Результаты сервиса и состояние доставляются через
 * {@code uiExecutor} ({@code Platform::runLater} в приложении). Состояние видно через {@link
 * #stateProperty()} и {@link #statusProperty()}. Класс не зависит от контролов, поэтому тестируется
 * без показа окон.
 */
final class DatabaseController {

  /** Выход во время миграции откладывается: прервать миграцию посреди нельзя. */
  private static final String MIGRATION_EXIT_WARNING =
      "Идёт миграция базы. Дождитесь её завершения, затем закройте окно.";

  /** Предупреждение при истечении срока остановки в меню-пути: выход после подтверждения. */
  private static final String TIMED_OUT_EXIT_WARNING =
      "БД могла закрыться не полностью; данные не потеряны и восстановятся при следующем открытии.";

  /** Сколько ждать окончания открытия или создания при выходе через меню и окно. */
  static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

  /** Сколько ждать открытия или создания при завершении в обход меню. */
  static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

  /** Сколько ждать окончания миграции при завершении в обход меню. */
  static final Duration MIGRATION_TIMEOUT = Duration.ofSeconds(30);

  private static final System.Logger LOG = System.getLogger(DatabaseController.class.getName());

  private final Path defaultDatabaseFile;
  private final DatabaseService service;
  private final Dialogs dialogs;
  private final Runnable exit;
  private final Executor uiExecutor;

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
   * Выход начат: повторный запрос игнорируется. {@code volatile}: предикат {@code exitStarted} в
   * {@link MigrationFlow} читает флаг из цепочек доставки, которые могут выполняться не в потоке
   * интерфейса.
   */
  private volatile boolean exiting;

  /**
   * Приложение завершается: поздние результаты сервиса игнорируются. Поле выставляет остановка, а
   * читают обработчики результатов, которые могут выполняться в другом потоке, поэтому оно {@code
   * volatile}.
   */
  private volatile boolean stopped;

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
    this.exit = exit;
    this.uiExecutor = uiExecutor;
    this.store = store;
    this.settings = loaded;
    // Предикаты — живой взгляд на флаги контроллера: значения читаются в момент решения
    this.flow = new MigrationFlow(service, dialogs, uiExecutor, () -> exiting, () -> stopped);
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

  /**
   * «Файл → Выход» и закрытие окна кнопкой. Во время миграции выход откладывается с сообщением;
   * повторный запрос игнорируется. Ожидаемая миграция считается отклонённой, её диалог закрывается.
   * Если результат «нужна миграция» приходит после запроса, диалог не показывается. Остановка
   * сервиса идёт в фоне (открытие и создание ждут не дольше {@link #SHUTDOWN_TIMEOUT}), приложение
   * завершается по её итогу; при истечении срока остановки пользователю показывается
   * предупреждение, и приложение завершается после его подтверждения. Сбой цепочки оставляет
   * приложение работать: повторный «Выход» начинает цепочку заново.
   */
  void requestExit() {
    DbState current = state.get();
    if (current instanceof DbState.Migrating) {
      dialogs.warn(MIGRATION_EXIT_WARNING);
      return;
    }
    if (exiting) {
      return;
    }
    exiting = true;
    if (current instanceof DbState.AwaitingMigration) {
      flow.cancelDialog();
      declineAndStop();
    } else {
      deliverExitResult(service.shutdownAsync(SHUTDOWN_TIMEOUT));
    }
  }

  /**
   * Отклоняет ожидающую решения миграцию и затем останавливает сервис: миграция считается
   * отклонённой, файл БД не меняется, а сервис допускает остановку.
   */
  private void declineAndStop() {
    CompletableFuture<ShutdownResult> stopping =
        service.declineMigration().thenCompose(ignored -> service.shutdownAsync(SHUTDOWN_TIMEOUT));
    deliverExitResult(stopping);
  }

  /**
   * Передаёт итог остановки в поток JavaFX. Сбой цепочки выхода — сама остановка или её обработка —
   * восстанавливает интерфейс: флаг выхода сброшен, ошибка показана, приложение продолжает работу.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  private void deliverExitResult(CompletableFuture<ShutdownResult> stopping) {
    stopping
        .thenAcceptAsync(this::onExitResult, uiExecutor)
        .exceptionallyAsync(
            error -> {
              onExitFailure(error);
              return null;
            },
            uiExecutor);
  }

  /**
   * Сбой цепочки выхода в потоке JavaFX: приложение остаётся живым, повторный «Выход» начинает
   * цепочку заново.
   */
  private void onExitFailure(Throwable error) {
    exiting = false;
    LOG.log(System.Logger.Level.ERROR, "Сбой остановки сервиса БД", error);
    dialogs.error(ErrorMessages.of(failureOf(error)));
  }

  /** Первый значимый сбой: обёртка {@link CompletionException} снята до причины. */
  private static Throwable failureOf(Throwable error) {
    if (error instanceof CompletionException completion && completion.getCause() != null) {
      return completion.getCause();
    }
    return error;
  }

  /**
   * Итог остановки в потоке JavaFX. Точка доставки из тестов: тесты передают собранные результаты
   * напрямую. Истечение срока остановки показывает предупреждение; приложение завершается, когда
   * пользователь его подтвердил, — повторные запросы выхода в это время игнорируются, и диалог не
   * накапливается. Отказ, пока сервис ждёт решения о миграции (результат «нужна миграция» пришёл
   * после запроса выхода), отклоняет миграцию и повторяет остановку без диалога и предупреждения.
   * Отказ из-за настоящей миграции показывает «Идёт миграция».
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  void onExitResult(ShutdownResult result) {
    if (result == ShutdownResult.TIMED_OUT) {
      stopped = true;
      dialogs
          .warnBeforeExit(TIMED_OUT_EXIT_WARNING)
          .thenRunAsync(exit, uiExecutor)
          .exceptionallyAsync(
              error -> {
                onExitFailure(error);
                return null;
              },
              uiExecutor);
    } else if (result != ShutdownResult.REFUSED) {
      stopped = true;
      exit.run();
    } else if (service.state() instanceof DbState.AwaitingMigration) {
      declineAndStop();
    } else {
      exiting = false;
      dialogs.warn(MIGRATION_EXIT_WARNING);
    }
  }

  /**
   * Завершение приложения в обход меню: останавливает сервис не дольше срока {@link #STOP_TIMEOUT}.
   * Ожидающая решения миграция считается отклонённой, идущая доводится до конца не дольше {@link
   * #MIGRATION_TIMEOUT}. Результаты, пришедшие позже, игнорируются. Сбой самой остановки сервиса
   * (синхронной или асинхронной в конце цепочки) журналируется и завершению не мешает: диалог в
   * пути завершения невозможен — тулкит гаснет, процесс обязан завершиться, — поэтому ошибка
   * остаётся в журнале, а завершение продолжается.
   *
   * <p>Метод выполняется на любом потоке: в приложении это выделенный поток завершения {@code
   * bonds2-exit}, поэтому ожидание не задерживает поток интерфейса.
   */
  void shutdown() {
    try {
      if (shutdown(STOP_TIMEOUT) != ShutdownResult.REFUSED) {
        return;
      }
      flow.cancelDialog();
      // Ожидание миграции сознательно: прервать миграцию хуже, чем подождать — откат при следующем
      // запуске потерял бы гарантию «файл не тронут». Ожидание идёт на потоке завершения, поэтому
      // интерфейс оно не блокирует.
      CompletableFuture<?> prepared = CompletableFuture.completedFuture(null);
      CompletableFuture<OpenResult> migration = flow.currentMigration();
      if (service.state() instanceof DbState.AwaitingMigration) {
        prepared = service.declineMigration();
      } else if (migration != null) {
        prepared = migration.copy();
      }
      ShutdownResult retried =
          prepared
              .orTimeout(MIGRATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
              .handle((value, error) -> null)
              .thenCompose(ignored -> service.shutdownAsync(MIGRATION_TIMEOUT))
              .join();
      logTimedOutStop(retried);
      stopped = true;
    } catch (RuntimeException | Error e) {
      // Диалог здесь невозможен: тулкит гаснет, процесс обязан завершиться. Сбой остановки
      // журналируется, а завершение продолжается
      LOG.log(System.Logger.Level.ERROR, "Сбой остановки сервиса БД при внешнем завершении", e);
    }
  }

  /** Синхронная остановка сервиса; поздние результаты игнорируются, если отказа не было. */
  ShutdownResult shutdown(Duration timeout) {
    ShutdownResult result = service.shutdown(timeout);
    if (result != ShutdownResult.REFUSED) {
      stopped = true;
      logTimedOutStop(result);
    }
    return result;
  }

  /**
   * Действие хука завершения JVM: останавливает сервис не дольше {@link #STOP_TIMEOUT}. Диалог
   * здесь невозможен; истечение срока остаётся в журнале.
   */
  void stopForExternalShutdown() {
    shutdown(STOP_TIMEOUT);
  }

  /** Истечение срока остановки — в журнал: диалог в путях без меню невозможен. */
  private static void logTimedOutStop(ShutdownResult result) {
    if (result == ShutdownResult.TIMED_OUT) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Остановка БД не завершилась в срок; данные восстановятся при следующем открытии из"
              + " журнала WAL");
    }
  }

  private void onOpenResult(OpenResult result) {
    if (stopped) {
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
    if (stopped) {
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
    if (stopped) {
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
