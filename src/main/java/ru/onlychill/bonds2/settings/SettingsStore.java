// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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
 * значения заменяются значениями по умолчанию. Запись атомарна и долговечна: сначала во временный
 * файл рядом с целевым, который сбрасывается на диск, затем переименование поверх старого файла.
 */
public final class SettingsStore {

  /**
   * Результат чтения настроек.
   *
   * @param settings настройки
   * @param badCopy копия испорченного файла; {@code null}, если сброса не было или копия не создана
   * @param recovered файл был испорчен и сброшен к значениям по умолчанию
   */
  public record LoadResult(Settings settings, @Nullable Path badCopy, boolean recovered) {}

  /** Ключ пути к файлу БД; пустое значение означает путь по умолчанию. */
  static final String KEY_DATABASE_PATH = "database.path";

  /** Ключ флага автооткрытия БД при запуске. */
  static final String KEY_AUTO_OPEN = "database.autoOpen";

  private static final String HEADER =
      "Настройки bonds2. database.path: путь к файлу БД, пусто — по умолчанию. "
          + "database.autoOpen: открывать БД при запуске, true или false.";

  private static final DateTimeFormatter BAD_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  /** Префикс и суффикс временного файла записи: {@code settings-<случайное>.tmp}. */
  private static final String TEMP_PREFIX = "settings-";

  private static final String TEMP_SUFFIX = ".tmp";

  /**
   * Каталог можно сбросить на диск только в POSIX-системах: на Windows открыть каталог как канал
   * нельзя.
   */
  private static final boolean POSIX =
      FileSystems.getDefault().supportedFileAttributeViews().contains("posix");

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
   * <p>Сначала удаляются временные файлы записи, оставшиеся от прерванного сохранения. Испорченный
   * файл (нечитаем, битая Unicode-последовательность, относительный или некорректный путь, флаг не
   * {@code true}/{@code false}) копируется в {@code settings.properties.bad-<yyyyMMdd-HHmmss>} и
   * заменяется значениями по умолчанию. Файл нулевой длины тоже считается испорченным, но копия для
   * него не создаётся: в нём нет данных, которые стоило бы сохранить. Отсутствие файла и отдельных
   * ключей повреждением не считается. Сбой копирования или записи пишется в лог.
   *
   * @return настройки из файла либо {@link Settings#defaults()} с признаком сброса и путём к копии
   */
  public LoadResult load() {
    removeOrphanedTempFiles();
    if (Files.notExists(file)) {
      return new LoadResult(Settings.defaults(), null, false);
    }
    if (isEmptyFile()) {
      return resetEmpty();
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
    return new LoadResult(new Settings(path, autoOpen), null, false);
  }

  /**
   * Обычный файл нулевой длины. Если размер не читается, файл пустым не считается: чтение сообщит
   * об ошибке.
   */
  private boolean isEmptyFile() {
    try {
      return Files.isRegularFile(file) && Files.size(file) == 0;
    } catch (IOException e) {
      return false;
    }
  }

  /**
   * Сбрасывает файл нулевой длины к значениям по умолчанию без копии: в пустом файле нет данных,
   * которые стоило бы сохранить. Сбой записи пишется в лог и не мешает запуску.
   */
  private LoadResult resetEmpty() {
    Settings defaults = Settings.defaults();
    try {
      save(defaults);
    } catch (UncheckedIOException e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось восстановить настройки: " + file, e);
    }
    return new LoadResult(defaults, null, true);
  }

  /**
   * Удаляет временные файлы записи, оставшиеся от прерванного сохранения. Это безопасно: чтение
   * выполняется под блокировкой единственного экземпляра, поэтому другая запись в тот же каталог не
   * идёт. Ошибка просмотра или удаления пишется в лог и не прерывает чтение.
   */
  private void removeOrphanedTempFiles() {
    Path dir = file.toAbsolutePath().getParent();
    if (dir == null || !Files.isDirectory(dir)) {
      return;
    }
    try (DirectoryStream<Path> entries =
        Files.newDirectoryStream(dir, TEMP_PREFIX + "*" + TEMP_SUFFIX)) {
      for (Path entry : entries) {
        deleteOrphan(entry);
      }
    } catch (IOException | DirectoryIteratorException e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось просмотреть каталог настроек: " + dir, e);
    }
  }

  private static void deleteOrphan(Path entry) {
    try {
      if (Files.isRegularFile(entry)) {
        Files.deleteIfExists(entry);
      }
    } catch (IOException e) {
      LOG.log(
          System.Logger.Level.WARNING, "Не удалось удалить временный файл настроек: " + entry, e);
    }
  }

  /** Сохраняет испорченный файл рядом и записывает значения по умолчанию. */
  private LoadResult recover() {
    Settings defaults = Settings.defaults();
    Path copy = null;
    try {
      if (!Files.isRegularFile(file)) {
        throw new IOException("не обычный файл: " + file);
      }
      Path target = badCopyPath();
      Files.copy(file, target);
      copy = target;
      save(defaults);
    } catch (IOException | UncheckedIOException e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось восстановить настройки: " + file, e);
    }
    return new LoadResult(defaults, copy, true);
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

  /**
   * Пишет настройки во временный файл, сбрасывает его на диск и атомарно переименовывает поверх
   * прежнего файла. На POSIX-системах затем сбрасывается каталог, чтобы переименование пережило
   * сбой питания. Если запись не удалась, временный файл удаляется, а прежний файл не меняется.
   */
  private void writeAtomically(Properties props) {
    Path dir = Objects.requireNonNull(file.toAbsolutePath().getParent(), "нет каталога настроек");
    Path tmp = null;
    try {
      Files.createDirectories(dir);
      tmp = Files.createTempFile(dir, TEMP_PREFIX, TEMP_SUFFIX);
      writeDurably(tmp, props);
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      if (tmp != null) {
        deleteAfterFailure(tmp, e);
      }
      throw new UncheckedIOException("Не удалось сохранить настройки: " + file, e);
    }
    syncDirectory(dir);
  }

  /**
   * Записывает настройки в файл и сбрасывает его содержимое на диск до переименования. Канал
   * закрывается до переименования: открытый файл на Windows заменить нельзя.
   */
  private static void writeDurably(Path tmp, Properties props) throws IOException {
    var text = new StringWriter();
    props.store(text, HEADER);
    ByteBuffer bytes = ByteBuffer.wrap(text.toString().getBytes(StandardCharsets.UTF_8));
    try (FileChannel channel = FileChannel.open(tmp, StandardOpenOption.WRITE)) {
      while (bytes.hasRemaining()) {
        channel.write(bytes);
      }
      channel.force(true);
    }
  }

  /**
   * Сбрасывает каталог на диск, чтобы переименование пережило сбой питания. Открыть каталог как
   * канал можно только в POSIX-системах; на остальных шаг пропускается. Файл уже заменён, поэтому
   * сбой здесь пишется в лог и не считается ошибкой сохранения.
   */
  private static void syncDirectory(Path dir) {
    if (!POSIX) {
      return;
    }
    try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось сбросить каталог настроек: " + dir, e);
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
