// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.onlychill.bonds2.dbservice.TestDatabases.isClosed;
import static ru.onlychill.bonds2.dbservice.TestDatabases.newDatabaseAt;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
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
import ru.onlychill.bonds2.persistence.InstanceLock;

/**
 * Конвейер запуска и завершения ядра с подставным интерфейсом: порядок шагов, поведение при отказах
 * до окна, хук завершения JVM и цепочка штатного завершения. Без JavaFX: интерфейсный поток
 * подменяется очередью задач на потоке теста.
 */
class AppCoreTest {

  // Сильная ссылка на logger ядра: изменения уровня и хендлеров не теряются при сборке мусора
  private static final Logger CORE_LOG = Logger.getLogger(AppCore.class.getName());

  @TempDir Path dir;

  private final GatedStorage storage = new GatedStorage();
  private final DatabaseService service = new DatabaseService(storage);
  private final RecordingAppView view = new RecordingAppView();
  private final RecordingHooks hooks = new RecordingHooks(view.events);
  private final List<Throwable> startFailures = new CopyOnWriteArrayList<>();

  @AfterEach
  void stopService() {
    storage.openGate.release();
    storage.migrateGate.release();
    storage.backupGate.release();
    try {
      service.shutdown(Duration.ofSeconds(30));
    } catch (RuntimeException | Error ignored) {
      // Поток жизненного цикла мог погибнуть от подменённого сбоя — teardown не важен
    }
  }

  private Path lockFile() {
    return dir.resolve("bonds2.lock");
  }

  private Path userFile() {
    Path file = dir.resolve("data/bonds.db");
    storage.databaseFile = file;
    return file;
  }

  private AppCore core() {
    return new AppCore(view, service, lockFile(), hooks);
  }

  /** Запускает ядро на фоновом потоке: главный поток теста управляет доставкой результатов. */
  private Thread startInBackground(AppCore core) {
    var thread =
        new Thread(
            () -> {
              try {
                core.start();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              } catch (RuntimeException | Error e) {
                startFailures.add(e);
              }
            },
            "test-app-core");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  @Test
  void startupPipelineShowsWindowAfterLockAndFixesReadyOnlyAfterOpenDelivery() throws Exception {
    newDatabaseAt(userFile());
    storage.openGate.hold();
    var core = core();
    var started = startInBackground(core);

    // Тулкит поднят, окно показано — в этом порядке; блокировка уже у ядра
    view.ui.pumpUntil(() -> view.events.contains("window"));
    assertThat(view.events.indexOf("toolkit")).isLessThan(view.events.indexOf("window"));
    assertThat(view.events).doesNotContain("shutdown");
    assertThat(InstanceLock.tryAcquire(lockFile())).isEmpty();
    assertThat(hooks.registered).hasSize(1);
    assertThat(hooks.disarmed).isEmpty();

    // Открытие инициировано после окна, но готовность не фиксируется до доставки итога
    assertThat(storage.openGate.awaitEntered()).isTrue();
    assertThat(core.ready()).isFalse();

    storage.openGate.release();
    view.ui.pumpUntil(core::ready);
    assertThat(core.ready()).isTrue();
    assertThat(service.state()).isInstanceOf(DbState.Open.class);

    // Штатный выход: интерфейс гаснет, цепочка освобождает блокировку, разряжает хук
    // и отпускает главный поток; повторный запрос выхода второй цепочки не создаёт
    var presenter = view.presenter();
    view.ui.execute(presenter::requestExit);
    view.ui.execute(presenter::requestExit);
    view.ui.pumpUntil(() -> view.events.stream().filter("shutdown"::equals).count() == 2);
    assertThat(startFailures).isEmpty();

    // Разряжение хука — до последнего гашения тулкита, хук снят ровно один раз
    assertThat(view.events.lastIndexOf("disarm")).isLessThan(view.events.lastIndexOf("shutdown"));
    assertThat(hooks.disarmed).containsExactly(hooks.registered.get(0));
    // Блокировка освобождена после остановки БД: её берёт новый экземпляр
    Optional<InstanceLock> reacquired = InstanceLock.tryAcquire(lockFile());
    assertThat(reacquired).isPresent();
    reacquired.ifPresent(InstanceLock::close);
    // Сервис остановлен: новые операции отклоняются
    assertThat(service.open().join())
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> assertThat(rejected.message()).contains("остановлен"));

    // Главный поток освобождён после цепочки: запуск вернулся
    started.join(10_000);
    assertThat(started.isAlive()).isFalse();

    // Повторное срабатывание хука после штатного выхода ничего не делает
    assertThatCode(core::stopForExternalShutdown).doesNotThrowAnyException();
    assertThat(hooks.disarmed).hasSize(1);
  }

  @Test
  void secondInstanceShowsStartupErrorAndExitsWithoutWindowOrDatabase() throws Exception {
    userFile();
    try (InstanceLock first = InstanceLock.tryAcquire(lockFile()).orElseThrow()) {
      var core = core();

      core.start();

      assertThat(view.events)
          .containsExactly("toolkit", "error:Приложение уже запущено.", "shutdown");
      assertThat(storage.threads).isEmpty();
      assertThat(hooks.registered).isEmpty();
      assertThat(core.ready()).isFalse();
    }
  }

  @Test
  void lockFailureShowsStartupErrorWithCauseAndExits() throws Exception {
    Files.writeString(dir.resolve("blocker"), "файл");
    var core = new AppCore(view, service, dir.resolve("blocker/bonds2.lock"), hooks);

    core.start();

    assertThat(view.events).hasSize(3);
    assertThat(view.events.get(1)).startsWith("error:Не удалось запустить приложение");
    assertThat(view.events.get(2)).isEqualTo("shutdown");
    assertThat(storage.threads).isEmpty();
  }

  @Test
  void externalShutdownStopsRunningServiceWhileHookStaysArmed() throws Exception {
    newDatabaseAt(userFile());
    var core = core();
    var started = startInBackground(core);
    view.ui.pumpUntil(core::ready);

    core.stopForExternalShutdown();

    // Сервис остановлен в пределах короткого срока: БД закрыта, новые операции отклоняются
    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);
    assertThat(service.open().join()).isInstanceOf(OpenResult.Rejected.class);
    // Штатного выхода не было: хук ещё вооружён
    assertThat(hooks.disarmed).isEmpty();

    started.interrupt();
    started.join(10_000);
    assertThat(startFailures).isEmpty();
  }

  @Test
  void hookStopFailureIsLoggedAndTerminationContinues() throws Exception {
    newDatabaseAt(userFile());
    var core = core();
    var started = startInBackground(core);
    view.ui.pumpUntil(core::ready);
    // Подписка после открытия: при подписке доставляется текущее состояние, а «закрыта»
    // придёт только на финальной публикации остановки — там подписчик и падает
    var failure = new AssertionError("сбой остановки");
    service.states().subscribe(failingOnClosedState(failure));

    var capture = new LogCapture();
    CORE_LOG.addHandler(capture);
    try {
      assertThatCode(core::stopForExternalShutdown).doesNotThrowAnyException();
    } finally {
      CORE_LOG.removeHandler(capture);
    }

    // Сбой остановки журналируется обёрткой хука и завершению не мешает
    assertThat(capture.messages)
        .anySatisfy(message -> assertThat(message).contains("Не удалось остановить сервис БД"));
    // Завершение продолжается: БД закрыта до сбоя публикации
    assertThat(isClosed(storage.opened.get(0))).isTrue();

    started.interrupt();
    started.join(10_000);
  }

  @Test
  void exitChainReleasesLockAndDisarmsHookEvenWhenDatabaseCloseFails() throws Exception {
    newDatabaseAt(userFile());
    var core = core();
    var started = startInBackground(core);
    view.ui.pumpUntil(core::ready);
    storage.closeFailure = new IllegalStateException("сбой закрытия");

    view.ui.execute(() -> view.presenter().requestExit());
    view.ui.pumpUntil(() -> hooks.disarmed.size() == 1);

    // Блокировка освобождается и при сбое закрытия: её берёт новый экземпляр
    Optional<InstanceLock> reacquired = InstanceLock.tryAcquire(lockFile());
    assertThat(reacquired).isPresent();
    reacquired.ifPresent(InstanceLock::close);
    started.join(10_000);
    assertThat(started.isAlive()).isFalse();
    assertThat(startFailures).isEmpty();
  }

  @Test
  void windowBuildFailureExtinguishesToolkitAndRethrows() {
    newDatabaseAt(userFile());
    view.failWindow = true;
    var core = core();

    // Сбой сборки окна пробрасывается, тулкит гасится: процесс не остаётся «зомби» с блокировкой
    assertThatThrownBy(core::start).isInstanceOf(IllegalStateException.class);
    assertThat(view.events).endsWith("shutdown");
    assertThat(storage.threads).isEmpty();
    assertThat(core.ready()).isFalse();
  }

  @Test
  void disarmDuringJvmShutdownDoesNotBreakChain() throws Exception {
    newDatabaseAt(userFile());
    var core = core();
    var started = startInBackground(core);
    view.ui.pumpUntil(core::ready);
    // Выход JVM уже идёт: среда выполнения отказывает в снятии хука
    hooks.refuseDisarm = true;

    view.ui.execute(() -> view.presenter().requestExit());
    view.ui.pumpUntil(() -> view.events.stream().filter("shutdown"::equals).count() == 2);

    // Цепочка дошла до конца: блокировка освобождена, главный поток отпущен
    Optional<InstanceLock> reacquired = InstanceLock.tryAcquire(lockFile());
    assertThat(reacquired).isPresent();
    reacquired.ifPresent(InstanceLock::close);
    started.join(10_000);
    assertThat(started.isAlive()).isFalse();
    assertThat(startFailures).isEmpty();
  }

  @Test
  void interruptedMainThreadExtinguishesToolkitAndStopsWaiting() throws Exception {
    newDatabaseAt(userFile());
    var core = core();
    var started = startInBackground(core);
    view.ui.pumpUntil(core::ready);

    started.interrupt();
    started.join(10_000);

    // Прерванное ожидание гасит тулкит: процесс не остаётся «зомби» с занятой блокировкой
    assertThat(view.events).endsWith("shutdown");
    assertThat(startFailures).isEmpty();
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

  /** Подстава порта интерфейса: события записываются, задачи «потока интерфейса» — в очередь. */
  private static final class RecordingAppView implements AppView {

    final List<String> events = new CopyOnWriteArrayList<>();
    final UiQueue ui = new UiQueue();
    private volatile @Nullable DbPresenter presenter;

    /** Если задан, показ окна падает: сборка интерфейса не удалась. */
    volatile boolean failWindow;

    @Override
    public Executor startToolkit() {
      events.add("toolkit");
      return ui;
    }

    @Override
    public void showMainWindow(DatabaseService service, DbPresenter shown, Executor uiExecutor) {
      if (failWindow) {
        throw new IllegalStateException("сбой сборки окна");
      }
      this.presenter = shown;
      events.add("window");
    }

    DbPresenter presenter() {
      return Objects.requireNonNull(presenter, "окно не показано");
    }

    @Override
    public void showStartupError(String message) {
      events.add("error:" + message);
    }

    @Override
    public void shutdown() {
      events.add("shutdown");
    }

    @Override
    public CompletableFuture<Void> warnTimedOutBeforeExit(String message) {
      events.add("warnTimeout:" + message);
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void warnMigrationInProgress(String message) {
      events.add("warnMigration:" + message);
    }

    @Override
    public void showError(String message) {
      events.add("error:" + message);
    }
  }

  /** Подстава хуков завершения JVM: регистрация и разряжение записываются в общий список. */
  private static final class RecordingHooks implements AppCore.Hooks {

    final List<Thread> registered = new CopyOnWriteArrayList<>();
    final List<Thread> disarmed = new CopyOnWriteArrayList<>();
    private final List<String> events;

    /** Если задан, разряжение отказывает, как при уже идущем выходе JVM. */
    volatile boolean refuseDisarm;

    RecordingHooks(List<String> events) {
      this.events = events;
    }

    @Override
    public void register(Thread hook) {
      registered.add(hook);
    }

    @Override
    public void disarm(Thread hook) {
      if (refuseDisarm) {
        throw new IllegalStateException("Shutdown in progress");
      }
      disarmed.add(hook);
      events.add("disarm");
    }
  }

  /** Очередь задач «потока интерфейса»: задачи выполняются на потоке теста в {@code pumpUntil}. */
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

  /** Сборщик записей журнала ядра: проверка журнала сбоя остановки в хуке. */
  private static final class LogCapture extends Handler {

    final List<String> messages = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      if (record.getLevel().intValue() >= Level.WARNING.intValue()) {
        messages.add(record.getMessage());
      }
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
