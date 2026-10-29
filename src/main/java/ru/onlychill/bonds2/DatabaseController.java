package ru.onlychill.bonds2;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.persistence.Database;
import ru.onlychill.bonds2.persistence.PersistenceException;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsStore;

/**
 * Жизненный цикл БД главного окна: открытие по настройкам, создание, миграция с подтверждением,
 * закрытие и проверка возможности выйти из приложения.
 *
 * <p>Методы вызываются из потока JavaFX. Работа с файлом БД передаётся в {@link Background}, её
 * результат возвращается в поток JavaFX. Диалоги — в {@link Dialogs}. Состояние видно через {@link
 * #stateProperty()} и {@link #statusProperty()}. Класс не зависит от контролов, поэтому тестируется
 * без показа окон.
 */
final class DatabaseController {

  /** Текст вопроса о миграции; резервная копия названа явно. */
  private static final String MIGRATION_QUESTION =
      "Схема БД устарела. Применить миграции? Перед миграцией будет создана резервная копия файла"
          + " БД.";

  private static final String STATUS_CLOSED = "БД не открыта";
  private static final String STATUS_OPEN = "БД открыта: ";
  private static final String STATUS_OPENING = "Открытие базы…";
  private static final String STATUS_CREATING = "Создание базы…";
  private static final String STATUS_MIGRATING = "Миграция базы…";

  private static final String REASON_MISSING = "файл не найден";
  private static final String REASON_OUTDATED = "схема устарела";
  private static final String REASON_TOO_NEW = "схема новее приложения";
  private static final String REASON_OPEN = "ошибка открытия";
  private static final String REASON_CREATE = "ошибка создания";
  private static final String REASON_MIGRATION = "ошибка миграции";
  private static final String REASON_UNEXPECTED = "внутренняя ошибка";

  /** Сколько ждать окончания фоновой операции при завершении приложения. */
  static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

  private static final System.Logger LOG = System.getLogger(DatabaseController.class.getName());

  /**
   * Фоновое выполнение работы с БД; {@code onDone} и {@code onError} вызываются в потоке JavaFX.
   */
  interface Background {

    /**
     * Выполняет {@code work} вне потока JavaFX и затем вызывает один из обработчиков.
     *
     * @return результат самой работы: он готов, как только работа закончилась, и не зависит от
     *     доставки в поток JavaFX, поэтому его можно ждать из потока JavaFX
     */
    Future<OpenResult> run(
        Callable<OpenResult> work, Consumer<OpenResult> onDone, Consumer<Throwable> onError);
  }

  /** Диалоги пользователя. */
  interface Dialogs {

    /**
     * Вопрос о миграции с кнопками «Применить» и «Отмена»: {@code true}, если пользователь
     * согласен.
     */
    boolean confirmMigration(String question);

    /** Предупреждение: операция отложена или выполнена частично, приложение продолжает работу. */
    void warn(String message);

    /** Сообщение об ошибке. */
    void error(String message);
  }

  private final SettingsStore store;
  private final Path defaultDatabaseFile;
  private final Background background;
  private final Dialogs dialogs;
  private final Runnable exit;
  private final Duration shutdownTimeout;

  private final ReadOnlyObjectWrapper<DbState> state = new ReadOnlyObjectWrapper<>(DbState.CLOSED);
  private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper(STATUS_CLOSED);

  private Settings settings;

  /** Открытая БД: не {@code null} ровно тогда, когда состояние {@link DbState#OPEN}. */
  private @Nullable Database database;

  /** Фоновая работа, результат которой ещё не обработан; её ждёт {@link #shutdown()}. */
  private @Nullable Future<OpenResult> pendingWork;

  /** Приложение завершается: поздние результаты фоновой работы игнорируются. */
  private boolean stopped;

  /**
   * @param store хранилище настроек; настройки читаются при создании контроллера
   * @param defaultDatabaseFile файл БД, если в настройках путь не задан
   * @param background фоновое выполнение работы с БД
   * @param dialogs диалоги пользователя
   * @param exit завершение приложения
   */
  DatabaseController(
      SettingsStore store,
      Path defaultDatabaseFile,
      Background background,
      Dialogs dialogs,
      Runnable exit) {
    this(store, defaultDatabaseFile, background, dialogs, exit, SHUTDOWN_TIMEOUT);
  }

  /** Конструктор с заданным сроком ожидания фоновой операции при завершении. */
  DatabaseController(
      SettingsStore store,
      Path defaultDatabaseFile,
      Background background,
      Dialogs dialogs,
      Runnable exit,
      Duration shutdownTimeout) {
    this.shutdownTimeout = shutdownTimeout;
    this.store = store;
    this.defaultDatabaseFile = defaultDatabaseFile;
    this.background = background;
    this.dialogs = dialogs;
    this.exit = exit;
    this.settings = store.load();
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
      openDatabase();
    }
  }

  /** «Файл → Открыть базу»: открывает БД по пути из настроек. */
  void openDatabase() {
    if (!state.get().allowsOpen()) {
      return;
    }
    Path file = databaseFile();
    boolean defaultPath = settings.databasePath() == null;
    setState(DbState.OPENING, STATUS_OPENING);
    pendingWork =
        background.run(
            () -> attempt(file, defaultPath),
            result -> onResult(file, result),
            this::onUnexpectedError);
  }

  /**
   * «Файл → Создать базу…»: создаёт БД с актуальной схемой по выбранному пути. Существующий файл не
   * изменяется. Путь записывается в настройки; флаг автооткрытия не меняется.
   */
  void createDatabase(Path file) {
    if (!state.get().allowsCreate()) {
      return;
    }
    if (Files.exists(file)) {
      dialogs.error(
          "Файл уже существует: "
              + file
              + ". Выберите другое имя файла: существующий файл не"
              + " изменён.");
      return;
    }
    setState(DbState.OPENING, STATUS_CREATING);
    pendingWork =
        background.run(
            () -> create(file), result -> onCreated(file, result), this::onUnexpectedError);
  }

  /** «Файл → Закрыть базу»: закрывает БД, переносит журнал WAL в файл. */
  void closeDatabase() {
    Database opened = database;
    if (opened == null || !state.get().allowsClose()) {
      return;
    }
    database = null;
    setState(DbState.CLOSED, STATUS_CLOSED);
    try {
      opened.close();
    } catch (PersistenceException e) {
      dialogs.warn(
          "Не удалось полностью закрыть БД: "
              + ErrorMessages.of(e)
              + " Зафиксированные данные не потеряны: они остаются в журнале WAL и будут применены"
              + " при следующем открытии.");
    }
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
   * «Файл → Выход» и закрытие окна кнопкой: во время операций с БД выход откладывается с
   * сообщением, иначе приложение завершается.
   */
  void requestExit() {
    DbState current = state.get();
    if (current == DbState.MIGRATING) {
      dialogs.warn("Идёт миграция базы. Дождитесь её завершения, затем закройте окно.");
    } else if (current == DbState.OPENING) {
      dialogs.warn("Идёт открытие или создание базы. Дождитесь завершения, затем закройте окно.");
    } else {
      exit.run();
    }
  }

  /**
   * Вызывается при завершении приложения. Ждёт окончания фоновой операции не дольше срока ожидания
   * и закрывает полученную БД, затем закрывает открытую БД. Результаты, пришедшие позже,
   * игнорируются.
   */
  void shutdown() {
    stopped = true;
    awaitPendingWork();
    Database opened = database;
    if (opened == null) {
      return;
    }
    database = null;
    setState(DbState.CLOSED, STATUS_CLOSED);
    try {
      opened.close();
    } catch (PersistenceException e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось закрыть БД при выходе", e);
    }
  }

  /** Ждёт фоновую работу и закрывает БД, которую она открыла; по сроку или ошибке идёт дальше. */
  private void awaitPendingWork() {
    Future<OpenResult> work = pendingWork;
    pendingWork = null;
    if (work == null) {
      return;
    }
    try {
      discard(work.get(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (TimeoutException e) {
      LOG.log(System.Logger.Level.WARNING, "Операция с БД не завершилась при выходе", e);
    } catch (ExecutionException e) {
      LOG.log(System.Logger.Level.WARNING, "Операция с БД завершилась ошибкой при выходе", e);
    }
  }

  /** Результат, который больше никому не нужен: открытую БД закрывает, остальное отбрасывает. */
  private static void discard(OpenResult result) {
    if (result instanceof OpenResult.Opened opened) {
      try {
        opened.database().close();
      } catch (PersistenceException e) {
        LOG.log(System.Logger.Level.WARNING, "Не удалось закрыть БД при выходе", e);
      }
    }
  }

  /** Файл БД по настройкам либо путь по умолчанию. */
  private Path databaseFile() {
    Path configured = settings.databasePath();
    return configured != null ? configured : defaultDatabaseFile;
  }

  /** Результат создания: при успехе запоминает путь в настройках. */
  private void onCreated(Path file, OpenResult result) {
    if (stopped) {
      discard(result);
      return;
    }
    if (result instanceof OpenResult.Opened opened) {
      becomeOpen(file, opened.database());
      rememberDatabasePath(file);
    } else {
      onResult(file, result);
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

  /**
   * Результат открытия или миграции: открывает БД, спрашивает о миграции или сообщает об ошибке.
   */
  private void onResult(Path file, OpenResult result) {
    if (stopped) {
      discard(result);
      return;
    }
    switch (result) {
      case OpenResult.Opened opened -> becomeOpen(file, opened.database());
      case OpenResult.NeedsMigration() -> confirmMigration(file);
      case OpenResult.Failed failure -> reject(failure);
    }
  }

  /** Спрашивает согласие на миграцию; без согласия файл не меняется и БД не открывается. */
  private void confirmMigration(Path file) {
    boolean confirmed = dialogs.confirmMigration(MIGRATION_QUESTION);
    if (stopped) {
      return;
    }
    if (!confirmed) {
      setState(DbState.CLOSED, STATUS_CLOSED + ": " + REASON_OUTDATED);
      return;
    }
    setState(DbState.MIGRATING, STATUS_MIGRATING);
    pendingWork =
        background.run(
            () -> migrateAndOpen(file), result -> onResult(file, result), this::onUnexpectedError);
  }

  private void becomeOpen(Path file, Database opened) {
    database = opened;
    setState(DbState.OPEN, STATUS_OPEN + file);
  }

  /** Операция не выполнена: БД закрыта, причина — в строке состояния, подробности — в диалоге. */
  private void reject(OpenResult.Failed failure) {
    setState(DbState.CLOSED, STATUS_CLOSED + ": " + failure.reason());
    dialogs.error(failure.message());
  }

  private void onUnexpectedError(Throwable error) {
    if (stopped) {
      return;
    }
    reject(
        new OpenResult.Failed(
            REASON_UNEXPECTED, "Непредвиденная ошибка: " + ErrorMessages.of(error)));
  }

  private void setState(DbState next, String text) {
    state.set(next);
    status.set(text);
  }

  /**
   * Фоновая часть открытия: по наличию файла и состоянию схемы выбирает действие. Файл по пути из
   * настроек не создаётся, но путь по умолчанию создаётся автоматически.
   */
  private static OpenResult attempt(Path file, boolean defaultPath) {
    if (Files.notExists(file)) {
      return defaultPath ? create(file) : missing(file);
    }
    try {
      return switch (Database.inspect(file)) {
        case UP_TO_DATE -> new OpenResult.Opened(Database.open(file));
        case NEEDS_MIGRATION -> new OpenResult.NeedsMigration();
        case TOO_NEW -> tooNew(file);
      };
    } catch (PersistenceException e) {
      return new OpenResult.Failed(REASON_OPEN, ErrorMessages.of(e));
    }
  }

  private static OpenResult create(Path file) {
    try {
      return new OpenResult.Opened(Database.create(file));
    } catch (PersistenceException e) {
      return new OpenResult.Failed(REASON_CREATE, ErrorMessages.of(e));
    }
  }

  /** Миграция с резервной копией и открытие. Ошибка миграции уже содержит путь к копии. */
  private static OpenResult migrateAndOpen(Path file) {
    try {
      Database.migrateWithBackup(file);
      return new OpenResult.Opened(Database.open(file));
    } catch (PersistenceException e) {
      return new OpenResult.Failed(REASON_MIGRATION, ErrorMessages.of(e));
    }
  }

  private static OpenResult.Failed missing(Path file) {
    return new OpenResult.Failed(
        REASON_MISSING,
        "Файл БД не найден: "
            + file
            + ". Чтобы создать новую базу, выберите «Файл → Создать базу…».");
  }

  private static OpenResult.Failed tooNew(Path file) {
    return new OpenResult.Failed(
        REASON_TOO_NEW,
        "Схема БД новее этой версии приложения. Файл не изменён: "
            + file
            + ". Обновите приложение.");
  }
}
