package ru.onlychill.bonds2.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Properties;
import org.jspecify.annotations.Nullable;

/**
 * Хранилище настроек в файле {@code settings.properties} (кодировка UTF-8).
 *
 * <p>Чтение не бросает исключений: отсутствующий, нечитаемый или повреждённый файл и некорректные
 * значения заменяются значениями по умолчанию. Запись атомарна: сначала во временный файл рядом с
 * целевым, затем переименование поверх старого файла.
 */
public final class SettingsStore {

  /** Ключ пути к файлу БД; пустое значение означает путь по умолчанию. */
  static final String KEY_DATABASE_PATH = "database.path";

  /** Ключ флага автооткрытия БД при запуске. */
  static final String KEY_AUTO_OPEN = "database.autoOpen";

  private static final String HEADER =
      "Настройки bonds2. database.path: путь к файлу БД, пусто — по умолчанию. "
          + "database.autoOpen: открывать БД при запуске, true или false.";

  private final Path file;

  /** Хранилище настроек в указанном файле. */
  public SettingsStore(Path file) {
    this.file = file;
  }

  /**
   * Читает настройки из файла.
   *
   * @return настройки из файла либо {@link Settings#defaults()}, если файл отсутствует, нечитаем
   *     или повреждён
   */
  public Settings load() {
    var props = new Properties();
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      props.load(in);
    } catch (IOException | IllegalArgumentException e) {
      // IllegalArgumentException: некорректная Unicode-escape-последовательность в файле
      return Settings.defaults();
    }
    return new Settings(
        Settings.parseDatabasePath(props.getProperty(KEY_DATABASE_PATH)),
        parseFlag(props.getProperty(KEY_AUTO_OPEN)));
  }

  /**
   * Сохраняет настройки атомарно и создаёт недостающие каталоги.
   *
   * @throws UncheckedIOException если сохранить не удалось; прежний файл при этом не меняется
   */
  public void save(Settings settings) {
    var props = new Properties();
    Path path = settings.databasePath();
    props.setProperty(KEY_DATABASE_PATH, path == null ? "" : path.toString());
    props.setProperty(KEY_AUTO_OPEN, Boolean.toString(settings.autoOpen()));
    writeAtomically(props);
  }

  private void writeAtomically(Properties props) {
    Path dir = Objects.requireNonNull(file.toAbsolutePath().getParent(), "нет каталога настроек");
    Path tmp = null;
    try {
      Files.createDirectories(dir);
      tmp = Files.createTempFile(dir, "settings-", ".tmp");
      try (Writer out = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
        props.store(out, HEADER);
      }
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      if (tmp != null) {
        deleteAfterFailure(tmp, e);
      }
      throw new UncheckedIOException("Не удалось сохранить настройки: " + file, e);
    }
  }

  /** Удаляет временный файл после неудачной записи; ошибка удаления добавляется к исходной. */
  private static void deleteAfterFailure(Path tmp, IOException cause) {
    try {
      Files.deleteIfExists(tmp);
    } catch (IOException e) {
      cause.addSuppressed(e);
    }
  }

  /**
   * Флаг из строки: {@code true} или {@code false} без учёта регистра, иначе значение по умолчанию.
   */
  private static boolean parseFlag(@Nullable String value) {
    if (value != null) {
      String text = value.strip();
      if (text.equalsIgnoreCase("true")) {
        return true;
      }
      if (text.equalsIgnoreCase("false")) {
        return false;
      }
    }
    return Settings.DEFAULT_AUTO_OPEN;
  }
}
