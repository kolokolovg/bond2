// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.jspecify.annotations.Nullable;
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
 * <p>Методы вызываются из потока JavaFX. Результаты сервиса и состояние доставляются через {@code
 * uiExecutor} ({@code Platform::runLater} в приложении). Состояние видно через {@link
 * #stateProperty()} и {@link #statusProperty()}. Класс не зависит от контролов, поэтому тестируется
 * без показа окон.
 */
final class DatabaseController {

  /** Текст вопроса о миграции; резервная копия названа явно. */
  private static final String MIGRATION_QUESTION =
      "Схема БД устарела. Применить миграции? Перед миграцией будет создана резервная копия файла"
          + " БД.";

  /** Выход во время миграции откладывается: прервать миграцию посреди нельзя. */
  private static final String MIGRATION_EXIT_WARNING =
      "Идёт миграция базы. Дождитесь её завершения, затем закройте окно.";

  /** Сколько ждать окончания открытия или создания при выходе через меню и окно. */
  static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

  /** Сколько ждать открытия или создания при завершении в обход меню. */
  static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

  /** Сколько ждать окончания миграции при завершении в обход меню. */
  static final Duration MIGRATION_TIMEOUT = Duration.ofSeconds(30);

  private static final System.Logger LOG = System.getLogger(DatabaseController.class.getName());

  /** Имя потока, на котором выполняется запись настроек. */
  static final String SETTINGS_THREAD_NAME = "bonds2-settings";

  private final SettingsStore store;
  private final Path defaultDatabaseFile;
  private final DatabaseService service;
  private final Dialogs dialogs;
  private final Runnable exit;
  private final Executor uiExecutor;

  /** Фоновая запись настроек: один поток-демон, запись не задерживает поток интерфейса. */
  private final Executor settingsExecutor;

  private final ReadOnlyObjectWrapper<DbState> state = new ReadOnlyObjectWrapper<>(DbState.CLOSED);
  private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper(StatusText.CLOSED);

  private Settings settings;

  /** Файл настроек при запуске был испорчен и сброшен. */
  private final boolean recovered;

  /** Копия испорченного файла настроек; {@code null}, если её нет. */
  private final @Nullable Path badCopy;

  /** Открытый диалог миграции; {@code null}, если диалога нет. */
  private @Nullable CompletableFuture<Boolean> migrationDialog;

  /** Запущенная миграция; {@code null}, если её не было. */
  private @Nullable CompletableFuture<OpenResult> migration;

  /**
   * Открытие или создание идёт: повторный запрос до доставки результата игнорируется. {@code
   * volatile}: запись и сброс происходят в потоке интерфейса, но исполнитель в тестах отличается.
   */
  private volatile boolean busy;

  /** Выход начат: повторный запрос игнорируется. */
  private boolean exiting;

  /**
   * Приложение завершается: поздние результаты сервиса игнорируются. Поле выставляет остановка, а
   * читают обработчики результатов, которые могут выполняться в другом потоке, поэтому оно {@code
   * volatile}.
   */
  private volatile boolean stopped;

  /**
   * @param store хранилище настроек; используется для фоновой записи обновлённых настроек
   * @param loaded результат чтения настроек, выполненного до создания контроллера вне потока
   *     интерфейса
   * @param defaultDatabaseFile файл БД, если в настройках путь не задан
   * @param service сервис жизненного цикла БД; контроллер подписывается на его состояние
   * @param dialogs диалоги пользователя
   * @param exit завершение приложения
   * @param uiExecutor выполняет задачу в потоке JavaFX
   */
  DatabaseController(
      SettingsStore store,
      SettingsStore.LoadResult loaded,
      Path defaultDatabaseFile,
      DatabaseService service,
      Dialogs dialogs,
      Runnable exit,
      Executor uiExecutor) {
    this(
        store,
        loaded,
        defaultDatabaseFile,
        service,
        dialogs,
        exit,
        uiExecutor,
        Executors.newSingleThreadExecutor(
            runnable -> {
              var thread = new Thread(runnable, SETTINGS_THREAD_NAME);
              // Демон: незавершённая запись настроек не удерживает процесс при выходе
              thread.setDaemon(true);
              return thread;
            }));
  }

  /**
   * @param settingsExecutor выполняет запись настроек вне потока интерфейса; приложение использует
   *     однопоточный исполнитель с потоком-демоном, тесты подменяют его, чтобы проверять поток
   *     записи
   */
  DatabaseController(
      SettingsStore store,
      SettingsStore.LoadResult loaded,
      Path defaultDatabaseFile,
      DatabaseService service,
      Dialogs dialogs,
      Runnable exit,
      Executor uiExecutor,
      Executor settingsExecutor) {
    this.store = store;
    this.defaultDatabaseFile = defaultDatabaseFile;
    this.service = service;
    this.dialogs = dialogs;
    this.exit = exit;
    this.uiExecutor = uiExecutor;
    this.settingsExecutor = settingsExecutor;
    this.settings = loaded.settings();
    this.recovered = loaded.recovered();
    this.badCopy = loaded.badCopy();
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
    if (recovered) {
      dialogs.warn(
          "Файл настроек был повреждён, настройки сброшены к значениям по умолчанию."
              + (badCopy != null ? " Копия повреждённого файла: " + badCopy : ""));
    }
    if (settings.autoOpen()) {
      open(!recovered);
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
   * Записывает настройки на фоновом исполнителе и применяет их по завершении в потоке JavaFX.
   * Ошибка записи завершает future исключением, прежние настройки остаются в силе.
   *
   * @return завершается после записи и применения настроек; вызывающая сторона (окно настроек)
   *     закрывается по его завершении
   */
  CompletableFuture<Void> saveSettings(Settings updated) {
    var saved = new CompletableFuture<Void>();
    try {
      settingsExecutor.execute(
          () -> {
            try {
              store.save(updated);
              saved.complete(null);
            } catch (RuntimeException e) {
              saved.completeExceptionally(e);
            }
          });
    } catch (RejectedExecutionException e) {
      saved.completeExceptionally(e);
    }
    // Применение настроек — только после записи и в потоке JavaFX
    return saved.thenRunAsync(() -> settings = updated, uiExecutor);
  }

  /**
   * «Файл → Выход» и закрытие окна кнопкой. Во время миграции выход откладывается с сообщением;
   * повторный запрос игнорируется. Ожидаемая миграция считается отклонённой, её диалог закрывается.
   * Если результат «нужна миграция» приходит после запроса, диалог не показывается. Остановка
   * сервиса идёт в фоне (открытие и создание ждут не дольше {@link #SHUTDOWN_TIMEOUT}), приложение
   * завершается по её итогу. Сбой цепочки оставляет приложение работать: повторный «Выход» начинает
   * цепочку заново.
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
      cancelMigrationDialog();
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
   * Итог остановки в потоке JavaFX. Отказ, пока сервис ждёт решения о миграции (результат «нужна
   * миграция» пришёл после запроса выхода), отклоняет миграцию и повторяет остановку без диалога и
   * предупреждения. Отказ из-за настоящей миграции показывает «Идёт миграция».
   */
  private void onExitResult(ShutdownResult result) {
    if (result != ShutdownResult.REFUSED) {
      stopped = true;
      exit.run();
    } else if (service.state() instanceof DbState.AwaitingMigration) {
      declineAndStop();
    } else {
      exiting = false;
      dialogs.warn(MIGRATION_EXIT_WARNING);
    }
  }

  private void cancelMigrationDialog() {
    CompletableFuture<Boolean> dialog = migrationDialog;
    migrationDialog = null;
    if (dialog != null) {
      dialog.cancel(true);
    }
  }

  /**
   * Вызывается при завершении приложения в обход меню: останавливает сервис и ждёт не дольше {@link
   * #STOP_TIMEOUT}. Ожидающая решения миграция считается отклонённой, идущая доводится до конца не
   * дольше {@link #MIGRATION_TIMEOUT}. Результаты, пришедшие позже, игнорируются.
   *
   * <p>Ожидание здесь блокирует поток JavaFX сознательно. К моменту вызова окно уже скрыто JavaFX,
   * поэтому никакая обратная связь пользователю невозможна. Неблокирующий вариант — вернуться из
   * метода до закрытия БД — позволил бы процессу завершиться с незакрытой базой, что хуже короткой
   * задержки: без явного закрытия не выполняется контрольная точка журнала WAL и не проверяется
   * целостность файла. Ожидание ограничено: обычно не дольше {@link #STOP_TIMEOUT} (5 секунд), а
   * при идущей миграции — до её окончания и остановки сервиса, суммарно до ~65 секунд.
   */
  void shutdown() {
    if (shutdown(STOP_TIMEOUT) != ShutdownResult.REFUSED) {
      stopSettingsWriting();
      return;
    }
    cancelMigrationDialog();
    // Ожидание миграции в stop() (до MIGRATION_TIMEOUT) сохранено сознательно. Вызов идёт из потока
    // JavaFX, и блокировка этого потока неприятна, но прервать миграцию хуже, чем подождать: откат
    // при следующем запуске потерял бы гарантию «файл не тронут». Случай редкий: без меню stop()
    // почти не вызывается.
    CompletableFuture<?> prepared = CompletableFuture.completedFuture(null);
    if (service.state() instanceof DbState.AwaitingMigration) {
      prepared = service.declineMigration();
    } else if (migration != null) {
      prepared = migration.copy();
    }
    prepared
        .orTimeout(MIGRATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        .handle((value, error) -> null)
        .thenCompose(ignored -> service.shutdownAsync(MIGRATION_TIMEOUT))
        .join();
    stopped = true;
    stopSettingsWriting();
  }

  /** Останавливает фоновую запись настроек; незавершённая запись доходит до конца на демоне. */
  private void stopSettingsWriting() {
    if (settingsExecutor instanceof ExecutorService executorService) {
      executorService.shutdown();
    }
  }

  /** Синхронная остановка сервиса; поздние результаты игнорируются, если отказа не было. */
  ShutdownResult shutdown(Duration timeout) {
    ShutdownResult result = service.shutdown(timeout);
    if (result != ShutdownResult.REFUSED) {
      stopped = true;
    }
    return result;
  }

  private void onOpenResult(OpenResult result) {
    if (stopped) {
      return;
    }
    switch (result) {
      case OpenResult.Opened opened -> {}
      case OpenResult.NeedsMigration needs -> askAboutMigration();
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

  /** Записывает путь новой БД в настройки в фоне; при ошибке записи БД остаётся открытой. */
  @SuppressWarnings("FutureReturnValueIgnored")
  private void rememberDatabasePath(Path file) {
    saveSettings(new Settings(file, settings.autoOpen()))
        .exceptionallyAsync(
            error -> {
              dialogs.warn(
                  "База создана и открыта, но путь не записан в настройки. "
                      + ErrorMessages.of(failureOf(error)));
              return null;
            },
            uiExecutor);
  }

  /**
   * Спрашивает согласие на миграцию; без согласия файл не меняется и БД не открывается. Если выход
   * уже начат или приложение остановлено, диалог не показывается и миграция не запускается.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  private void askAboutMigration() {
    if (exiting || stopped) {
      return;
    }
    CompletableFuture<Boolean> dialog = dialogs.confirmMigration(MIGRATION_QUESTION);
    migrationDialog = dialog;
    dialog
        .thenComposeAsync(this::onMigrationAnswer, uiExecutor)
        .exceptionally(
            error -> {
              if (!(error instanceof CancellationException)
                  && !(error.getCause() instanceof CancellationException)) {
                LOG.log(System.Logger.Level.ERROR, "Сбой операции с БД", error);
              }
              return null;
            });
  }

  private CompletableFuture<Void> onMigrationAnswer(boolean confirmed) {
    migrationDialog = null;
    if (confirmed) {
      CompletableFuture<OpenResult> started = service.migrate();
      migration = started;
      return started.thenAcceptAsync(this::onMigrationResult, uiExecutor);
    }
    return service.declineMigration().thenAccept(result -> {});
  }

  private void onMigrationResult(OpenResult result) {
    if (stopped) {
      return;
    }
    if (result instanceof OpenResult.Failed failure) {
      dialogs.error(failure.message());
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
