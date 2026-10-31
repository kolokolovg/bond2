// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsStore;

/**
 * Фоновая запись настроек: пишет обновлённые настройки на отдельном исполнителе и применяет их в
 * потоке интерфейса после записи.
 *
 * <p>Future, возвращаемый {@link #saveSettings(Settings)}, завершается только после записи на
 * исполнителе настроек и применения их на {@code uiExecutor} (в приложении — поток JavaFX). Ошибка
 * записи завершает future исключением, прежние настройки остаются в силе. {@link #stopWriting()}
 * останавливает исполнитель записи и ждёт незавершённую запись не дольше {@link
 * #SETTINGS_WRITE_TIMEOUT}; при любом исходе файл настроек остаётся целым благодаря атомарной
 * замене.
 */
final class SettingsPersistence {

  /** Имя потока, на котором выполняется запись настроек. */
  static final String SETTINGS_THREAD_NAME = "bonds2-settings";

  /** Сколько ждать незавершённой записи настроек при остановке исполнителя. */
  static final Duration SETTINGS_WRITE_TIMEOUT = Duration.ofSeconds(5);

  private static final System.Logger LOG = System.getLogger(SettingsPersistence.class.getName());

  private final SettingsStore store;
  private final Executor uiExecutor;
  private final Executor settingsExecutor;

  private Settings settings;

  /** Файл настроек при запуске был испорчен и сброшен. */
  private final boolean recovered;

  /** Копия испорченного файла настроек; {@code null}, если её нет. */
  private final @Nullable Path badCopy;

  /**
   * @param store хранилище настроек; используется для фоновой записи обновлённых настроек
   * @param loaded результат чтения настроек, выполненного до создания компонента вне потока
   *     интерфейса
   * @param uiExecutor выполняет задачу в потоке JavaFX: применение настроек после записи
   * @param settingsExecutor выполняет запись настроек вне потока интерфейса; приложение использует
   *     однопоточный исполнитель с потоком-демоном, тесты подменяют его, чтобы проверять поток
   *     записи
   */
  SettingsPersistence(
      SettingsStore store,
      SettingsStore.LoadResult loaded,
      Executor uiExecutor,
      Executor settingsExecutor) {
    this.store = store;
    this.uiExecutor = uiExecutor;
    this.settingsExecutor = settingsExecutor;
    this.settings = loaded.settings();
    this.recovered = loaded.recovered();
    this.badCopy = loaded.badCopy();
  }

  /** Настройки, действующие сейчас (последние сохранённые). */
  Settings settings() {
    return settings;
  }

  /** Файл настроек при запуске был испорчен и сброшен. */
  boolean recovered() {
    return recovered;
  }

  /** Копия испорченного файла настроек; {@code null}, если её нет. */
  @Nullable Path badCopy() {
    return badCopy;
  }

  /**
   * Записывает настройки на фоновом исполнителе и применяет их по завершении в потоке JavaFX.
   * Ошибка записи завершает future исключением, прежние настройки остаются в силе.
   *
   * @return завершается после записи и применения настроек; вызывающая сторона (окно настроек)
   *     закрывается по его завершении
   */
  CompletableFuture<Void> saveSettings(Settings updated) {
    var saved = new CompletableFuture<Void>();
    try {
      settingsExecutor.execute(
          () -> {
            try {
              store.save(updated);
              saved.complete(null);
            } catch (RuntimeException e) {
              saved.completeExceptionally(e);
            }
          });
    } catch (RejectedExecutionException e) {
      saved.completeExceptionally(e);
    }
    // Применение настроек — только после записи и в потоке JavaFX
    return saved.thenRunAsync(() -> settings = updated, uiExecutor);
  }

  /**
   * Останавливает запись настроек и ждёт незавершённую запись не дольше {@link
   * #SETTINGS_WRITE_TIMEOUT}. Истечение срока и прерывание ожидания не останавливают завершение
   * приложения: об этом пишется предупреждение в журнал, а файл настроек остаётся целым благодаря
   * атомарной замене.
   */
  void stopWriting() {
    stopWriting(SETTINGS_WRITE_TIMEOUT);
  }

  /**
   * Останавливает запись настроек и ждёт незавершённую запись не дольше срока; по истечении срока
   * ожидание прекращается, в журнал пишется предупреждение, и завершение приложения продолжается.
   * Прерывание потока записи не используется: при {@link InterruptedException} флаг прерывания
   * восстанавливается, а ожидание считается не окончившимся (с тем же предупреждением). Файл
   * настроек при любом исходе остаётся целым благодаря атомарной замене. Исполнитель без интерфейса
   * {@link ExecutorService} (тестовые обёртки) не трогается.
   *
   * @param timeout сколько ждать незавершённую запись; отдельный параметр позволяет тестам не ждать
   *     реальные пять секунд
   */
  void stopWriting(Duration timeout) {
    if (!(settingsExecutor instanceof ExecutorService executorService)) {
      // Остановить и ждать нечего: тестовая обёртка без жизненного цикла исполнителя
      return;
    }
    executorService.shutdown();
    try {
      if (!executorService.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        LOG.log(
            System.Logger.Level.WARNING,
            "Запись настроек не завершилась за "
                + timeout.toMillis()
                + " мс, завершение"
                + " продолжается");
      }
    } catch (InterruptedException e) {
      // Прерывание потока записи не используется: восстанавливаем флаг и продолжаем завершение
      Thread.currentThread().interrupt();
      LOG.log(
          System.Logger.Level.WARNING,
          "Запись настроек не завершилась за "
              + timeout.toMillis()
              + " мс, завершение"
              + " продолжается");
    }
  }
}
