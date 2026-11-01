// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.onlychill.bonds2.dbservice.TestDatabases.newDatabaseAt;
import static ru.onlychill.bonds2.dbservice.TestDatabases.outdatedAt;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
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
import ru.onlychill.bonds2.dbservice.OpenResult;
import ru.onlychill.bonds2.dbservice.ShutdownRefusal;
import ru.onlychill.bonds2.dbservice.ShutdownResult;
import ru.onlychill.bonds2.persistence.Database;

/** Машина выхода без интерфейса: фазы, повторы и порядок цепочки завершения. */
class ExitCoordinatorTest {

  @TempDir Path dir;

  private final GatedStorage storage = new GatedStorage();
  private final DatabaseService service = new DatabaseService(storage);
  private final RecordingExitView view = new RecordingExitView();
  private final AtomicInteger exitAttempts = new AtomicInteger();
  private final UiQueue ui = new UiQueue();

  @AfterEach
  void stopService() {
    storage.openGate.release();
    storage.migrateGate.release();
    try {
      service.shutdown(Duration.ofSeconds(30));
    } catch (RuntimeException ignored) {
      // Поток жизненного цикла мог погибнуть от подменённого сбоя закрытия — teardown не важен
    }
  }

  /** Очередь задач «потока интерфейса»: задачи выполняются на потоке теста в {@link #pumpUntil}. */
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
  }

  /** Подстава {@link ExitView}: предупреждения и ошибки записываются, подтверждение ждёт тест. */
  private static final class RecordingExitView implements ExitView {

    final List<String> migrationWarnings = new CopyOnWriteArrayList<>();
    final List<String> errors = new CopyOnWriteArrayList<>();
    final List<CompletableFuture<Void>> timeoutConfirmations = new CopyOnWriteArrayList<>();

    @Override
    public CompletableFuture<Void> warnTimedOutBeforeExit(String message) {
      var confirmation = new CompletableFuture<Void>();
      timeoutConfirmations.add(confirmation);
      return confirmation;
    }

    @Override
    public void warnMigrationInProgress(String message) {
      migrationWarnings.add(message);
    }

    @Override
    public void showError(String message) {
      errors.add(message);
    }
  }

  private ExitCoordinator coordinator() {
    return coordinatorWith(exitAttempts::incrementAndGet);
  }

  private ExitCoordinator coordinatorWith(Runnable exit) {
    var coordinator = new ExitCoordinator(service, view, exit, ui, service::state);
    coordinator.attachMigration(
        new MigrationView() {
          @Override
          public void cancelDialog() {}

          @Override
          public @Nullable CompletableFuture<OpenResult> currentMigration() {
            return null;
          }
        });
    return coordinator;
  }

  private Path dbFile() {
    Path file = dir.resolve("data/bonds.db");
    // Файл единственной БД задаётся хранилищу: сервис открывает его без параметров
    storage.databaseFile = file;
    return file;
  }

  @Test
  void requestExitWithClosedDatabaseStopsAndExitsOnce() {
    var coordinator = coordinator();

    coordinator.requestExit();
    ui.pumpUntil(coordinator::concluded);

    assertThat(exitAttempts.get()).isEqualTo(1);
    assertThat(view.errors).isEmpty();
    assertThat(view.migrationWarnings).isEmpty();
  }

  @Test
  void repeatedRequestExitDuringExitIsIgnored() {
    var coordinator = coordinator();

    coordinator.requestExit();
    // Итог остановки ещё не доставлен: фаза EXITING, повторный запрос ничего не делает
    assertThat(coordinator.exiting()).isTrue();
    coordinator.requestExit();
    ui.pumpUntil(coordinator::concluded);

    assertThat(exitAttempts.get()).isEqualTo(1);
  }

  @Test
  void refusedBecauseOfMigrationReturnsToRunningWithWarning() {
    var coordinator = coordinator();

    coordinator.onExitResult(new ShutdownResult.Refused(ShutdownRefusal.MIGRATING));

    assertThat(coordinator.exiting()).isFalse();
    assertThat(coordinator.concluded()).isFalse();
    assertThat(view.migrationWarnings).hasSize(1);
    assertThat(exitAttempts.get()).isZero();
  }

  @Test
  void refusedWithPendingDecisionDeclinesMigrationAndStopsAgain() throws Exception {
    outdatedAt(dbFile());
    var coordinator = coordinator();

    var opened = service.open().join();
    assertThat(opened).isInstanceOf(OpenResult.NeedsMigration.class);
    assertThat(service.state()).isInstanceOf(DbState.AwaitingMigration.class);

    coordinator.onExitResult(new ShutdownResult.Refused(ShutdownRefusal.DECISION_PENDING));
    ui.pumpUntil(coordinator::concluded);

    assertThat(exitAttempts.get()).isEqualTo(1);
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);
    assertThat(Database.inspect(dbFile())).isEqualTo(Database.State.NEEDS_MIGRATION);
  }

  @Test
  void timedOutExitsAfterUserConfirmsWarning() {
    var coordinator = coordinator();

    coordinator.onExitResult(ShutdownResult.TIMED_OUT);
    assertThat(view.timeoutConfirmations).hasSize(1);
    assertThat(exitAttempts.get()).isZero();

    view.timeoutConfirmations.get(0).complete(null);
    ui.pumpUntil(() -> exitAttempts.get() == 1);
  }

  @Test
  void timedOutWithFailingWarningDialogStillExits() {
    var coordinator = coordinator();

    coordinator.onExitResult(ShutdownResult.TIMED_OUT);
    assertThat(view.timeoutConfirmations).hasSize(1);

    // Диалог подтверждения упал: приложение всё равно завершается, «зомби» не возникает
    view.timeoutConfirmations.get(0).completeExceptionally(new IllegalStateException("сбой"));
    ui.pumpUntil(() -> exitAttempts.get() == 1);

    assertThat(exitAttempts.get()).isEqualTo(1);
  }

  @Test
  void repeatedExitFailureAfterConcludedReturnsAppToRunning() {
    AtomicInteger attempts = new AtomicInteger();
    // Первые два вызова завершения падают, третий успешен
    Runnable failingTwice =
        () -> {
          if (attempts.incrementAndGet() <= 2) {
            throw new IllegalStateException("сбой завершения");
          }
        };
    var coordinator = coordinatorWith(failingTwice);

    coordinator.onExitResult(ShutdownResult.TIMED_OUT);
    view.timeoutConfirmations.get(0).complete(null);
    ui.pumpUntil(() -> !view.errors.isEmpty());

    // Повторный сбой после известного итога: приложение снова закрываемо
    assertThat(coordinator.exiting()).isFalse();
    assertThat(coordinator.concluded()).isFalse();
    assertThat(view.errors).hasSize(1);

    coordinator.requestExit();
    ui.pumpUntil(() -> attempts.get() == 3);
    assertThat(coordinator.concluded()).isTrue();
  }

  @Test
  void exitChainStopsDatabaseThenReleasesLockThenDisarmsHook() throws Exception {
    newDatabaseAt(dbFile());
    service.open().join();
    assertThat(service.state()).isInstanceOf(DbState.Open.class);
    List<String> events = new CopyOnWriteArrayList<>();
    var coordinator = coordinator();
    Runnable releaseLock =
        () -> {
          // К моменту блокировки остановка уже выполнена: БД закрыта
          assertThat(service.state()).isInstanceOf(DbState.Closed.class);
          events.add("lock");
        };
    Runnable disarmHook = () -> events.add("hook");

    assertThat(coordinator.runExitChain(releaseLock, disarmHook)).isTrue();

    assertThat(events).containsExactly("lock", "hook");
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);

    // Повторный вызов — no-op
    assertThat(coordinator.runExitChain(releaseLock, disarmHook)).isFalse();
    assertThat(events).containsExactly("lock", "hook");
  }

  @Test
  void exitChainReleasesLockAndDisarmsHookEvenWhenStopFails() throws Exception {
    newDatabaseAt(dbFile());
    service.open().join();
    storage.closeFailure = new IllegalStateException("сбой закрытия");
    List<String> events = new CopyOnWriteArrayList<>();
    var coordinator = coordinator();

    assertThat(coordinator.runExitChain(() -> events.add("lock"), () -> events.add("hook")))
        .isTrue();

    assertThat(events).containsExactly("lock", "hook");
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);
  }

  @Test
  void shutdownWaitingWithOpenDatabaseClosesIt() throws Exception {
    newDatabaseAt(dbFile());
    service.open().join();
    assertThat(service.state()).isInstanceOf(DbState.Open.class);
    var coordinator = coordinator();

    ShutdownResult result = coordinator.shutdownWaiting(Duration.ofSeconds(5));

    assertThat(result).isEqualTo(ShutdownResult.STOPPED);
    assertThat(coordinator.concluded()).isTrue();
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);
  }
}
