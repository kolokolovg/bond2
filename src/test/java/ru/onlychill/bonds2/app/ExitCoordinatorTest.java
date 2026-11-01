// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static ru.onlychill.bonds2.dbservice.Eventually.holds;
import static ru.onlychill.bonds2.dbservice.TestDatabases.isClosed;
import static ru.onlychill.bonds2.dbservice.TestDatabases.newDatabaseAt;
import static ru.onlychill.bonds2.dbservice.TestDatabases.outdatedAt;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
import ru.onlychill.bonds2.dbservice.ShutdownRefusal;
import ru.onlychill.bonds2.dbservice.ShutdownResult;
import ru.onlychill.bonds2.persistence.Database;

/** Машина выхода без интерфейса: фазы, повторы и порядок цепочки завершения. */
class ExitCoordinatorTest {

  // Сильная ссылка на logger координатора: изменения уровня и хендлеров не теряются при сборке
  // мусора
  private static final Logger COORDINATOR_LOG = Logger.getLogger(ExitCoordinator.class.getName());

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
    } catch (RuntimeException | Error ignored) {
      // Поток жизненного цикла мог погибнуть от подменённого сбоя — teardown не важен
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
          public void askAboutMigration() {}

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

  @Test
  void shutdownTimeoutsMatchSpecification() {
    assertThat(ExitCoordinator.STOP_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
    assertThat(ExitCoordinator.SHUTDOWN_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    assertThat(ExitCoordinator.MIGRATION_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void shutdownTimeoutOutsideMenuIsLoggedWithoutDialog() throws Exception {
    // Диалог в пути без меню невозможен: истечение срока — только в журнал
    dbFile();
    storage.openGate.hold();
    var coordinator = coordinator();
    service.open();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    assertThat(holds(() -> service.state() instanceof DbState.Opening)).isTrue();

    var capture = new LogCapture();
    COORDINATOR_LOG.addHandler(capture);
    ShutdownResult result;
    try {
      result = coordinator.shutdownWaiting(Duration.ofMillis(50));
    } finally {
      COORDINATOR_LOG.removeHandler(capture);
    }

    assertThat(result).isEqualTo(ShutdownResult.TIMED_OUT);
    assertThat(capture.messages)
        .anySatisfy(m -> assertThat(m).contains("не завершилась в срок").contains("WAL"));
    assertThat(view.migrationWarnings).isEmpty();
    assertThat(view.errors).isEmpty();
  }

  @Test
  void lateResultAfterShutdownTimeoutIsIgnoredAndItsDatabaseClosed() throws Exception {
    dbFile();
    storage.openGate.hold();
    var coordinator = coordinator();
    service.open();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    assertThat(holds(() -> service.state() instanceof DbState.Opening)).isTrue();

    assertThat(coordinator.shutdownWaiting(Duration.ofMillis(50)))
        .isEqualTo(ShutdownResult.TIMED_OUT);
    storage.openGate.release();
    ui.settle();

    // Самолечение остановки: запоздавший итог не публикуется, БД закрывается тихо
    assertThat(holds(() -> storage.opened.size() == 1 && isClosed(storage.opened.get(0)))).isTrue();
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(view.errors).isEmpty();
    assertThat(view.migrationWarnings).isEmpty();
  }

  @Test
  void shutdownRefusedDuringMigrationDoesNotStopCoordinator() throws Exception {
    outdatedAt(dbFile());
    storage.migrateGate.hold();
    var coordinator = coordinator();
    service.open();
    assertThat(holds(() -> service.state() instanceof DbState.AwaitingMigration)).isTrue();
    service.migrate();
    assertThat(holds(() -> service.state() instanceof DbState.Migrating)).isTrue();
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    assertThat(coordinator.shutdownWaiting(Duration.ofMillis(50)))
        .isEqualTo(new ShutdownResult.Refused(ShutdownRefusal.MIGRATING));

    storage.migrateGate.release();
    assertThat(holds(() -> service.state() instanceof DbState.Open)).isTrue();
    assertThat(view.errors).isEmpty();
  }

  @Test
  void shutdownFailureIsLoggedAndExternalTerminationContinues() throws Exception {
    // Сценарий «Сбой остановки при внешнем завершении»: подписчик бросает ошибку при финальной
    // публикации «закрыта», поэтому синхронная остановка сервиса пробрасывает её вызывающему
    newDatabaseAt(dbFile());
    var coordinator = coordinator();
    service.open().join();
    // Подписка после открытия: при подписке доставляется текущее состояние, и «закрыта» пришла бы
    // сразу; сбой нужен только на финальной публикации остановки
    var failure = new AssertionError("сбой остановки");
    service.states().subscribe(failingOnClosedState(failure));

    var capture = new LogCapture();
    COORDINATOR_LOG.addHandler(capture);
    try {
      // Завершение продолжается: сбой остановки не пробрасывается вызывающему
      assertThatCode(coordinator::shutdownForExternalTermination).doesNotThrowAnyException();
    } finally {
      COORDINATOR_LOG.removeHandler(capture);
    }

    // Сбой остановки журналируется как ошибка с самой причиной, без заворачивания
    assertThat(capture.records)
        .anySatisfy(
            record -> {
              assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
              assertThat(record.getMessage())
                  .contains("Сбой остановки сервиса БД при внешнем завершении");
              assertThat(record.getThrown()).isSameAs(failure);
            });
    // Диалог в пути внешнего завершения невозможен: ошибок и предупреждений нет
    assertThat(view.errors).isEmpty();
    assertThat(view.migrationWarnings).isEmpty();
    // Завершение продолжается: БД закрыта до сбоя публикации
    assertThat(storage.opened).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  /** Подписчик, бросающий заданную ошибку только при доставке состояния «закрыта». */
  private static Flow.Subscriber<DbState> failingOnClosedState(Error error) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(DbState item) {
        if (item instanceof DbState.Closed) {
          throw error;
        }
      }

      @Override
      public void onError(Throwable throwable) {}

      @Override
      public void onComplete() {}
    };
  }

  /** Сборщик записей журнала координатора: проверка журналов остановки. */
  private static final class LogCapture extends Handler {

    final List<LogRecord> records = new ArrayList<>();
    final List<String> messages = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
      messages.add(record.getMessage());
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
