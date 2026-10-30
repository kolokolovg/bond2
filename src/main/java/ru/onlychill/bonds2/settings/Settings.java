// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.settings;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Настройки приложения, связанные с БД: путь к файлу и флаг автооткрытия при запуске.
 *
 * @param databasePath путь к файлу БД или {@code null}, если используется путь по умолчанию
 * @param autoOpen открывать БД при запуске
 */
public record Settings(@Nullable Path databasePath, boolean autoOpen) {

  /** Значение флага автооткрытия по умолчанию: включено. */
  static final boolean DEFAULT_AUTO_OPEN = true;

  /** Настройки по умолчанию: путь к БД не задан, автооткрытие включено. */
  public static Settings defaults() {
    return new Settings(null, DEFAULT_AUTO_OPEN);
  }

  /**
   * Разбирает путь из текстового значения (файла настроек или поля окна).
   *
   * <p>Пустой или пробельный текст означает путь по умолчанию. Некорректный путь тоже даёт {@code
   * null}. Относительный путь тоже не принимается: он зависит от рабочего каталога процесса.
   *
   * @return абсолютный путь или {@code null}
   */
  public static @Nullable Path parseDatabasePath(@Nullable String text) {
    if (text == null || text.isBlank()) {
      return null;
    }
    try {
      Path path = Path.of(text.strip());
      return path.isAbsolute() ? path : null;
    } catch (InvalidPathException e) {
      return null;
    }
  }
}
