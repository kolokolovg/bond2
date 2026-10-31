// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
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
 * останавливает исполнитель записи; незавершённая запись доходит до конца на потоке-демоне и
 * процесс не удерживает.
 */
final class SettingsPersistence {

  /** Имя потока, на котором выполняется запись настроек. */
  static final String SETTINGS_THREAD_NAME = "bonds2-settings";

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

  /** Останавливает фоновую запись настроек; незавершённая запись доходит до конца на демоне. */
  void stopWriting() {
    if (settingsExecutor instanceof ExecutorService executorService) {
      executorService.shutdown();
    }
  }
}
