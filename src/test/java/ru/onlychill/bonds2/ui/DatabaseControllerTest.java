// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.onlychill.bonds2.dbservice.TestDatabases.backupsIn;
import static ru.onlychill.bonds2.dbservice.TestDatabases.executeSql;
import static ru.onlychill.bonds2.dbservice.TestDatabases.isClosed;
import static ru.onlychill.bonds2.dbservice.TestDatabases.newDatabaseAt;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.GatedStorage;
import ru.onlychill.bonds2.dbservice.OpenResult;
import ru.onlychill.bonds2.dbservice.ShutdownResult;
import ru.onlychill.bonds2.dbservice.TestDatabases;
import ru.onlychill.bonds2.persistence.Database;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsStore;

class DatabaseControllerTest {

  private static final String CLOSED_STATUS = "БД не открыта";

  @TempDir Path dir;

  private final RecordingDialogs dialogs = new RecordingDialogs();
  private final AtomicInteger exits = new AtomicInteger();
  private final GatedStorage storage = new GatedStorage();
  private final DatabaseService service = new DatabaseService(storage);
  private final UiQueue ui = new UiQueue();

  /** Очередь задач «потока JavaFX»: задачи выполняются на потоке теста в {@link #pumpUntil}. */
  private static final class UiQueue implements Executor {

    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    /** Выполняет задачи, пока условие не выполнится; ошибка, если оно не выполнилось за 20 с. */
    void pumpUntil(BooleanSupplier condition) {
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (!condition.getAsBoolean()) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("Условие не выполнилось вовремя");
        }
        try {
          Runnable task = tasks.poll(10, TimeUnit.MILLISECONDS);
          if (task != null) {
            task.run();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
      }
    }

    /** Выполняет всё, что успело появиться за короткий простой. */
    void settle() {
      try {
        Runnable task;
        while ((task = tasks.poll(150, TimeUnit.MILLISECONDS)) != null) {
          task.run();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  @AfterEach
  void stopService() {
    storage.openGate.release();
    storage.migrateGate.release();
    service.shutdown(Duration.ofSeconds(30));
  }

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

  private DatabaseController controller() {
    return controllerWith(settingsFile());
  }

  private DatabaseController controllerWith(Path settings) {
    return new DatabaseController(
        new SettingsStore(settings), defaultFile(), service, dialogs, exits::incrementAndGet, ui);
  }

  private void saveSettings(Path databasePath, boolean autoOpen) {
    new SettingsStore(settingsFile()).save(new Settings(databasePath, autoOpen));
  }

  private static boolean isOpen(DatabaseController db) {
    return db.stateProperty().get() instanceof DbState.Open;
  }

  private static boolean isMigrating(DatabaseController db) {
    return db.stateProperty().get() instanceof DbState.Migrating;
  }

  /** Ждёт, пока открытие или создание придёт к итогу, и доставляет все диалоги. */
  private void settleAfterOperation(DatabaseController db) {
    ui.pumpUntil(
        () ->
            db.stateProperty().get() instanceof DbState.Open
                || (db.stateProperty().get() instanceof DbState.Closed closed
                    && closed.reason() != null));
    ui.settle();
  }

  private DatabaseController opened() {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    var db = controller();
    db.openOnStartup();
    ui.pumpUntil(() -> isOpen(db));
    return db;
  }

  @Test
  void startsClosedWithClosedStatus() {
    var db = controller();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
  }

  @Test
  void disabledAutoOpenLeavesDatabaseClosedAndFileUntouched() {
    saveSettings(userFile(), false);
    var db = controller();

    db.openOnStartup();
    ui.settle();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
    assertThat(userFile()).doesNotExist();
  }

  @Test
  void autoOpenOpensExistingDatabaseAndShowsItsPath() {
    var db = opened();

    assertThat(db.statusProperty().get()).isEqualTo("БД открыта: " + userFile());
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void missingDefaultPathDatabaseIsCreatedAutomatically() {
    saveSettings(null, true);
    var db = controller();

    db.openOnStartup();
    ui.pumpUntil(() -> isOpen(db));

    assertThat(defaultFile()).isRegularFile();
    assertThat(db.statusProperty().get()).isEqualTo("БД открыта: " + defaultFile());
  }

  private void corruptSettings() throws IOException {
    Files.createDirectories(dir.resolve("cfg"));
    Files.writeString(settingsFile(), "database.autoOpen=maybe\n");
  }

  @Test
  void resetSettingsWarnAndDoNotCreateDefaultDatabase() throws IOException {
    corruptSettings();
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(dialogs.warnings).hasSize(1);
    assertThat(dialogs.warnings.get(0)).contains("settings.properties.bad-");
    assertThat(defaultFile()).doesNotExist();
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: файл не найден");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("Создать базу");
  }

  @Test
  void resetSettingsStillOpenExistingDefaultDatabase() throws IOException {
    newDatabaseAt(defaultFile());
    corruptSettings();
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(dialogs.warnings).hasSize(1);
    assertThat(isOpen(db)).isTrue();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void firstRunWithoutSettingsFileCreatesDefaultDatabaseWithoutWarning() {
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(dialogs.warnings).isEmpty();
    assertThat(defaultFile()).isRegularFile();
    assertThat(isOpen(db)).isTrue();
  }

  @Test
  void missingUserFileIsNotCreatedAndHintsAtCreation() {
    saveSettings(userFile(), true);
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(userFile()).doesNotExist();
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: файл не найден");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("Создать базу");
  }

  @Test
  void outdatedSchemaIsMigratedAfterConfirmationWithBackup() throws SQLException, IOException {
    TestDatabases.outdatedAt(userFile());
    saveSettings(userFile(), true);
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(dialogs.questions).hasSize(1);
    assertThat(dialogs.questions.get(0)).contains("резервная копия");
    assertThat(isOpen(db)).isTrue();
    assertThat(backupsIn(dataDir())).hasSize(1);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void refusedMigrationLeavesFileUntouchedAndDatabaseClosed() throws SQLException, IOException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    dialogs.migrationAnswer = false;
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(dialogs.questions).hasSize(1);
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: схема устарела");
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(backupsIn(dataDir())).isEmpty();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void emptyFileIsInitializedWithoutMigrationQuestion() throws IOException {
    Files.createDirectories(dataDir());
    Files.createFile(userFile());
    saveSettings(userFile(), true);
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(isOpen(db)).isTrue();
    assertThat(dialogs.questions).isEmpty();
    assertThat(backupsIn(dataDir())).isEmpty();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void foreignDatabaseIsRejectedWithoutChangesAndWithoutMigrationOffer()
      throws SQLException, IOException {
    TestDatabases.foreignAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: не база bonds2");
    assertThat(dialogs.questions).isEmpty();
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0))
        .contains("не является базой данных bonds2")
        .contains("Файл не изменён");
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void failedMigrationRestoresFileAndShowsErrorWithRestoreNote() throws SQLException, IOException {
    TestDatabases.forgottenHistoryAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: ошибка миграции");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("восстановлена").contains(".bak-");
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void newerSchemaIsRejectedWithoutTouchingFile() throws SQLException, IOException {
    TestDatabases.tooNewAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: схема новее приложения");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void unexpectedFailureIsReportedAndDatabaseStaysClosed() {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    storage.openFailure = new IllegalStateException("сбой");
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: внутренняя ошибка");
    assertThat(dialogs.errors).singleElement().asString().contains("сбой");
  }

  @Test
  void openAndCreateAreIgnoredWhileOpeningAndWhileOpen() throws InterruptedException {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    storage.openGate.hold();
    var db = controller();

    db.openDatabase();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Opening);
    db.openDatabase();
    db.createDatabase(dataDir().resolve("other.db"));

    storage.openGate.release();
    ui.pumpUntil(() -> isOpen(db));
    db.openDatabase();
    db.createDatabase(dataDir().resolve("other.db"));
    ui.settle();

    assertThat(storage.opened).hasSize(1);
    assertThat(dataDir().resolve("other.db")).doesNotExist();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void statusFollowsOpeningCreatingAndMigrating() throws SQLException, InterruptedException {
    saveSettings(null, false);
    storage.openGate.hold();
    var db = controller();

    db.createDatabase(userFile());
    ui.pumpUntil(() -> db.statusProperty().get().equals("Создание базы…"));
    storage.openGate.release();
    ui.pumpUntil(() -> isOpen(db));
    db.closeDatabase();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Closed);

    executeSql(userFile(), "DELETE FROM flyway_schema_history", "DROP TABLE app_info");
    storage.migrateGate.hold();
    db.openDatabase();
    ui.pumpUntil(() -> isMigrating(db));

    assertThat(db.statusProperty().get()).isEqualTo("Миграция базы…");
    storage.migrateGate.release();
    ui.pumpUntil(() -> isOpen(db));
  }

  @Test
  void exitRunsImmediatelyWhenClosed() {
    var db = controller();

    db.requestExit();

    assertThat(exits.get()).isEqualTo(1);
    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void exitDuringOpeningWaitsForOperationWithoutQuestionAndClosesDatabase()
      throws InterruptedException {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    storage.openGate.hold();
    var db = controller();
    db.openDatabase();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var releaser =
        new Thread(
            () -> {
              try {
                Thread.sleep(200);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              storage.openGate.release();
            });
    releaser.start();

    db.requestExit();
    releaser.join();

    assertThat(exits.get()).isEqualTo(1);
    assertThat(storage.opened).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void exitIsDelayedWithMessageWhileMigratingAndAllowedAfterwards()
      throws SQLException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    saveSettings(userFile(), true);
    storage.migrateGate.hold();
    var db = controller();
    db.openDatabase();
    ui.pumpUntil(() -> isMigrating(db));
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    db.requestExit();

    assertThat(dialogs.warnings).singleElement().asString().contains("Идёт миграция");
    assertThat(exits.get()).isZero();
    assertThat(isMigrating(db)).isTrue();

    storage.migrateGate.release();
    ui.pumpUntil(() -> isOpen(db));
    db.requestExit();
    assertThat(exits.get()).isEqualTo(1);
  }

  @Test
  void exitWhileMigrationDialogIsOpenDoesNothing() throws SQLException {
    TestDatabases.outdatedAt(userFile());
    saveSettings(userFile(), true);
    var db = controller();
    dialogs.onQuestion = db::requestExit;

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(dialogs.questions).hasSize(1);
    assertThat(dialogs.warnings).isEmpty();
    assertThat(exits.get()).isZero();
    assertThat(isOpen(db)).isTrue();
  }

  @Test
  void exitWhileAwaitingMigrationDecisionDoesNothing() throws SQLException {
    TestDatabases.outdatedAt(userFile());
    var db = controller();
    // Диалог уже закрыт, решение не отправлено: сервис ждёт команду, выход блокирован
    service.open(userFile(), false).join();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.AwaitingMigration);

    db.requestExit();

    assertThat(exits.get()).isZero();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.questions).isEmpty();
  }

  @Test
  void createdDatabaseIsOpenedAndItsPathSavedKeepingAutoOpen() {
    saveSettings(null, false);
    var db = controller();

    db.createDatabase(userFile());
    ui.pumpUntil(() -> isOpen(db));
    ui.settle();

    assertThat(db.statusProperty().get()).isEqualTo("БД открыта: " + userFile());
    assertThat(userFile()).isRegularFile();
    assertThat(new SettingsStore(settingsFile()).load().settings())
        .isEqualTo(new Settings(userFile(), false));
    assertThat(db.settings()).isEqualTo(new Settings(userFile(), false));
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void createOverExistingFileIsRefusedAndFileKept() throws IOException {
    Files.createDirectories(dataDir());
    Files.writeString(userFile(), "чужой файл");
    byte[] before = Files.readAllBytes(userFile());
    var db = controller();

    db.createDatabase(userFile());
    ui.pumpUntil(() -> !dialogs.errors.isEmpty());

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("уже существует");
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(settingsFile()).doesNotExist();
  }

  @Test
  void createFailureIsReported() throws IOException {
    Files.writeString(dir.resolve("blocker"), "файл на месте каталога");
    var db = controller();

    db.createDatabase(dir.resolve("blocker/bonds.db"));
    ui.pumpUntil(() -> !dialogs.errors.isEmpty());

    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: ошибка создания");
  }

  @Test
  void createKeepsDatabaseOpenWhenSettingsCannotBeSaved() throws IOException {
    Files.writeString(dir.resolve("blocker"), "файл на месте каталога настроек");
    var db = controllerWith(dir.resolve("blocker/settings.properties"));

    db.createDatabase(userFile());
    ui.pumpUntil(() -> !dialogs.warnings.isEmpty());

    assertThat(isOpen(db)).isTrue();
    assertThat(dialogs.warnings.get(0)).contains("не записан в настройки");
    assertThat(db.settings()).isEqualTo(Settings.defaults());
  }

  @Test
  void closeDatabaseClosesItAndDatabaseCanBeOpenedAgain() {
    var db = opened();

    db.closeDatabase();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Closed);
    ui.settle();

    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
    assertThat(dialogs.warnings).isEmpty();
    assertThat(isClosed(storage.opened.get(0))).isTrue();

    db.openDatabase();
    ui.pumpUntil(() -> isOpen(db));
  }

  @Test
  void closeWhenClosedDoesNothing() {
    var db = controller();

    db.closeDatabase();
    ui.settle();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void closeFailureIsReportedAndDatabaseStaysClosed() throws SQLException {
    var db = opened();

    TestDatabases.whileReaderLagsBehind(
        userFile(),
        () -> {
          db.closeDatabase();
          ui.pumpUntil(() -> !dialogs.warnings.isEmpty());
        });

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(dialogs.warnings.get(0)).contains("Не удалось полностью закрыть БД");
  }

  @Test
  void closeWarningArrivingAfterShutdownIsNotShown() throws SQLException {
    var db = opened();

    TestDatabases.whileReaderLagsBehind(
        userFile(),
        () -> {
          // Закрытие с предупреждением (WAL не перенесён) завершается раньше остановки
          db.closeDatabase();
          db.shutdown();
        });
    ui.settle();

    assertThat(dialogs.warnings).isEmpty();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  @Test
  void shutdownClosesOpenDatabaseOnlyOnce() {
    var db = opened();

    db.shutdown();
    db.shutdown();
    ui.settle();

    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void lateResultAfterShutdownIsIgnoredAndItsDatabaseClosed() throws InterruptedException {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    storage.openGate.hold();
    var db = controller();
    db.openDatabase();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Opening);

    assertThat(db.shutdown(Duration.ofMillis(50))).isEqualTo(ShutdownResult.TIMED_OUT);
    storage.openGate.release();
    ui.settle();

    assertThat(Eventually(() -> storage.opened.size() == 1 && isClosed(storage.opened.get(0))))
        .isTrue();
    assertThat(db.stateProperty().get()).isInstanceOf(DbState.Opening.class);
    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
  }

  private static boolean Eventually(BooleanSupplier condition) {
    return ru.onlychill.bonds2.dbservice.Eventually.holds(condition);
  }

  @Test
  void shutdownWhileClosedDoesNothing() {
    var db = controller();

    assertThatCode(db::shutdown).doesNotThrowAnyException();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void shutdownRefusedDuringMigrationDoesNotStopController() throws SQLException {
    TestDatabases.outdatedAt(userFile());
    saveSettings(userFile(), true);
    dialogs.migrationAnswer = true;
    var db = controller();
    storage.migrateGate.hold();
    db.openDatabase();
    ui.pumpUntil(() -> isMigrating(db));

    assertThat(db.shutdown(Duration.ofMillis(50))).isEqualTo(ShutdownResult.REFUSED);

    storage.migrateGate.release();
    ui.pumpUntil(() -> isOpen(db));
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void shutdownWhileAwaitingMigrationDeclinesItAndStopsService() throws SQLException, IOException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    var db = controller();
    // Сервис ждёт решения о миграции; диалога и команды ещё нет
    service.open(userFile(), false).join();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.AwaitingMigration);

    db.shutdown();
    ui.settle();

    assertThat(db.stateProperty().get()).isEqualTo(new DbState.Closed("схема устарела", null));
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: схема устарела");
    assertThat(dialogs.questions).isEmpty();
    assertThat(dialogs.errors).isEmpty();
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(backupsIn(dataDir())).isEmpty();
    // Сервис остановлен: новые операции отклоняются
    assertThat(service.open(userFile(), false).join())
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> assertThat(rejected.message()).contains("остановлен"));
  }

  @Test
  void shutdownWhileMigratingWaitsForMigrationAndThenStops()
      throws SQLException, IOException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    saveSettings(userFile(), true);
    storage.migrateGate.hold();
    var db = controller();
    db.openDatabase();
    ui.pumpUntil(() -> isMigrating(db));
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    var stopping = CompletableFuture.runAsync(db::shutdown);
    // Миграция не завершена: остановка ждёт её окончания
    assertThat(stopping).isNotDone();

    storage.migrateGate.release();
    assertThat(stopping).succeedsWithin(Duration.ofSeconds(30));
    ui.settle();

    assertThat(Database.inspect(userFile())).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(backupsIn(dataDir())).hasSize(1);
    assertThat(storage.opened).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void saveSettingsUpdatesMemoryAndFile() {
    var db = controller();

    db.saveSettings(new Settings(userFile(), false));

    assertThat(db.settings()).isEqualTo(new Settings(userFile(), false));
    assertThat(new SettingsStore(settingsFile()).load().settings())
        .isEqualTo(new Settings(userFile(), false));
  }

  @Test
  void failedSaveKeepsPreviousSettings() throws IOException {
    Files.writeString(dir.resolve("blocker"), "файл на месте каталога настроек");
    var db = controllerWith(dir.resolve("blocker/settings.properties"));

    assertThatThrownBy(() -> db.saveSettings(new Settings(userFile(), false)))
        .isInstanceOf(UncheckedIOException.class);

    assertThat(db.settings()).isEqualTo(Settings.defaults());
  }

  static final class RecordingDialogs implements Dialogs {

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
