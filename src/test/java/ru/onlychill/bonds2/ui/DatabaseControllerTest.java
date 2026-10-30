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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
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

  /** Контроллер с подменённым исполнителем записи настроек: тесты проверяют поток записи. */
  private DatabaseController controllerWith(Path settings, Executor settingsExecutor) {
    return new DatabaseController(
        new SettingsStore(settings),
        defaultFile(),
        service,
        dialogs,
        exits::incrementAndGet,
        ui,
        settingsExecutor);
  }

  /** Однопоточный исполнитель записи настроек с именем потока, как в приложении. */
  private static ExecutorService settingsExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          var thread = new Thread(runnable, DatabaseController.SETTINGS_THREAD_NAME);
          thread.setDaemon(true);
          return thread;
        });
  }

  /** Исполнитель, запоминающий поток каждой задачи перед её запуском. */
  private static Executor recordingThreads(ExecutorService executor, List<Thread> threads) {
    return task ->
        executor.execute(
            () -> {
              threads.add(Thread.currentThread());
              task.run();
            });
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
  void emptySettingsFileWarnsAboutResetWithoutCopyPathAndDoesNotCreateDefaultDatabase()
      throws IOException {
    Files.createDirectories(dir.resolve("cfg"));
    Files.createFile(settingsFile());
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(dialogs.warnings).hasSize(1);
    assertThat(dialogs.warnings.get(0)).contains("настройки сброшены").doesNotContain("bad-");
    assertThat(defaultFile()).doesNotExist();
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: файл не найден");
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
    saveSettings(userFile(), true);
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: ошибка миграции");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("восстановлена").contains(".bak-");
    // Файл восстановлен копией: VACUUM INTO пишет компактный файл, поэтому сравниваем с копией
    List<String> backups = backupsIn(dataDir());
    assertThat(backups).hasSize(1);
    assertThat(Files.readAllBytes(userFile()))
        .isEqualTo(Files.readAllBytes(Path.of(backups.get(0))));
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
    // Путь записан в настройки в фоне: без него открытие пошло бы по пути по умолчанию
    ui.pumpUntil(
        () ->
            new SettingsStore(settingsFile())
                .load()
                .settings()
                .equals(new Settings(userFile(), false)));
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
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void repeatedExitRequestIsIgnoredWhileStopping() throws InterruptedException {
    newDatabaseAt(userFile());
    saveSettings(userFile(), true);
    storage.openGate.hold();
    var db = controller();
    db.openDatabase();
    assertThat(storage.openGate.awaitEntered()).isTrue();

    db.requestExit();
    db.requestExit();
    // Поток «интерфейса» не заблокирован: остановка ждёт открытия, выхода ещё нет
    ui.settle();
    assertThat(exits.get()).isZero();

    storage.openGate.release();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(exits.get()).isEqualTo(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  @Test
  void exitChainFailureShowsErrorKeepsAppAliveAndRepeatedExitStartsAgain() {
    var failFirst = new AtomicBoolean(true);
    var db =
        new DatabaseController(
            new SettingsStore(settingsFile()),
            defaultFile(),
            service,
            dialogs,
            () -> {
              if (failFirst.compareAndSet(true, false)) {
                throw new IllegalStateException("сбой остановки");
              }
              exits.incrementAndGet();
            },
            ui);

    db.requestExit();
    ui.settle();

    // Диалог показан, приложение живо, выход не начат заново сам
    assertThat(dialogs.errors).singleElement().asString().contains("сбой остановки");
    assertThat(dialogs.warnings).isEmpty();
    assertThat(exits.get()).isZero();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);

    // Повторный «Выход» начинает цепочку заново: приложение завершается
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
    assertThat(dialogs.errors).hasSize(1);
  }

  @Test
  void repeatedExitChainFailureResetsExitingAgain() {
    var db =
        new DatabaseController(
            new SettingsStore(settingsFile()),
            defaultFile(),
            service,
            dialogs,
            () -> {
              throw new IllegalStateException("сбой остановки");
            },
            ui);

    db.requestExit();
    ui.settle();
    db.requestExit();
    ui.settle();

    // Второй сбой тоже восстановил флаг выхода: третий запрос снова запускает цепочку
    assertThat(dialogs.errors).hasSize(2);
    db.requestExit();
    ui.settle();

    assertThat(dialogs.errors).hasSize(3);
    assertThat(exits.get()).isZero();
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

    db.requestExit();
    ui.settle();
    assertThat(exits.get()).isZero();
    storage.openGate.release();
    ui.pumpUntil(() -> exits.get() == 1);

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
    ui.pumpUntil(() -> exits.get() == 1);
  }

  @Test
  void exitRefusedByMigrationStartedBeforeUiUpdateKeepsWarningAndExitsAfterwards()
      throws SQLException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    saveSettings(userFile(), true);
    storage.migrateGate.hold();
    var db = controller();
    // Интерфейс ничего не применяет до pumpUntil: он отстаёт от сервиса
    db.openDatabase();
    assertThat(Eventually(() -> service.state() instanceof DbState.AwaitingMigration)).isTrue();
    var migration = service.migrate();
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    db.requestExit();
    ui.pumpUntil(() -> !dialogs.warnings.isEmpty());

    assertThat(dialogs.warnings).singleElement().asString().contains("Идёт миграция");
    assertThat(dialogs.questions).isEmpty();
    assertThat(exits.get()).isZero();

    storage.migrateGate.release();
    assertThat(migration.join()).isEqualTo(new OpenResult.Opened(userFile()));
    ui.pumpUntil(() -> isOpen(db));
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(dialogs.warnings).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  @Test
  void exitWhileMigrationDialogIsOpenCancelsDialogDeclinesAndExits()
      throws SQLException, IOException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    var db = controller();
    dialogs.pending = new CompletableFuture<>();

    db.openOnStartup();
    ui.pumpUntil(() -> dialogs.questions.size() == 1);
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.AwaitingMigration);

    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(dialogs.pending).isCancelled();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.errors).isEmpty();
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(backupsIn(dataDir())).isEmpty();
  }

  @Test
  void migrationDialogCancelledByAnotherWayIsNotReportedAsError() throws SQLException {
    TestDatabases.outdatedAt(userFile());
    saveSettings(userFile(), true);
    var db = controller();
    dialogs.pending = new CompletableFuture<>();

    db.openOnStartup();
    ui.pumpUntil(() -> dialogs.questions.size() == 1);
    dialogs.pending.cancel(true);
    ui.settle();

    assertThat(dialogs.errors).isEmpty();
    assertThat(db.stateProperty().get()).isInstanceOf(DbState.AwaitingMigration.class);
  }

  @Test
  void declinedMigrationClosesDatabaseWithoutChangingFile() throws SQLException, IOException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    var db = controller();
    dialogs.migrationAnswer = false;

    db.openOnStartup();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Closed);

    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void exitWhileAwaitingMigrationDecisionDeclinesAndExits() throws SQLException {
    TestDatabases.outdatedAt(userFile());
    var db = controller();
    // Диалога нет, решение не отправлено: сервис ждёт команду
    service.open(userFile(), false).join();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.AwaitingMigration);

    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.questions).isEmpty();
  }

  @Test
  void migrationResultArrivingAfterExitRequestOpensNoDialogAndRunsNoMigration()
      throws SQLException, IOException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    storage.openGate.hold();
    var db = controller();
    db.openDatabase();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Opening);

    // Выход запрошен, пока открытие ещё идёт; результат «нужна миграция» приходит после запроса
    db.requestExit();
    storage.openGate.release();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(dialogs.questions).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.errors).isEmpty();
    assertThat(exits.get()).isEqualTo(1);
    assertThat(service.state()).isEqualTo(new DbState.Closed("схема устарела", null));
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(backupsIn(dataDir())).isEmpty();
  }

  @Test
  void exitRequestedBeforeUiAppliesAwaitingMigrationExitsWithoutDialogOrWarning()
      throws SQLException, IOException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    saveSettings(userFile(), true);
    storage.openGate.hold();
    var db = controller();
    db.openDatabase();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Opening);

    // Сервис уже ждёт решения, а интерфейс ещё не применил ни состояние, ни результат открытия
    storage.openGate.release();
    assertThat(Eventually(() -> service.state() instanceof DbState.AwaitingMigration)).isTrue();
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(dialogs.questions).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.errors).isEmpty();
    assertThat(exits.get()).isEqualTo(1);
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(backupsIn(dataDir())).isEmpty();
  }

  @Test
  void repeatedExitRequestWhileAwaitingMigrationDecisionIsIgnored() throws SQLException {
    TestDatabases.outdatedAt(userFile());
    var db = controller();
    // Диалога нет, решение не отправлено: сервис ждёт команду
    service.open(userFile(), false).join();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.AwaitingMigration);

    db.requestExit();
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(exits.get()).isEqualTo(1);
    assertThat(dialogs.questions).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void createdDatabaseIsOpenedAndItsPathSavedKeepingAutoOpen() {
    saveSettings(null, false);
    var db = controller();

    db.createDatabase(userFile());
    ui.pumpUntil(() -> isOpen(db));
    var expected = new Settings(userFile(), false);
    // Путь записывается в настройки в фоне: ждём завершения записи
    ui.pumpUntil(() -> new SettingsStore(settingsFile()).load().settings().equals(expected));

    assertThat(db.statusProperty().get()).isEqualTo("БД открыта: " + userFile());
    assertThat(userFile()).isRegularFile();
    assertThat(new SettingsStore(settingsFile()).load().settings()).isEqualTo(expected);
    assertThat(db.settings()).isEqualTo(expected);
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

  // Сильная ссылка на logger контроллера: изменения уровня и хендлеров не теряются при сборке
  // мусора
  private static final Logger CONTROLLER_LOG = Logger.getLogger(DatabaseController.class.getName());

  @Test
  void createRejectedByStateRaceIsQuietAndLogged() {
    newDatabaseAt(userFile());
    var db = controller();
    // Сервис открывает БД мимо интерфейса: сервис уже открыт, интерфейс ещё не прокачан
    service.open(userFile(), false).join();

    var capture = new LogCapture();
    CONTROLLER_LOG.addHandler(capture);
    CONTROLLER_LOG.setLevel(Level.FINE);
    try {
      db.createDatabase(dataDir().resolve("other.db"));
      ui.settle();
    } finally {
      CONTROLLER_LOG.removeHandler(capture);
      CONTROLLER_LOG.setLevel(null);
    }

    // Гонка состояния — не ошибка файла: диалога нет, отклонение зафиксировано в журнале
    assertThat(dialogs.errors).isEmpty();
    assertThat(capture.messages).anySatisfy(m -> assertThat(m).contains("Создание отклонено"));
    assertThat(dataDir().resolve("other.db")).doesNotExist();
  }

  @Test
  void createRejectedDuringShutdownIsQuietAndStateStaysClosed() throws InterruptedException {
    newDatabaseAt(userFile());
    var db = controller();
    // Открытие идёт мимо контроллера: интерфейс остаётся в состоянии «закрыта»
    storage.openGate.hold();
    service.open(userFile(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    service.shutdownAsync(DatabaseController.SHUTDOWN_TIMEOUT);

    db.createDatabase(dataDir().resolve("other.db"));
    ui.settle();

    // Начатая остановка отклоняет создание тихо: диалогов нет, файл не создан
    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dataDir().resolve("other.db")).doesNotExist();

    // Остановка доходит до конца: состояние «закрыта» — пункты меню снова доступны
    storage.openGate.release();
    ui.pumpUntil(
        () -> db.stateProperty().get() instanceof DbState.Closed closed && closed.reason() == null);
    ui.settle();

    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
    assertThat(dialogs.errors).isEmpty();
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
  void unexpectedCloseFailureIsReportedAsErrorAndDatabaseStaysClosed() {
    var db = opened();
    storage.closeFailure = new IllegalStateException("сбой закрытия");

    db.closeDatabase();
    ui.pumpUntil(() -> !dialogs.errors.isEmpty());
    ui.settle();

    assertThat(db.stateProperty().get()).isInstanceOf(DbState.Closed.class);
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: внутренняя ошибка");
    assertThat(dialogs.errors).singleElement().asString().contains("сбой закрытия");
    assertThat(dialogs.warnings).isEmpty();
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
    // Самолечение остановки: интерфейс приходит к согласованному финалу «закрыта»,
    // промежуточное «открывается» не публикуется и диалогов запоздавший итог не порождает
    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
  }

  private static boolean Eventually(BooleanSupplier condition) {
    return ru.onlychill.bonds2.dbservice.Eventually.holds(condition);
  }

  @Test
  void shutdownTimeoutsMatchSpecification() {
    assertThat(DatabaseController.STOP_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
    assertThat(DatabaseController.SHUTDOWN_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    assertThat(DatabaseController.MIGRATION_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void shutdownAfterExitThroughMenuIsIdempotent() {
    var db = opened();
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThatCode(db::shutdown).doesNotThrowAnyException();
    assertThat(exits.get()).isEqualTo(1);
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
    // Реальный drain при остановке во время миграции: бюджет ниже дедлайнов pitest-миньона
    assertThat(stopping).succeedsWithin(Duration.ofSeconds(10));
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
  void saveSettingsWritesOnSettingsThreadAndAppliesOnUiQueue() {
    var executor = settingsExecutor();
    var writeThreads = new CopyOnWriteArrayList<Thread>();
    var db = controllerWith(settingsFile(), recordingThreads(executor, writeThreads));
    try {
      var saving = db.saveSettings(new Settings(userFile(), false));

      // Применение — только через очередь потока интерфейса: без прокачки настройки старые
      assertThat(db.settings()).isEqualTo(Settings.defaults());
      ui.pumpUntil(saving::isDone);

      // Запись выполнена вне потока интерфейса, на именованном потоке исполнителя
      assertThat(saving).isCompleted();
      assertThat(writeThreads).hasSize(1);
      assertThat(writeThreads.get(0).getName()).isEqualTo(DatabaseController.SETTINGS_THREAD_NAME);
      assertThat(writeThreads.get(0)).isNotEqualTo(Thread.currentThread());
      assertThat(db.settings()).isEqualTo(new Settings(userFile(), false));
      assertThat(new SettingsStore(settingsFile()).load().settings())
          .isEqualTo(new Settings(userFile(), false));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void createdDatabasePathIsRememberedOffUiThread() {
    saveSettings(null, false);
    var executor = settingsExecutor();
    var writeThreads = new CopyOnWriteArrayList<Thread>();
    var db = controllerWith(settingsFile(), recordingThreads(executor, writeThreads));
    try {
      db.createDatabase(userFile());
      ui.pumpUntil(() -> isOpen(db));
      var expected = new Settings(userFile(), false);
      ui.pumpUntil(() -> new SettingsStore(settingsFile()).load().settings().equals(expected));

      assertThat(db.settings()).isEqualTo(expected);
      assertThat(dialogs.warnings).isEmpty();
      assertThat(writeThreads).hasSize(1);
      assertThat(writeThreads.get(0).getName()).isEqualTo(DatabaseController.SETTINGS_THREAD_NAME);
      assertThat(writeThreads.get(0)).isNotEqualTo(Thread.currentThread());
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void shutdownStopsSettingsExecutor() {
    var executor = settingsExecutor();
    var db = controllerWith(settingsFile(), executor);

    db.shutdown();

    assertThat(executor.isShutdown()).isTrue();
  }

  @Test
  void saveSettingsUpdatesMemoryAndFile() {
    var db = controller();

    var saving = db.saveSettings(new Settings(userFile(), false));
    ui.pumpUntil(saving::isDone);

    assertThat(saving).isCompleted();
    assertThat(db.settings()).isEqualTo(new Settings(userFile(), false));
    assertThat(new SettingsStore(settingsFile()).load().settings())
        .isEqualTo(new Settings(userFile(), false));
  }

  @Test
  void failedSaveKeepsPreviousSettings() throws IOException {
    Files.writeString(dir.resolve("blocker"), "файл на месте каталога настроек");
    var db = controllerWith(dir.resolve("blocker/settings.properties"));

    var saving = db.saveSettings(new Settings(userFile(), false));
    ui.pumpUntil(saving::isDone);

    assertThat(saving).isCompletedExceptionally();
    assertThatThrownBy(saving::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(UncheckedIOException.class);
    assertThat(db.settings()).isEqualTo(Settings.defaults());
  }

  /** Сборщик записей журнала контроллера: проверка тихого отклонения создания БД. */
  private static final class LogCapture extends Handler {

    final List<String> messages = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      messages.add(record.getMessage());
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  static final class RecordingDialogs implements Dialogs {

    final List<String> questions = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    boolean migrationAnswer = true;

    /** Если задан, диалог остаётся открытым, пока тест не завершит этот future. */
    @Nullable CompletableFuture<Boolean> pending;

    @Override
    public CompletableFuture<Boolean> confirmMigration(String question) {
      questions.add(question);
      return pending != null ? pending : CompletableFuture.completedFuture(migrationAnswer);
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
