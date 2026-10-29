package ru.onlychill.bonds2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.persistence.Database;
import ru.onlychill.bonds2.persistence.PersistenceException;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsStore;

class DatabaseControllerTest {

  private static final String CLOSED_STATUS = "БД не открыта";

  @TempDir Path dir;

  private final RecordingDialogs dialogs = new RecordingDialogs();
  private final AtomicInteger exits = new AtomicInteger();

  private Path dataDir() {
    return dir.resolve("data");
  }

  private Path userFile() {
    return dataDir().resolve("bonds.db");
  }

  private Path settingsFile() {
    return dir.resolve("cfg/settings.properties");
  }

  private Path defaultFile() {
    return dir.resolve("default/bonds2.db");
  }

  private DatabaseController controller(DatabaseController.Background background) {
    return controllerWith(settingsFile(), background);
  }

  private DatabaseController controller(
      DatabaseController.Background background, Duration shutdownTimeout) {
    return new DatabaseController(
        new SettingsStore(settingsFile()),
        defaultFile(),
        background,
        dialogs,
        exits::incrementAndGet,
        shutdownTimeout);
  }

  private DatabaseController controllerWith(
      Path settings, DatabaseController.Background background) {
    return new DatabaseController(
        new SettingsStore(settings), defaultFile(), background, dialogs, exits::incrementAndGet);
  }

  private DatabaseController immediate() {
    return controller(FakeBackground.immediate());
  }

  private void saveSettings(Path databasePath, boolean autoOpen) {
    new SettingsStore(settingsFile()).save(new Settings(databasePath, autoOpen));
  }

  @Test
  void startsClosedWithClosedStatus() {
    var db = immediate();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
  }

  @Test
  void disabledAutoOpenLeavesDatabaseClosedAndFileUntouched() {
    saveSettings(userFile(), false);
    var db = immediate();

    db.openOnStartup();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
    assertThat(userFile()).doesNotExist();
  }

  @Test
  void autoOpenOpensExistingDatabaseAndShowsItsPath() {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    var db = immediate();

    db.openOnStartup();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPEN);
    assertThat(db.statusProperty().get()).isEqualTo("БД открыта: " + userFile());
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void missingDefaultPathDatabaseIsCreatedAutomatically() {
    saveSettings(null, true);
    var db = immediate();

    db.openOnStartup();

    assertThat(defaultFile()).isRegularFile();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPEN);
    assertThat(db.statusProperty().get()).isEqualTo("БД открыта: " + defaultFile());
  }

  @Test
  void missingUserFileIsNotCreatedAndHintsAtCreation() {
    saveSettings(userFile(), true);
    var db = immediate();

    db.openOnStartup();

    assertThat(userFile()).doesNotExist();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: файл не найден");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("Создать базу");
  }

  @Test
  void outdatedSchemaIsMigratedAfterConfirmationWithBackup() throws IOException {
    emptyUserFile();
    saveSettings(userFile(), true);
    var db = immediate();

    db.openOnStartup();

    assertThat(dialogs.questions).hasSize(1);
    assertThat(dialogs.questions.get(0)).contains("резервная копия");
    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPEN);
    assertThat(backupsIn(dataDir())).hasSize(1);
    assertThat(Database.inspect(userFile())).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void refusedMigrationLeavesFileUntouchedAndDatabaseClosed() throws IOException {
    emptyUserFile();
    saveSettings(userFile(), true);
    dialogs.migrationAnswer = false;
    var db = immediate();

    db.openOnStartup();

    assertThat(dialogs.questions).hasSize(1);
    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: схема устарела");
    assertThat(userFile()).isEmptyFile();
    assertThat(backupsIn(dataDir())).isEmpty();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void failedMigrationRestoresFileAndShowsErrorWithRestoreNote() throws SQLException, IOException {
    databaseWithForgottenHistory(userFile());
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    var db = immediate();

    db.openOnStartup();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: ошибка миграции");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("восстановлена").contains(".bak-");
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(backupsIn(dataDir())).hasSize(1);
  }

  @Test
  void newerSchemaIsRejectedWithoutTouchingFile() throws SQLException, IOException {
    newDatabaseAt(userFile());
    executeSql(
        userFile(),
        "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script,"
            + " checksum, installed_by, installed_on, execution_time, success) VALUES (2, '99',"
            + " 'future', 'SQL', 'V99__future.sql', 1, 'test', CURRENT_TIMESTAMP, 0, 1)");
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    var db = immediate();

    db.openOnStartup();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: схема новее приложения");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("новее");
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void corruptFileIsReportedAsFailureAndNotChanged() throws IOException {
    Files.createDirectories(dataDir());
    Files.writeString(
        userFile(),
        "это обычный текст, а не база данных SQLite, поэтому открыть его нельзя",
        StandardCharsets.UTF_8);
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    var db = immediate();

    db.openOnStartup();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).startsWith("БД не открыта: ");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void unexpectedBackgroundFailureIsReportedAndDatabaseStaysClosed() {
    DatabaseController.Background failing =
        (work, onDone, onError) -> {
          var failure = new IllegalStateException("сбой");
          onError.accept(failure);
          return CompletableFuture.failedFuture(failure);
        };
    var db = controller(failing);

    db.openDatabase();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: внутренняя ошибка");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("сбой");
  }

  @Test
  void openAndCreateAreIgnoredWhileOpeningAndWhileOpen() throws IOException {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    var background = FakeBackground.deferred();
    var db = controller(background);

    db.openDatabase();
    db.openDatabase();
    db.createDatabase(dataDir().resolve("other.db"));
    assertThat(background.pending()).isEqualTo(1);

    background.runNext();
    db.openDatabase();
    db.createDatabase(dataDir().resolve("other.db"));

    assertThat(background.pending()).isZero();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPEN);
    assertThat(dataDir().resolve("other.db")).doesNotExist();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void exitIsDelayedWithMessageWhileOpening() {
    saveSettings(null, true);
    var background = FakeBackground.deferred();
    var db = controller(background);
    db.openDatabase();

    db.requestExit();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPENING);
    assertThat(dialogs.warnings).hasSize(1);
    assertThat(dialogs.warnings.get(0)).contains("Идёт открытие");
    assertThat(exits.get()).isZero();
  }

  @Test
  void exitIsDelayedWithMessageWhileMigratingAndAllowedAfterwards() throws IOException {
    emptyUserFile();
    saveSettings(userFile(), true);
    var background = FakeBackground.deferred();
    var db = controller(background);
    db.openDatabase();
    background.runNext();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.MIGRATING);
    db.requestExit();
    assertThat(dialogs.warnings).hasSize(1);
    assertThat(dialogs.warnings.get(0)).contains("Идёт миграция");
    assertThat(exits.get()).isZero();

    background.runNext();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPEN);
    db.requestExit();
    assertThat(exits.get()).isEqualTo(1);
  }

  @Test
  void exitRunsImmediatelyWhenClosed() {
    var db = immediate();

    db.requestExit();

    assertThat(exits.get()).isEqualTo(1);
    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void createdDatabaseIsOpenedAndItsPathSavedKeepingAutoOpen() {
    saveSettings(null, false);
    var db = immediate();

    db.createDatabase(userFile());

    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPEN);
    assertThat(db.statusProperty().get()).isEqualTo("БД открыта: " + userFile());
    assertThat(userFile()).isRegularFile();
    assertThat(new SettingsStore(settingsFile()).load()).isEqualTo(new Settings(userFile(), false));
    assertThat(db.settings()).isEqualTo(new Settings(userFile(), false));
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void createOverExistingFileIsRefusedAndFileKept() throws IOException {
    Files.createDirectories(dataDir());
    Files.writeString(userFile(), "чужой файл");
    byte[] before = Files.readAllBytes(userFile());
    var db = immediate();

    db.createDatabase(userFile());

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("уже существует");
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(settingsFile()).doesNotExist();
  }

  @Test
  void createKeepsDatabaseOpenWhenSettingsCannotBeSaved() throws IOException {
    Files.writeString(dir.resolve("blocker"), "файл на месте каталога настроек");
    var db = controllerWith(dir.resolve("blocker/settings.properties"), FakeBackground.immediate());

    db.createDatabase(userFile());

    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPEN);
    assertThat(dialogs.warnings).hasSize(1);
    assertThat(dialogs.warnings.get(0)).contains("не записан в настройки");
    assertThat(db.settings()).isEqualTo(Settings.defaults());
  }

  @Test
  void closeDatabaseClosesItAndDatabaseCanBeOpenedAgain() {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    var db = immediate();
    db.openOnStartup();

    db.closeDatabase();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
    assertThat(dialogs.warnings).isEmpty();

    db.openDatabase();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPEN);
  }

  @Test
  void closeWhenClosedDoesNothing() {
    var db = immediate();

    db.closeDatabase();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void closeFailureIsReportedAndDatabaseStaysClosed() throws SQLException {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    var db = immediate();
    db.openOnStartup();

    whileReaderLagsBehind(userFile(), db::closeDatabase);

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
    assertThat(dialogs.warnings).hasSize(1);
    assertThat(dialogs.warnings.get(0)).contains("Не удалось полностью закрыть БД");
  }

  @Test
  void shutdownClosesOpenDatabaseOnlyOnce() {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    var db = immediate();
    db.openOnStartup();

    db.shutdown();
    db.shutdown();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void lateResultAfterShutdownClosesItsDatabaseAndStaysSilent() {
    saveSettings(null, true);
    var background = FakeBackground.deferred();
    var db = controller(background, Duration.ofMillis(50));
    db.openDatabase();

    db.shutdown();
    background.runNext();

    assertThat(background.results).hasSize(1);
    assertDatabaseClosed(background.results.get(0));
    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPENING);
    assertThat(db.statusProperty().get()).isEqualTo("Открытие базы…");
    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void shutdownClosesDatabaseOpenedByFinishedButUndeliveredWork() {
    saveSettings(null, true);
    var background = FakeBackground.deferred();
    var db = controller(background);
    db.openDatabase();
    var job = background.runWorkOnly();

    db.shutdown();
    job.deliver();

    assertDatabaseClosed(background.results.get(0));
    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPENING);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void shutdownDuringMigrationClosesMigratedDatabase() throws IOException {
    emptyUserFile();
    saveSettings(userFile(), true);
    var background = FakeBackground.deferred();
    var db = controller(background);
    db.openDatabase();
    background.runNext();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.MIGRATING);
    var job = background.runWorkOnly();

    db.shutdown();
    job.deliver();

    assertThat(backupsIn(dataDir())).hasSize(1);
    assertDatabaseClosed(background.results.get(1));
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void shutdownStopsWaitingWhenWorkDoesNotFinishInTime() {
    saveSettings(null, true);
    var background = FakeBackground.deferred();
    var db = controller(background, Duration.ofMillis(50));
    db.openDatabase();

    assertTimeoutPreemptively(Duration.ofSeconds(10), db::shutdown);

    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void errorAfterShutdownIsNotReported() {
    saveSettings(null, true);
    var background = FakeBackground.deferred();
    var db = controller(background, Duration.ofMillis(50));
    db.openDatabase();

    db.shutdown();
    background.runNext();

    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void migrationAnswerAfterShutdownDoesNotStartMigration() throws IOException {
    emptyUserFile();
    saveSettings(userFile(), true);
    var background = FakeBackground.deferred();
    var db = controller(background);
    dialogs.onQuestion = db::shutdown;
    db.openDatabase();

    background.runNext();

    assertThat(background.pending()).isZero();
    assertThat(backupsIn(dataDir())).isEmpty();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.OPENING);
  }

  private static void assertDatabaseClosed(OpenResult result) {
    assertThat(result).isInstanceOf(OpenResult.Opened.class);
    var database = ((OpenResult.Opened) result).database();
    assertThatThrownBy(() -> database.inTransaction(connection -> null))
        .isInstanceOf(PersistenceException.class);
  }

  @Test
  void shutdownWhileClosedDoesNothing() {
    var db = immediate();

    assertThatCode(db::shutdown).doesNotThrowAnyException();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void shutdownDoesNotThrowWhenCloseFails() throws SQLException {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    var db = immediate();
    db.openOnStartup();

    whileReaderLagsBehind(
        userFile(), () -> assertThatCode(db::shutdown).doesNotThrowAnyException());

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void saveSettingsUpdatesMemoryAndFile() {
    var db = immediate();

    db.saveSettings(new Settings(userFile(), false));

    assertThat(db.settings()).isEqualTo(new Settings(userFile(), false));
    assertThat(new SettingsStore(settingsFile()).load()).isEqualTo(new Settings(userFile(), false));
  }

  @Test
  void failedSaveKeepsPreviousSettings() throws IOException {
    Files.writeString(dir.resolve("blocker"), "файл на месте каталога настроек");
    var db = controllerWith(dir.resolve("blocker/settings.properties"), FakeBackground.immediate());

    assertThatThrownBy(() -> db.saveSettings(new Settings(userFile(), false)))
        .isInstanceOf(UncheckedIOException.class);

    assertThat(db.settings()).isEqualTo(Settings.defaults());
  }

  /** Создаёт пустой файл пользовательской БД: в нём нет схемы, значит миграции устарели. */
  private void emptyUserFile() throws IOException {
    Files.createDirectories(dataDir());
    Files.createFile(userFile());
  }

  /**
   * Создаёт БД с актуальной схемой и закрывает её. Одна транзакция переводит файл в WAL, как при
   * обычной работе приложения: без неё файл остаётся в режиме журнала отката.
   */
  private static void newDatabaseAt(Path file) {
    try (var db = Database.create(file)) {
      db.inTransaction(connection -> null);
    }
  }

  /**
   * Создаёт БД и стирает историю миграций: таблица {@code app_info} уже есть, а V1 числится
   * непримененной, поэтому миграция падает на её создании.
   */
  private static void databaseWithForgottenHistory(Path file) throws SQLException {
    newDatabaseAt(file);
    executeSql(file, "DELETE FROM flyway_schema_history");
  }

  /**
   * Выполняет действие, пока другое соединение держит читающую транзакцию на старом снимке. Запись
   * сделана после начала этой транзакции, поэтому WAL нельзя перенести в файл: закрытие должно
   * сообщить об ошибке, а не молча потерять данные.
   */
  private static void whileReaderLagsBehind(Path file, Runnable action) throws SQLException {
    try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      reader.setAutoCommit(false);
      try (Statement statement = reader.createStatement();
          ResultSet rs = statement.executeQuery("SELECT count(*) FROM app_info")) {
        rs.next();
      }
      executeSql(file, "INSERT INTO app_info VALUES ('late', 'write')");
      action.run();
    }
  }

  /** Выполняет SQL отдельным соединением; изменения фиксируются и попадают в файл. */
  private static void executeSql(Path file, String... sql) throws SQLException {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement statement = connection.createStatement()) {
      for (String text : sql) {
        statement.execute(text);
      }
    }
  }

  /** Резервные копии рядом с файлом БД: имена содержат {@code .bak-}. */
  private static List<String> backupsIn(Path directory) throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.map(Path::toString).filter(name -> name.contains(".bak-")).toList();
    }
  }

  /** Фон для тестов: работа выполняется сразу либо откладывается до {@link #runNext()}. */
  static final class FakeBackground implements DatabaseController.Background {

    private final boolean immediate;
    private final Deque<Job> queue = new ArrayDeque<>();

    /** Результаты выполненной работы: до доставки в обработчики. */
    final List<OpenResult> results = new ArrayList<>();

    private FakeBackground(boolean immediate) {
      this.immediate = immediate;
    }

    static FakeBackground immediate() {
      return new FakeBackground(true);
    }

    static FakeBackground deferred() {
      return new FakeBackground(false);
    }

    @Override
    public Future<OpenResult> run(
        Callable<OpenResult> work, Consumer<OpenResult> onDone, Consumer<Throwable> onError) {
      var job = new Job(work, onDone, onError);
      if (immediate) {
        job.runWork();
        job.deliver();
      } else {
        queue.add(job);
      }
      return job.outcome;
    }

    /** Выполняет работу и доставляет результат следующей отложенной операции. */
    void runNext() {
      Job job = queue.remove();
      job.runWork();
      job.deliver();
    }

    /** Выполняет работу следующей операции, но результат ещё не доставляет. */
    Job runWorkOnly() {
      Job job = queue.remove();
      job.runWork();
      return job;
    }

    int pending() {
      return queue.size();
    }

    /** Отложенная операция: работа и доставка результата выполняются отдельно. */
    final class Job {

      final CompletableFuture<OpenResult> outcome = new CompletableFuture<>();
      private final Callable<OpenResult> work;
      private final Consumer<OpenResult> onDone;
      private final Consumer<Throwable> onError;
      private @Nullable Throwable failure;

      Job(Callable<OpenResult> work, Consumer<OpenResult> onDone, Consumer<Throwable> onError) {
        this.work = work;
        this.onDone = onDone;
        this.onError = onError;
      }

      void runWork() {
        try {
          OpenResult result = work.call();
          results.add(result);
          outcome.complete(result);
        } catch (Exception e) {
          failure = e;
          outcome.completeExceptionally(e);
        }
      }

      /** Передаёт обработчику результат или ошибку, как это делает поток JavaFX. */
      void deliver() {
        if (failure != null) {
          onError.accept(failure);
        } else {
          onDone.accept(outcome.join());
        }
      }
    }
  }

  static final class RecordingDialogs implements DatabaseController.Dialogs {

    final List<String> questions = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    boolean migrationAnswer = true;
    Runnable onQuestion = () -> {};

    @Override
    public boolean confirmMigration(String question) {
      questions.add(question);
      onQuestion.run();
      return migrationAnswer;
    }

    @Override
    public void warn(String message) {
      warnings.add(message);
    }

    @Override
    public void error(String message) {
      errors.add(message);
    }
  }
}
