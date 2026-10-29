// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
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

  private final SettingsStore store;
  private final Path defaultDatabaseFile;
  private final DatabaseService service;
  private final Dialogs dialogs;
  private final Runnable exit;
  private final Executor uiExecutor;

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

  /** Выход начат: повторный запрос игнорируется. */
  private boolean exiting;

  /** Приложение завершается: поздние результаты сервиса игнорируются. */
  private boolean stopped;

  /**
   * @param store хранилище настроек; настройки читаются при создании контроллера
   * @param defaultDatabaseFile файл БД, если в настройках путь не задан
   * @param service сервис жизненного цикла БД; контроллер подписывается на его состояние
   * @param dialogs диалоги пользователя
   * @param exit завершение приложения
   * @param uiExecutor выполняет задачу в потоке JavaFX
   */
  DatabaseController(
      SettingsStore store,
      Path defaultDatabaseFile,
      DatabaseService service,
      Dialogs dialogs,
      Runnable exit,
      Executor uiExecutor) {
    this.store = store;
    this.defaultDatabaseFile = defaultDatabaseFile;
    this.service = service;
    this.dialogs = dialogs;
    this.exit = exit;
    this.uiExecutor = uiExecutor;
    SettingsStore.LoadResult loaded = store.load();
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
    if (!state.get().allowsOpen()) {
      return;
    }
    Path configured = settings.databasePath();
    Path file = configured != null ? configured : defaultDatabaseFile;
    deliver(service.open(file, autoCreate && configured == null), this::onOpenResult);
  }

  /**
   * «Файл → Создать базу…»: создаёт БД с актуальной схемой по выбранному пути. Существующий файл не
   * изменяется. Путь записывается в настройки; флаг автооткрытия не меняется.
   */
  void createDatabase(Path file) {
    if (!state.get().allowsCreate()) {
      return;
    }
    deliver(service.create(file), result -> onCreateResult(file, result));
  }

  /** «Файл → Закрыть базу»: закрывает БД на потоке сервиса, окно остаётся отзывчивым. */
  void closeDatabase() {
    if (!state.get().allowsClose()) {
      return;
    }
    deliver(service.close(), this::onCloseResult);
  }

  /**
   * Сохраняет настройки и запоминает их. При ошибке записи бросает {@link UncheckedIOException},
   * прежние настройки остаются в силе.
   */
  void saveSettings(Settings updated) {
    store.save(updated);
    settings = updated;
  }

  /**
   * «Файл → Выход» и закрытие окна кнопкой. Во время миграции выход откладывается с сообщением;
   * повторный запрос игнорируется. Ожидаемая миграция считается отклонённой, её диалог закрывается.
   * Остановка сервиса идёт в фоне (открытие и создание ждут не дольше {@link #SHUTDOWN_TIMEOUT}),
   * приложение завершается по её итогу.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
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
    CompletableFuture<?> prepared = CompletableFuture.completedFuture(null);
    if (current instanceof DbState.AwaitingMigration) {
      cancelMigrationDialog();
      prepared = service.declineMigration();
    }
    prepared
        .thenCompose(ignored -> service.shutdownAsync(SHUTDOWN_TIMEOUT))
        .thenAcceptAsync(this::onExitResult, uiExecutor)
        .exceptionally(
            error -> {
              LOG.log(System.Logger.Level.ERROR, "Сбой остановки сервиса БД", error);
              return null;
            });
  }

  private void onExitResult(ShutdownResult result) {
    if (result == ShutdownResult.REFUSED) {
      exiting = false;
      dialogs.warn(MIGRATION_EXIT_WARNING);
    } else {
      stopped = true;
      exit.run();
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
   */
  void shutdown() {
    if (shutdown(STOP_TIMEOUT) != ShutdownResult.REFUSED) {
      return;
    }
    cancelMigrationDialog();
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

  private void onCreateResult(Path file, OpenResult result) {
    if (stopped) {
      return;
    }
    if (result instanceof OpenResult.Opened) {
      rememberDatabasePath(file);
    } else if (result instanceof OpenResult.Failed failure) {
      dialogs.error(failure.message());
    } else if (result instanceof OpenResult.Rejected rejected) {
      dialogs.error(rejected.message());
    }
  }

  private void onCloseResult(CloseResult result) {
    if (stopped) {
      return;
    }
    if (result instanceof CloseResult.Closed closed && closed.warning() != null) {
      dialogs.warn(closed.warning());
    }
  }

  /** Записывает путь новой БД в настройки; при ошибке записи БД остаётся открытой. */
  private void rememberDatabasePath(Path file) {
    try {
      saveSettings(new Settings(file, settings.autoOpen()));
    } catch (UncheckedIOException e) {
      dialogs.warn(
          "База создана и открыта, но путь не записан в настройки. " + ErrorMessages.of(e));
    }
  }

  /** Спрашивает согласие на миграцию; без согласия файл не меняется и БД не открывается. */
  @SuppressWarnings("FutureReturnValueIgnored")
  private void askAboutMigration() {
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
