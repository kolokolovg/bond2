// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.onlychill.bonds2.dbservice.TestDatabases.preMigrationCopiesIn;

import java.io.IOException;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.GatedStorage;
import ru.onlychill.bonds2.dbservice.TestDatabases;

/**
 * Тесты сценария диалога миграции: контроллер собирается с минимальным окружением, сценарий идёт
 * через {@link DatabaseController#openOnStartup()}. Это не тесты выхода и остановки: гонки выхода
 * проверяются в {@code DatabaseControllerTest}.
 */
class MigrationFlowTest {

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
    storage.backupGate.release();
    service.shutdown(Duration.ofSeconds(30));
  }

  private Path dataDir() {
    return dir.resolve("data");
  }

  private Path userFile() {
    return dataDir().resolve("bonds.db");
  }

  private DatabaseController controller() {
    storage.databaseFile = userFile();
    return new DatabaseController(service, dialogs, exits::incrementAndGet, ui);
  }

  private static boolean isOpen(DatabaseController db) {
    return db.stateProperty().get() instanceof DbState.Open;
  }

  /** Ждёт, пока открытие придёт к итогу, и доставляет все диалоги. */
  private void settleAfterOperation(DatabaseController db) {
    ui.pumpUntil(
        () ->
            db.stateProperty().get() instanceof DbState.Open
                || (db.stateProperty().get() instanceof DbState.Closed closed
                    && closed.reason() != null));
    ui.settle();
  }

  @Test
  void outdatedSchemaIsMigratedAfterConfirmationWithBackup() throws SQLException, IOException {
    TestDatabases.outdatedAt(userFile());
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(dialogs.questions).hasSize(1);
    assertThat(dialogs.questions.get(0))
        .contains("bonds2.pre-migration.db")
        .contains("одной транзакцией");
    assertThat(isOpen(db)).isTrue();
    assertThat(preMigrationCopiesIn(dataDir())).hasSize(1);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void refusedMigrationLeavesFileUntouchedAndDatabaseClosed() throws SQLException, IOException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    dialogs.migrationAnswer = false;
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(dialogs.questions).hasSize(1);
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: схема устарела");
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void failedMigrationRollsBackAndShowsErrorWithUnchangedFileNote()
      throws SQLException, IOException {
    TestDatabases.forgottenHistoryAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: ошибка миграции");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0)).contains("Файл БД не изменён");
    // Транзакция миграции откатилась: файл остался до-миграционным, служебная копия создана
    assertThat(preMigrationCopiesIn(dataDir())).hasSize(1);
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void migrationDialogCancelledByAnotherWayIsNotReportedAsError() throws SQLException {
    TestDatabases.outdatedAt(userFile());
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
    var db = controller();
    dialogs.migrationAnswer = false;

    db.openOnStartup();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Closed);

    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void warnBeforeExitRecordsMessageAndWaitsForPendingFuture() {
    dialogs.pendingExitWarning = new CompletableFuture<>();
    var warning = dialogs.warnBeforeExit("предупреждение перед выходом");

    assertThat(dialogs.exitWarnings).containsExactly("предупреждение перед выходом");
    assertThat(warning).isNotDone();

    dialogs.pendingExitWarning.complete(null);
    assertThat(warning).isCompleted();
  }

  static final class RecordingDialogs implements Dialogs {

    final List<String> questions = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<String> exitWarnings = new ArrayList<>();
    boolean migrationAnswer = true;

    /** Если задан, диалог остаётся открытым, пока тест не завершит этот future. */
    @Nullable CompletableFuture<Boolean> pending;

    /** Если задан, диалог выхода остаётся открытым, пока тест не завершит этот future. */
    @Nullable CompletableFuture<Void> pendingExitWarning;

    @Override
    public CompletableFuture<Boolean> confirmMigration(String question) {
      questions.add(question);
      return pending != null ? pending : CompletableFuture.completedFuture(migrationAnswer);
    }

    @Override
    public CompletableFuture<Void> warnBeforeExit(String message) {
      exitWarnings.add(message);
      return pendingExitWarning != null
          ? pendingExitWarning
          : CompletableFuture.completedFuture(null);
    }

    @Override
    public void warn(String message) {
      warnings.add(message);
    }

    @Override
    public void info(String message) {
      infos.add(message);
    }

    @Override
    public void error(String message) {
      errors.add(message);
    }
  }
}
