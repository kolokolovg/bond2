// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.sqlite.SQLiteConfig;

/**
 * Локальная БД SQLite: создание, проверка схемы и миграции с резервной копией, атомарные
 * транзакции, безопасные при параллельном использовании из нескольких потоков (WAL, {@code
 * busy_timeout}, {@code BEGIN IMMEDIATE} для записи).
 *
 * <p>Жизненный цикл: {@link #create(Path)}, {@link #initialize(Path)} для пустого файла или {@link
 * #open(Path)}, затем транзакции, затем {@link #close()}.
 */
public final class Database implements AutoCloseable {

  /** Таймаут ожидания блокировки БД по умолчанию. */
  public static final Duration DEFAULT_BUSY_TIMEOUT = Duration.ofSeconds(5);

  /** Состояние схемы БД относительно миграций приложения. */
  public enum State {
    /** Все миграции набора применены. */
    UP_TO_DATE,
    /** Есть неприменённые миграции набора. */
    NEEDS_MIGRATION,
    /** Применены миграции, неизвестные приложению: схема новее приложения. */
    TOO_NEW,
    /**
     * Файл пуст: 0 байт либо SQLite без пользовательских таблиц. Его инициализирует {@link
     * Database#initialize(Path)}.
     */
    EMPTY,
    /**
     * SQLite-файл с таблицами, но без истории миграций приложения: чужая база, файл не меняется.
     */
    FOREIGN
  }

  /** Набор миграций приложения. */
  private static final String MIGRATIONS = "classpath:db/migration";

  /** Таблица истории миграций Flyway: по ней БД приложения отличается от чужой SQLite-базы. */
  private static final String HISTORY_TABLE = "flyway_schema_history";

  /** Служебные файлы SQLite рядом с основным файлом БД. */
  private static final String[] SIDECAR_SUFFIXES = {"-wal", "-shm", "-journal"};

  private static final DateTimeFormatter BACKUP_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  /** Сколько резервных копий файла БД хранится после успешной миграции. */
  private static final int BACKUPS_KEPT = 3;

  private static final System.Logger LOG = System.getLogger(Database.class.getName());

  private final String url;
  private final Properties writeProperties;
  private final Properties readProperties;

  /** Транзакции держат общую блокировку, {@link #close()} берёт исключительную и ждёт их. */
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  /** Флаг закрытия; читается и пишется только под {@link #lock}. */
  private boolean closed;

  private Database(String url, Duration busyTimeout) {
    this.url = url;
    this.writeProperties = properties(busyTimeout, SQLiteConfig.TransactionMode.IMMEDIATE);
    this.readProperties = properties(busyTimeout, SQLiteConfig.TransactionMode.DEFERRED);
  }

  /** Создаёт новую БД с актуальной схемой; см. {@link #create(Path, Duration)}. */
  public static Database create(Path file) {
    return create(file, DEFAULT_BUSY_TIMEOUT);
  }

  /**
   * Создаёт новую БД по пути и применяет все миграции. Недостающие каталоги создаются. Если файл
   * уже существует, создание завершается ошибкой и файл не изменяется.
   *
   * @throws PersistenceException если файл уже существует или миграции не применились (тогда
   *     созданный файл удаляется)
   */
  public static Database create(Path file, Duration busyTimeout) {
    return create(file, busyTimeout, MIGRATIONS);
  }

  /** Создание БД из набора миграций {@code locations}. */
  static Database create(Path file, Duration busyTimeout, String... locations) {
    Path absolute = file.toAbsolutePath();
    createEmptyFile(absolute);
    String url = url(absolute);
    try {
      flyway(url, locations).migrate();
    } catch (RuntimeException e) {
      // Файл создан этим вызовом, поэтому удаляем его: повторная попытка должна быть возможна
      deleteQuietly(absolute, e);
      for (String suffix : SIDECAR_SUFFIXES) {
        deleteQuietly(withSuffix(absolute, suffix), e);
      }
      throw new PersistenceException("Не удалось создать БД: " + absolute, e);
    }
    return new Database(url, busyTimeout);
  }

  /**
   * Определяет состояние существующего файла без его изменения: {@link State#UP_TO_DATE}, {@link
   * State#NEEDS_MIGRATION}, {@link State#TOO_NEW}, {@link State#EMPTY} (0 байт или SQLite без
   * таблиц) либо {@link State#FOREIGN} (таблицы есть, истории миграций приложения нет).
   *
   * @throws PersistenceException если файла нет, он не является БД SQLite или история миграций не
   *     соответствует миграциям приложения
   */
  public static State inspect(Path file) {
    return inspect(file, MIGRATIONS);
  }

  /** Проверка состояния по набору миграций {@code locations}. */
  static State inspect(Path file, String... locations) {
    Path absolute = file.toAbsolutePath();
    if (Files.notExists(absolute)) {
      throw new PersistenceException("Файл БД не найден: " + absolute);
    }
    if (isZeroBytes(absolute)) {
      return State.EMPTY;
    }
    List<String> tables = userTables(absolute);
    if (tables.isEmpty()) {
      return State.EMPTY;
    }
    if (!tables.contains(HISTORY_TABLE)) {
      return State.FOREIGN;
    }
    Flyway flyway = flyway(url(absolute), locations);
    try {
      MigrationInfo[] migrations = flyway.info().all();
      if (Arrays.stream(migrations).anyMatch(Database::isNewer)) {
        return State.TOO_NEW;
      }
      var validation = flyway.validateWithResult();
      if (!validation.validationSuccessful) {
        throw new PersistenceException(
            "История миграций не соответствует приложению: " + validation.getAllErrorMessages());
      }
      boolean pending =
          Arrays.stream(migrations).anyMatch(m -> m.getState() == MigrationState.PENDING);
      return pending ? State.NEEDS_MIGRATION : State.UP_TO_DATE;
    } catch (FlywayException e) {
      throw new PersistenceException("Не удалось прочитать схему БД: " + absolute, e);
    }
  }

  /** Открывает БД с актуальной схемой; см. {@link #open(Path, Duration)}. */
  public static Database open(Path file) {
    return open(file, DEFAULT_BUSY_TIMEOUT);
  }

  /**
   * Открывает существующую БД, схема которой актуальна. Устаревшую схему сначала мигрирует {@link
   * #migrateWithBackup(Path)}; схему новее приложения не открывает никто. Пустой файл
   * инициализирует {@link #initialize(Path)}, чужую базу не открывает никто.
   *
   * @throws PersistenceException если файла нет, схема не актуальна, файл пуст или не является БД
   *     приложения, либо история миграций несогласована
   */
  public static Database open(Path file, Duration busyTimeout) {
    return open(file, busyTimeout, MIGRATIONS);
  }

  /** Открытие по набору миграций {@code locations}. */
  static Database open(Path file, Duration busyTimeout, String... locations) {
    Path absolute = file.toAbsolutePath();
    return switch (inspect(absolute, locations)) {
      case UP_TO_DATE -> new Database(url(absolute), busyTimeout);
      case NEEDS_MIGRATION ->
          throw new PersistenceException("Схема БД устарела, требуется миграция: " + absolute);
      case TOO_NEW -> throw new PersistenceException("Схема БД новее приложения: " + absolute);
      case EMPTY ->
          throw new PersistenceException("Файл БД пуст, схема не инициализирована: " + absolute);
      case FOREIGN ->
          throw new PersistenceException(
              "Файл не база данных приложения (нет истории миграций): " + absolute);
    };
  }

  /**
   * Инициализирует существующий пустой файл актуальной схемой: 0 байт либо SQLite без таблиц. Файл
   * не создаётся и не удаляется. Если миграции завершились ошибкой, файл возвращается к пустому
   * состоянию, поэтому повторная попытка возможна.
   *
   * @throws PersistenceException если файла нет, он не пуст (в том числе БД приложения или чужая
   *     база) либо миграции не применились
   */
  public static Database initialize(Path file) {
    return initialize(file, DEFAULT_BUSY_TIMEOUT, MIGRATIONS);
  }

  /** Инициализация по набору миграций {@code locations}. */
  static Database initialize(Path file, Duration busyTimeout, String... locations) {
    Path absolute = file.toAbsolutePath();
    if (inspect(absolute, locations) != State.EMPTY) {
      throw new PersistenceException(
          "Инициализация возможна только для пустого файла БД: " + absolute);
    }
    String url = url(absolute);
    try {
      flyway(url, locations).migrate();
    } catch (RuntimeException e) {
      // Файл существовал до вызова, поэтому его не удаляем, а возвращаем к пустому состоянию
      resetToEmpty(absolute, e);
      throw new PersistenceException("Не удалось инициализировать БД: " + absolute, e);
    }
    return new Database(url, busyTimeout);
  }

  /**
   * Миграция существующей БД с резервной копией. Перед миграцией WAL переносится в основной файл, а
   * копия {@code <имя>.bak-<yyyyMMdd-HHmmss>} создаётся рядом и проверяется {@code PRAGMA
   * integrity_check}. После успешной миграции хранятся три последние копии файла. При совпадении
   * имени добавляется числовой суффикс. Если миграция завершилась ошибкой, файл восстанавливается
   * из копии.
   *
   * @return путь к резервной копии
   * @throws PersistenceException если миграция не нужна, схема новее приложения, файл пуст или не
   *     является БД приложения; файл не изменён
   * @throws MigrationException если копию создать или проверить не удалось (миграция не выполнена,
   *     копия удаляется) либо миграция завершилась ошибкой (файл восстановлен из копии)
   */
  public static Path migrateWithBackup(Path file) {
    // Имя копии использует локальное время пользователя: часовой пояс указан явно
    return migrateWithBackup(file, Clock.system(ZoneId.systemDefault()), MIGRATIONS);
  }

  /** Миграция по набору {@code locations}; время в имени копии берётся из {@code clock}. */
  static Path migrateWithBackup(Path file, Clock clock, String... locations) {
    Path absolute = file.toAbsolutePath();
    State state = inspect(absolute, locations);
    if (state != State.NEEDS_MIGRATION) {
      throw new PersistenceException(refusal(state, absolute));
    }
    Path backup = createBackup(absolute, clock);
    verifyBackup(backup);
    try {
      flyway(url(absolute), locations).migrate();
    } catch (RuntimeException e) {
      throw restore(absolute, backup, e);
    }
    pruneBackups(absolute);
    return backup;
  }

  /**
   * Выполняет работу в транзакции записи: commit при успехе, rollback при любой ошибке.
   *
   * @throws PersistenceException если БД закрыта или транзакция не выполнена
   */
  public <T> T inTransaction(Work<T> work) {
    return run(work, writeProperties);
  }

  /**
   * Выполняет работу в читающей транзакции (снимок зафиксированных данных).
   *
   * @throws PersistenceException если БД закрыта или транзакция не выполнена
   */
  public <T> T inReadTransaction(Work<T> work) {
    return run(work, readProperties);
  }

  /**
   * Закрывает БД: ждёт завершения активных транзакций, переносит WAL в основной файл и запрещает
   * новые транзакции. Повторный вызов ничего не делает.
   *
   * @throws IllegalStateException если вызван из транзакции этой БД (иначе возник бы тупик)
   * @throws PersistenceException если WAL не удалось перенести в основной файл
   */
  @Override
  public void close() {
    if (lock.getReadHoldCount() > 0) {
      throw new IllegalStateException("БД нельзя закрыть изнутри её транзакции");
    }
    lock.writeLock().lock();
    try {
      if (!closed) {
        closed = true;
        checkpoint(url);
      }
    } catch (SQLException e) {
      throw new PersistenceException("Не удалось перенести WAL в файл БД при закрытии", e);
    } finally {
      lock.writeLock().unlock();
    }
  }

  private <T> T run(Work<T> work, Properties props) {
    lock.readLock().lock();
    try {
      if (closed) {
        throw new PersistenceException("БД закрыта");
      }
      try (Connection c = connect(url, props)) {
        c.setAutoCommit(false);
        try {
          T result = work.execute(c);
          c.commit();
          return result;
        } catch (Throwable t) {
          rollbackQuietly(c, t);
          throw t;
        }
      }
    } catch (SQLException e) {
      throw new PersistenceException("Ошибка работы с БД", e);
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Создаёт пустой файл БД и недостающие каталоги; существующий файл — ошибка. */
  private static void createEmptyFile(Path file) {
    Path parent = file.getParent();
    try {
      if (parent != null) {
        Files.createDirectories(parent);
      }
    } catch (IOException e) {
      throw new PersistenceException("Не удалось создать каталог БД: " + parent, e);
    }
    try {
      Files.createFile(file);
    } catch (FileAlreadyExistsException e) {
      throw new PersistenceException("Файл БД уже существует: " + file, e);
    } catch (IOException e) {
      throw new PersistenceException("Не удалось создать файл БД: " + file, e);
    }
  }

  /**
   * Переносит WAL в основной файл и копирует его рядом; существующую копию не перезаписывает, а при
   * совпадении имени добавляет числовой суффикс.
   */
  private static Path createBackup(Path file, Clock clock) {
    String base = ".bak-" + LocalDateTime.now(clock).format(BACKUP_STAMP);
    Path backup = withSuffix(file, base);
    try {
      checkpoint(url(file));
      for (int i = 1; ; i++) {
        try {
          Files.copy(file, backup);
          return backup;
        } catch (FileAlreadyExistsException e) {
          backup = withSuffix(file, base + "-" + i);
        }
      }
    } catch (SQLException e) {
      throw new MigrationException(
          "Не удалось перенести WAL в файл БД, миграция не выполнена: " + file, e);
    } catch (IOException e) {
      var failure =
          new MigrationException(
              "Не удалось создать резервную копию, миграция не выполнена: " + backup, e);
      deleteQuietly(backup, failure);
      throw failure;
    }
  }

  /**
   * Оставляет {@link #BACKUPS_KEPT} самых новых копий файла БД; порядок — по метке времени в имени,
   * затем по числовому суффиксу. Другие файлы не затрагиваются, сбой удаления пишется в лог.
   */
  private static void pruneBackups(Path file) {
    Path dir = file.getParent();
    var pattern =
        Pattern.compile(
            Pattern.quote(file.getFileName() + ".bak-") + "(\\d{8}-\\d{6})(?:-(\\d+))?");
    record Found(Path path, String stamp, int number) {}
    List<Found> found = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      files.forEach(
          p -> {
            Matcher m = pattern.matcher(String.valueOf(p.getFileName()));
            if (m.matches() && Files.isRegularFile(p)) {
              found.add(new Found(p, m.group(1), m.group(2) == null ? 0 : parseSuffix(m.group(2))));
            }
          });
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось просмотреть резервные копии: " + dir, e);
      return;
    }
    found.sort(Comparator.comparing(Found::stamp).thenComparingInt(Found::number).reversed());
    for (Found old : found.subList(Math.min(BACKUPS_KEPT, found.size()), found.size())) {
      try {
        Files.deleteIfExists(old.path());
      } catch (IOException e) {
        LOG.log(System.Logger.Level.WARNING, "Не удалось удалить старую копию: " + old.path(), e);
      }
    }
  }

  private static int parseSuffix(String digits) {
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException e) {
      return Integer.MAX_VALUE;
    }
  }

  /** Проверяет копию; если она повреждена, удаляет её и сообщает об ошибке. */
  private static void verifyBackup(Path backup) {
    String result;
    try {
      result = integrityCheck(url(backup));
    } catch (SQLException e) {
      throw rejectBackup(backup, e);
    }
    if (!"ok".equals(result)) {
      throw rejectBackup(backup, new SQLException("PRAGMA integrity_check: " + result));
    }
  }

  private static MigrationException rejectBackup(Path backup, SQLException cause) {
    var failure =
        new MigrationException(
            "Резервная копия не прошла проверку целостности, миграция не выполнена: " + backup,
            cause);
    deleteQuietly(backup, failure);
    return failure;
  }

  /**
   * Возвращает файл БД к состоянию резервной копии после неудачной миграции. К этому моменту
   * соединения Flyway уже закрыты; служебные файлы WAL и журнала удаляются, чтобы не применяться к
   * восстановленному файлу.
   */
  private static MigrationException restore(Path file, Path backup, RuntimeException cause) {
    try {
      for (String suffix : SIDECAR_SUFFIXES) {
        Files.deleteIfExists(withSuffix(file, suffix));
      }
      Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      cause.addSuppressed(e);
      return new MigrationException(
          "Миграция не выполнена, и восстановить БД из резервной копии не удалось. Восстановите"
              + " файл вручную из: "
              + backup,
          cause);
    }
    return new MigrationException(
        "Миграция не выполнена, БД восстановлена из резервной копии "
            + backup
            + ": "
            + cause.getMessage(),
        cause);
  }

  /** Причина отказа в миграции для состояния, кроме {@link State#NEEDS_MIGRATION}. */
  private static String refusal(State state, Path file) {
    return switch (state) {
      case TOO_NEW -> "Схема БД новее приложения, миграция невозможна: " + file;
      case EMPTY -> "Файл БД пуст, миграция невозможна: " + file;
      case FOREIGN -> "Файл не база данных приложения, миграция невозможна: " + file;
      default -> "Миграция не требуется, схема БД актуальна: " + file;
    };
  }

  /**
   * Возвращает существующий файл к пустому состоянию после неудачной инициализации: удаляет
   * служебные файлы WAL и журнала и обрезает основной файл до 0 байт. Ошибка добавляется к {@code
   * cause}.
   */
  private static void resetToEmpty(Path file, Throwable cause) {
    try {
      for (String suffix : SIDECAR_SUFFIXES) {
        Files.deleteIfExists(withSuffix(file, suffix));
      }
      Files.write(file, new byte[0]);
    } catch (IOException e) {
      cause.addSuppressed(e);
    }
  }

  private static boolean isNewer(MigrationInfo migration) {
    return migration.getState() == MigrationState.FUTURE_SUCCESS
        || migration.getState() == MigrationState.FUTURE_FAILED;
  }

  /** Переносит WAL в основной файл и обрезает его; если WAL занят другим соединением, ошибка. */
  private static void checkpoint(String url) throws SQLException {
    try (Connection c = connect(url, maintenanceProperties());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
      if (!rs.next() || rs.getInt(1) != 0) {
        throw new SQLException("WAL занят другим соединением");
      }
    }
  }

  /** Результат {@code PRAGMA integrity_check}: строка {@code ok} для целой БД. */
  private static String integrityCheck(String url) throws SQLException {
    try (Connection c = connect(url, maintenanceProperties());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
      return rs.next() ? rs.getString(1) : "";
    }
  }

  /**
   * Имена пользовательских таблиц из {@code sqlite_master}; служебные таблицы {@code sqlite_*}
   * (например, {@code sqlite_sequence}) не учитываются. Соединение не меняет файл.
   */
  private static List<String> userTables(Path file) {
    try (Connection c = connect(url(file), maintenanceProperties());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'")) {
      var names = new ArrayList<String>();
      while (rs.next()) {
        String name = rs.getString(1);
        if (!name.startsWith("sqlite_")) {
          names.add(name);
        }
      }
      return names;
    } catch (SQLException e) {
      throw new PersistenceException("Не удалось прочитать схему БД: " + file, e);
    }
  }

  /**
   * Файл размером 0 байт; такой файл SQLite читает как пустую БД, и его не открывают соединением.
   */
  private static boolean isZeroBytes(Path file) {
    try {
      return Files.size(file) == 0;
    } catch (IOException e) {
      throw new PersistenceException("Не удалось прочитать схему БД: " + file, e);
    }
  }

  private static Flyway flyway(String url, String... locations) {
    return Flyway.configure()
        .dataSource(url, null, null)
        .locations(locations)
        // Неприменённые и более новые миграции не ошибка валидации: их состояние определяет inspect
        .ignoreMigrationPatterns("*:pending", "*:future")
        .load();
  }

  private static Connection connect(String url, Properties props) throws SQLException {
    return new org.sqlite.JDBC().connect(url, props);
  }

  private static void rollbackQuietly(Connection c, Throwable cause) {
    try {
      c.rollback();
    } catch (SQLException e) {
      cause.addSuppressed(e);
    }
  }

  private static Properties properties(Duration busyTimeout, SQLiteConfig.TransactionMode mode) {
    var config = new SQLiteConfig();
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
    config.enforceForeignKeys(true);
    config.setBusyTimeout((int) busyTimeout.toMillis());
    config.setTransactionMode(mode);
    return config.toProperties();
  }

  /**
   * Настройки служебных соединений (проверки, WAL, копия): не переводят файл в WAL и не меняют его,
   * ожидание блокировки — по умолчанию.
   */
  private static Properties maintenanceProperties() {
    var config = new SQLiteConfig();
    config.setBusyTimeout((int) DEFAULT_BUSY_TIMEOUT.toMillis());
    return config.toProperties();
  }

  private static String url(Path file) {
    return "jdbc:sqlite:" + file.toAbsolutePath();
  }

  private static Path withSuffix(Path file, String suffix) {
    return file.resolveSibling(file.getFileName() + suffix);
  }

  /** Удаляет файл, если он есть; ошибка добавляется к {@code cause} как подавленная. */
  private static void deleteQuietly(Path file, Throwable cause) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      cause.addSuppressed(e);
    }
  }
}
