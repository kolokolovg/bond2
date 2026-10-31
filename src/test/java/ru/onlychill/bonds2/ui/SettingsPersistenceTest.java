// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsStore;

/**
 * Тесты фоновой записи настроек: {@link SettingsPersistence} собирается напрямую, без контроллера.
 * Проверяются поток записи, применение настроек через очередь потока интерфейса и сохранение
 * прежних настроек при сбое записи.
 */
class SettingsPersistenceTest {

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
