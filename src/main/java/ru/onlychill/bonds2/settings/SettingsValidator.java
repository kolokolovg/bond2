package ru.onlychill.bonds2.settings;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Проверка пути к файлу БД, введённого в окне настроек. Не зависит от UI.
 *
 * <p>Проверяется то, что известно без открытия БД: путь корректен и абсолютен, это не каталог, а
 * каталог файла существует либо может быть создан. Доступность файла на запись проверяет открытие
 * БД.
 */
public final class SettingsValidator {

  private static final String INVALID_CHARS = "Путь содержит недопустимые символы";

  private SettingsValidator() {}

  /**
   * Проверяет путь к файлу БД.
   *
   * @param text введённый текст; пустой или пробельный текст допустим и означает путь по умолчанию
   * @return сообщение об ошибке на русском языке либо пусто, если путь допустим
   */
  public static Optional<String> validateDatabasePath(String text) {
    String value = text.strip();
    if (value.isEmpty()) {
      return Optional.empty();
    }
    Path path;
    try {
      path = Path.of(value);
    } catch (InvalidPathException e) {
      return Optional.of(INVALID_CHARS);
    }
    if (value.chars().anyMatch(Character::isISOControl)) {
      return Optional.of(INVALID_CHARS);
    }
    if (!path.isAbsolute()) {
      return Optional.of("Укажите полный путь к файлу БД");
    }
    Path parent = path.getParent();
    if (parent == null) {
      // Корень файловой системы (например, / или C:\): у него нет имени файла
      return Optional.of("Укажите имя файла БД");
    }
    if (Files.isDirectory(path)) {
      return Optional.of("Указан каталог. Укажите путь к файлу БД, например bonds2.db");
    }
    return checkParentDirectory(parent);
  }

  /**
   * Каталог существует и является каталогом либо может быть создан: ближайший существующий предок
   * обязан быть каталогом (если на пути стоит файл, создать каталог нельзя).
   */
  private static Optional<String> checkParentDirectory(Path dir) {
    Path existing = dir;
    while (existing != null && !Files.exists(existing)) {
      existing = existing.getParent();
    }
    if (existing == null || !Files.isDirectory(existing)) {
      return Optional.of("Каталог «" + dir + "» недоступен и не может быть создан");
    }
    return Optional.empty();
  }
}
