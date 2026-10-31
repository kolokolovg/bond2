// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsStore;

/**
 * Тесты фоновой записи настроек: {@link SettingsPersistence} собирается напрямую, без контроллера.
 * Проверяются поток записи, применение настроек через очередь потока интерфейса, сохранение прежних
 * настроек при сбое записи и ожидание незавершённой записи при остановке исполнителя.
 */
class SettingsPersistenceTest {

  // Сильная ссылка на logger записи настроек: изменения хендлеров не теряются при сборке мусора
  private static final Logger SETTINGS_LOG = Logger.getLogger(SettingsPersistence.class.getName());

  @TempDir Path dir;

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
  }

  private Path userFile() {
    return dir.resolve("data/bonds.db");
  }

  private Path settingsFile() {
    return dir.resolve("cfg/settings.properties");
  }

  /**
   * Однопоточный исполнитель записи настроек; имя потока нейтральное, тестовые проверки его не
   * читают.
   */
  private static ExecutorService settingsExecutor() {
    return Executors.newSingleThreadExecutor(
        runnable -> {
          var thread = new Thread(runnable, "test-settings");
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

  /**
   * Исполнитель записи настроек с воротами: реальный однопоточный исполнитель, у которого каждая
   * задача перед запуском сигнализирует о начале («запись началась») и ждёт разрешения на
   * продолжение. Остановка и ожидание делегируются реальному исполнителю, поэтому {@code
   * stopWriting} видит в обёртке {@link ExecutorService} и ждёт удержанную запись по-настоящему.
   */
  static final class GatedExecutorService implements ExecutorService {

    private final ExecutorService delegate;
    private final CountDownLatch entered;
    private final CountDownLatch release;

    GatedExecutorService(ExecutorService delegate, CountDownLatch entered, CountDownLatch release) {
      this.delegate = delegate;
      this.entered = entered;
      this.release = release;
    }

    @Override
    public void execute(Runnable task) {
      delegate.execute(
          () -> {
            entered.countDown();
            awaitGate(release);
            task.run();
          });
    }

    @Override
    public void shutdown() {
      delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> java.util.concurrent.Future<T> submit(java.util.concurrent.Callable<T> task) {
      return delegate.submit(task);
    }

    @Override
    public <T> java.util.concurrent.Future<T> submit(Runnable task, T result) {
      return delegate.submit(task, result);
    }

    @Override
    public java.util.concurrent.Future<?> submit(Runnable task) {
      return delegate.submit(task);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
        Collection<? extends java.util.concurrent.Callable<T>> tasks) throws InterruptedException {
      return delegate.invokeAll(tasks);
    }

    @Override
    public <T> List<java.util.concurrent.Future<T>> invokeAll(
        Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException {
      return delegate.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends java.util.concurrent.Callable<T>> tasks)
        throws InterruptedException, java.util.concurrent.ExecutionException {
      return delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(
        Collection<? extends java.util.concurrent.Callable<T>> tasks, long timeout, TimeUnit unit)
        throws InterruptedException,
            java.util.concurrent.ExecutionException,
            java.util.concurrent.TimeoutException {
      return delegate.invokeAny(tasks, timeout, unit);
    }
  }

  /** Ждёт разрешения на воротах; прерывание и тайм-аут превращаются в сбой. */
  private static void awaitGate(CountDownLatch gate) {
    try {
      if (!gate.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Запись не получила разрешение вовремя");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Сборщик записей журнала записи настроек: проверка предупреждения об истёкшем сроке. */
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

  @Test
  void saveSettingsWritesOnSettingsThreadAndAppliesOnUiQueue() {
    var executor = settingsExecutor();
    var writeThreads = new CopyOnWriteArrayList<Thread>();
    var store = new SettingsStore(settingsFile());
    var persistence =
        new SettingsPersistence(store, store.load(), ui, recordingThreads(executor, writeThreads));
    try {
      var saving = persistence.saveSettings(new Settings(userFile(), false));

      // Применение — только через очередь потока интерфейса: без прокачки настройки старые
      assertThat(persistence.settings()).isEqualTo(Settings.defaults());
      ui.pumpUntil(saving::isDone);

      // Запись выполнена вне потока интерфейса
      assertThat(saving).isCompleted();
      assertThat(writeThreads).hasSize(1);
      assertThat(writeThreads.get(0)).isNotEqualTo(Thread.currentThread());
      assertThat(persistence.settings()).isEqualTo(new Settings(userFile(), false));
      assertThat(new SettingsStore(settingsFile()).load().settings())
          .isEqualTo(new Settings(userFile(), false));
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void shutdownStopsSettingsExecutor() {
    var executor = settingsExecutor();
    var store = new SettingsStore(settingsFile());
    var persistence = new SettingsPersistence(store, store.load(), ui, executor);

    persistence.stopWriting();

    assertThat(executor.isShutdown()).isTrue();
  }

  @Test
  void stopWritingWaitsForUnfinishedWriteWithoutWarning() throws InterruptedException {
    var executor = settingsExecutor();
    var writeStarted = new CountDownLatch(1);
    var releaseWrite = new CountDownLatch(1);
    var store = new SettingsStore(settingsFile());
    var persistence =
        new SettingsPersistence(
            store,
            store.load(),
            ui,
            new GatedExecutorService(executor, writeStarted, releaseWrite));
    var capture = new LogCapture();
    SETTINGS_LOG.addHandler(capture);
    try {
      var saving = persistence.saveSettings(new Settings(userFile(), false));
      // Запись началась и удерживается в воротах
      assertThat(writeStarted.await(10, TimeUnit.SECONDS)).isTrue();

      var stopping =
          CompletableFuture.runAsync(() -> persistence.stopWriting(Duration.ofSeconds(1)));
      // Запись удерживается: ожидание не кончилось ни по сроку, ни по завершению
      assertThat(stopping).isNotDone();

      releaseWrite.countDown();
      assertThat(stopping).succeedsWithin(Duration.ofSeconds(10));

      // Запись успела до истечения срока: предупреждения в журнале нет
      assertThat(capture.records).isEmpty();
      ui.pumpUntil(saving::isDone);
      assertThat(new SettingsStore(settingsFile()).load().settings())
          .isEqualTo(new Settings(userFile(), false));
    } finally {
      SETTINGS_LOG.removeHandler(capture);
      releaseWrite.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void stopWritingTimesOutLogsWarningAndFileStaysIntact() throws InterruptedException {
    var executor = settingsExecutor();
    var writeStarted = new CountDownLatch(1);
    var releaseWrite = new CountDownLatch(1);
    var store = new SettingsStore(settingsFile());
    var persistence =
        new SettingsPersistence(
            store,
            store.load(),
            ui,
            new GatedExecutorService(executor, writeStarted, releaseWrite));
    try {
      var saving = persistence.saveSettings(new Settings(userFile(), false));
      assertThat(writeStarted.await(10, TimeUnit.SECONDS)).isTrue();

      var capture = new LogCapture();
      SETTINGS_LOG.addHandler(capture);
      try {
        // Возврат по сроку, не по завершению записи
        persistence.stopWriting(Duration.ofMillis(200));
      } finally {
        SETTINGS_LOG.removeHandler(capture);
      }

      // Истечение срока зафиксировано предупреждением, завершение продолжилось
      assertThat(capture.records)
          .anySatisfy(
              record -> {
                assertThat(record.getLevel()).isEqualTo(Level.WARNING);
                assertThat(record.getMessage()).contains("не завершилась").contains("продолжается");
              });

      releaseWrite.countDown();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
      ui.pumpUntil(saving::isDone);

      // Атомарная замена: файл читается и содержит новые значения целиком
      assertThat(new SettingsStore(settingsFile()).load().settings())
          .isEqualTo(new Settings(userFile(), false));
    } finally {
      releaseWrite.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void stopWritingWithNonServiceExecutorDoesNothing() {
    var store = new SettingsStore(settingsFile());
    var persistence = new SettingsPersistence(store, store.load(), ui, Runnable::run);

    assertThatCode(() -> persistence.stopWriting()).doesNotThrowAnyException();
  }

  @Test
  void settingsWriteTimeoutMatchesSpecification() {
    assertThat(SettingsPersistence.SETTINGS_WRITE_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
  }

  @Test
  void saveSettingsUpdatesMemoryAndFile() {
    var store = new SettingsStore(settingsFile());
    var persistence = new SettingsPersistence(store, store.load(), ui, settingsExecutor());

    var saving = persistence.saveSettings(new Settings(userFile(), false));
    ui.pumpUntil(saving::isDone);

    assertThat(saving).isCompleted();
    assertThat(persistence.settings()).isEqualTo(new Settings(userFile(), false));
    assertThat(new SettingsStore(settingsFile()).load().settings())
        .isEqualTo(new Settings(userFile(), false));
  }

  @Test
  void failedSaveKeepsPreviousSettings() throws IOException {
    Files.writeString(dir.resolve("blocker"), "файл на месте каталога настроек");
    var store = new SettingsStore(dir.resolve("blocker/settings.properties"));
    var persistence = new SettingsPersistence(store, store.load(), ui, settingsExecutor());

    var saving = persistence.saveSettings(new Settings(userFile(), false));
    ui.pumpUntil(saving::isDone);

    assertThat(saving).isCompletedExceptionally();
    assertThatThrownBy(saving::join)
        .isInstanceOf(CompletionException.class)
        .hasCauseInstanceOf(UncheckedIOException.class);
    assertThat(persistence.settings()).isEqualTo(Settings.defaults());
  }
}
