package ru.onlychill.bonds2.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

  private static final DateTimeFormatter BAD_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  private static final System.Logger LOG = System.getLogger(SettingsStore.class.getName());

  private final Path file;
  private final Clock clock;

  /** Хранилище настроек в указанном файле. */
  public SettingsStore(Path file) {
    this(file, Clock.system(ZoneId.systemDefault()));
  }

  /** Хранилище настроек; время в имени копии испорченного файла берётся из {@code clock}. */
  SettingsStore(Path file, Clock clock) {
    this.file = file;
    this.clock = clock;
  }

  /**
   * Читает настройки из файла.
   *
   * <p>Испорченный файл (нечитаем, битая Unicode-последовательность, относительный или некорректный
   * путь, флаг не {@code true}/{@code false}) копируется в {@code
   * settings.properties.bad-<yyyyMMdd-HHmmss>} и заменяется значениями по умолчанию. Отсутствие
   * файла и отдельных ключей повреждением не считается. Сбой копирования или записи пишется в лог.
   *
   * @return настройки из файла либо {@link Settings#defaults()}
   */
  public Settings load() {
    if (Files.notExists(file)) {
      return Settings.defaults();
    }
    var props = new Properties();
    try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      props.load(in);
    } catch (IOException | IllegalArgumentException e) {
      // IllegalArgumentException: некорректная Unicode-escape-последовательность в файле
      return recover();
    }
    String rawPath = props.getProperty(KEY_DATABASE_PATH);
    Path path = Settings.parseDatabasePath(rawPath);
    Boolean autoOpen = parseFlag(props.getProperty(KEY_AUTO_OPEN));
    boolean pathBroken = path == null && rawPath != null && !rawPath.isBlank();
    if (pathBroken || autoOpen == null) {
      return recover();
    }
    return new Settings(path, autoOpen);
  }

  /** Сохраняет испорченный файл рядом и записывает значения по умолчанию. */
  private Settings recover() {
    Settings defaults = Settings.defaults();
    try {
      if (!Files.isRegularFile(file)) {
        throw new IOException("не обычный файл: " + file);
      }
      Files.copy(file, badCopyPath());
      save(defaults);
    } catch (IOException | UncheckedIOException e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось восстановить настройки: " + file, e);
    }
    return defaults;
  }

  /** Свободное имя копии; при совпадении метки времени добавляется числовой суффикс. */
  private Path badCopyPath() {
    String base = file.getFileName() + ".bad-" + LocalDateTime.now(clock).format(BAD_STAMP);
    Path candidate = file.resolveSibling(base);
    for (int i = 1; Files.exists(candidate); i++) {
      candidate = file.resolveSibling(base + "-" + i);
    }
    return candidate;
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
   * Флаг из строки: {@code true} или {@code false} без учёта регистра. Отсутствующее значение даёт
   * значение по умолчанию, любое другое — {@code null} (файл испорчен).
   */
  private static @Nullable Boolean parseFlag(@Nullable String value) {
    if (value == null) {
      return Settings.DEFAULT_AUTO_OPEN;
    }
    String text = value.strip();
    if (text.equalsIgnoreCase("true")) {
      return true;
    }
    if (text.equalsIgnoreCase("false")) {
      return false;
    }
    return null;
  }
}
