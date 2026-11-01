// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.sqlite.SQLiteConfig;

/**
 * Локальная БД SQLite: создание, проверка схемы, миграции со служебной копией, резервные копии по
 * указанию пользователя и атомарные транзакции, безопасные при параллельном использовании из
 * нескольких потоков (WAL, {@code busy_timeout}, {@code BEGIN IMMEDIATE} для записи).
 *
 * <p>Открытый экземпляр владеет файлом БД: служебные операции {@link #create(Path, Duration)},
 * {@link #initialize(Path)} и {@link #migrateWithBackup(Path)} отказывают, не касаясь файла и его
 * резервных копий, пока тот же файл открыт живым экземпляром приложения. Владение начинается при
 * создании экземпляра (все фабрики проходят через конструктор) и освобождается {@link #close()}, в
 * том числе при ошибке закрытия. Открытие нескольких экземпляров одного файла через {@link
 * #open(Path)} реестром не ограничивается.
 *
 * <p>Жизненный цикл: {@link #create(Path)}, {@link #initialize(Path)} для пустого файла или {@link
 * #open(Path)}, затем транзакции, затем {@link #close()}. Проверку схемы выполняет {@code
 * SchemaInspector}, прогон миграций одной транзакцией — {@code Migrations}, резервные копии —
 * {@code BackupManager}, низкоуровневые операции с SQLite — {@code SqliteSupport}.
 */
public final class Database implements AutoCloseable {

  private static final System.Logger LOG = System.getLogger(Database.class.getName());

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
     * Файл пуст: 0 байт либо SQLite без объектов схемы ({@code sqlite_master} пуст, {@code
     * application_id} и {@code user_version} равны 0). Его инициализирует {@link
     * Database#initialize(Path)}.
     */
    EMPTY,
    /**
     * SQLite-файл без истории миграций приложения, не являющийся пустым (есть объекты схемы или
     * ненулевые {@code application_id}/{@code user_version}): чужая база, файл не меняется.
     */
    FOREIGN
  }

  /** Набор миграций приложения. */
  private static final String MIGRATIONS = "classpath:db/migration";

  /**
   * Открытые экземпляры по нормализованному абсолютному пути файла БД — реестр владения файлом.
   * Запись создаёт конструктор, снимает {@link #close()}; через реестр служебные операции
   * отказывают работать с файлом, которым владеет открытый экземпляр.
   */
  private static final ConcurrentHashMap<Path, Database> OPEN_INSTANCES = new ConcurrentHashMap<>();

  private final String url;
  private final Properties writeProperties;
  private final Properties readProperties;

  /** Ключ экземпляра в {@link #OPEN_INSTANCES}: нормализованный абсолютный путь файла БД. */
  private final Path registryKey;

  /** Фабрика соединений транзакций; тесты подменяют её для проверки сбоев соединения. */
  private final ConnectionFactory connections;

  /** Транзакции держат общую блокировку, {@link #close()} берёт исключительную и ждёт их. */
  private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

  /** Флаг закрытия; читается и пишется только под {@link #lock}. */
  private boolean closed;

  private final Duration busyTimeout;

  /** Фабрика соединений для транзакций; по умолчанию открывает их через {@link SqliteSupport}. */
  @FunctionalInterface
  interface ConnectionFactory {

    Connection connect(String url, Properties props) throws SQLException;
  }

  /** Конструктор фабрик приложения: соединения транзакций открывает {@code SqliteSupport}. */
  Database(Path file, Duration busyTimeout) {
    this(file, busyTimeout, SqliteSupport::connect);
  }

  /** Тестовый конструктор: соединения транзакций открывает переданная фабрика. */
  Database(Path file, Duration busyTimeout, ConnectionFactory connections) {
    Path registryKey = keyOf(file);
    this.url = SqliteSupport.url(registryKey);
    this.registryKey = registryKey;
    this.busyTimeout = busyTimeout;
    this.connections = connections;
    this.writeProperties =
        SqliteSupport.properties(busyTimeout, SQLiteConfig.TransactionMode.IMMEDIATE);
    this.readProperties =
        SqliteSupport.properties(busyTimeout, SQLiteConfig.TransactionMode.DEFERRED);
    // Владение файлом фиксируется в конструкторе: через него проходят все фабрики экземпляра
    OPEN_INSTANCES.put(registryKey, this);
  }

  /** Создаёт новую БД с актуальной схемой; см. {@link #create(Path, Duration)}. */
  public static Database create(Path file) {
    return create(file, DEFAULT_BUSY_TIMEOUT);
  }

  /**
   * Создаёт новую БД по пути и применяет все миграции. Недостающие каталоги создаются. Если файл
   * уже существует, создание завершается ошибкой и файл не изменяется.
   *
   * @throws PersistenceException если файл уже открыт этим экземпляром приложения, файл уже
   *     существует или миграции не применились (тогда созданный файл удаляется)
   */
  public static Database create(Path file, Duration busyTimeout) {
    return create(file, busyTimeout, MIGRATIONS);
  }

  /** Создание БД из набора миграций {@code locations}. */
  static Database create(Path file, Duration busyTimeout, String... locations) {
    ensureNotOpened(file);
    Path absolute = file.toAbsolutePath();
    createEmptyFile(absolute);
    try {
      Migrations.applyPending(absolute, locations);
    } catch (RuntimeException e) {
      // Файл создан этим вызовом, поэтому удаляем его: повторная попытка должна быть возможна
      SqliteSupport.deleteWithSidecarsQuietly(absolute, e);
      throw new PersistenceException("Не удалось создать БД: " + absolute, e);
    }
    return new Database(absolute, busyTimeout);
  }

  /**
   * Определяет состояние существующего файла без его изменения: {@link State#UP_TO_DATE}, {@link
   * State#NEEDS_MIGRATION}, {@link State#TOO_NEW}, {@link State#EMPTY} (0 байт или SQLite без
   * объектов схемы и с нулевыми {@code application_id}/{@code user_version}) либо {@link
   * State#FOREIGN} (истории миграций приложения нет, файл не пуст).
   *
   * @throws PersistenceException если файла нет, он не является БД SQLite или история миграций не
   *     соответствует миграциям приложения
   */
  public static State inspect(Path file) {
    return inspect(file, MIGRATIONS);
  }

  /** Проверка состояния по набору миграций {@code locations}. */
  static State inspect(Path file, String... locations) {
    return SchemaInspector.inspect(file, locations);
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
    return switch (SchemaInspector.inspect(absolute, locations)) {
      case UP_TO_DATE -> new Database(absolute, busyTimeout);
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
   * Инициализирует существующий пустой файл актуальной схемой: 0 байт либо SQLite без объектов
   * схемы. Файл не создаётся и не удаляется. Если миграции завершились ошибкой, транзакция
   * откатывается и файл остаётся пустым, поэтому повторная попытка возможна.
   *
   * @throws PersistenceException если файла нет, файл уже открыт этим экземпляром приложения, он не
   *     пуст (в том числе БД приложения или чужая база) либо миграции не применились
   */
  public static Database initialize(Path file) {
    return initialize(file, DEFAULT_BUSY_TIMEOUT, MIGRATIONS);
  }

  /** Инициализация по набору миграций {@code locations}. */
  static Database initialize(Path file, Duration busyTimeout, String... locations) {
    ensureNotOpened(file);
    Path absolute = file.toAbsolutePath();
    if (SchemaInspector.inspect(absolute, locations) != State.EMPTY) {
      throw new PersistenceException(
          "Инициализация возможна только для пустого файла БД: " + absolute);
    }
    try {
      Migrations.applyPending(absolute, locations);
    } catch (RuntimeException e) {
      // Откат транзакции вернул файл к пустому состоянию; его не удаляем: он существовал до вызова
      throw new PersistenceException("Не удалось инициализировать БД: " + absolute, e);
    }
    return new Database(absolute, busyTimeout);
  }

  /**
   * Миграция существующей БД со служебной копией фиксированного имени {@code
   * bonds2.pre-migration.db} в каталоге данных: копия создаётся командой {@code VACUUM INTO},
   * которая включает данные из журнала WAL, проверяется и атомарно перезаписывается. Набор миграций
   * применяется одной транзакцией: сбой откатывает её, файл остаётся до-миграционным,
   * восстановление из копии не выполняется — она страховка от успешной, но ошибочной миграции.
   *
   * @throws PersistenceException если файл уже открыт этим экземпляром приложения, миграция не
   *     нужна, схема новее приложения, файл пуст или не является БД приложения; файл не изменён
   * @throws MigrationException если служебную копию создать или проверить не удалось (миграция не
   *     выполнена, файл БД не изменён) либо миграция завершилась ошибкой (транзакция откачена, файл
   *     до-миграционный)
   */
  public static void migrateWithBackup(Path file) {
    migrateWithBackup(file, MIGRATIONS);
  }

  /** Миграция по набору миграций {@code locations}. */
  static void migrateWithBackup(Path file, String... locations) {
    ensureNotOpened(file);
    BackupManager.migrateWithBackup(file.toAbsolutePath(), locations);
  }

  /**
   * Создаёт резервную копию открытой БД по выбранному пользователем пути: {@code VACUUM INTO} во
   * временный файл рядом с целью (он включает данные из журнала WAL), проверка целостности, сброс
   * на диск и замена цели — атомарная, а при невозможности обычным перемещением с заменой. При
   * любом сбое цель не тронута, временный файл удалён. Активные транзакции не блокируются: {@code
   * VACUUM INTO} читает согласованный снимок; закрытие БД ({@link #close()}) ждёт окончания
   * копирования.
   *
   * @throws PersistenceException если БД закрыта или копию создать, проверить или поставить на
   *     место не удалось
   */
  public void backupTo(Path target) {
    lock.readLock().lock();
    try {
      if (closed) {
        throw new PersistenceException("БД закрыта");
      }
      BackupManager.userCopy(registryKey, target);
    } finally {
      lock.readLock().unlock();
    }
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
   * новые транзакции. Повторный вызов ничего не делает. Файл БД не создаётся: если его удалили или
   * переместили после открытия, закрытие завершается ошибкой, а БД всё равно считается закрытой.
   * При любом исходе закрытия, включая ошибку переноса WAL, экземпляр освобождает владение файлом.
   *
   * @throws IllegalStateException если вызван из транзакции этой БД (иначе возник бы тупик)
   * @throws PersistenceException если WAL не удалось перенести в основной файл, в том числе если
   *     файла БД уже нет
   */
  @Override
  public void close() {
    if (lock.getReadHoldCount() > 0) {
      throw new IllegalStateException("БД нельзя закрыть изнутри её транзакции");
    }
    lock.writeLock().lock();
    try {
      try {
        if (!closed) {
          SqliteSupport.checkpoint(url, busyTimeout);
        }
      } finally {
        closed = true;
        // Владение снимается на всех исходах, включая сбой переноса WAL; двухаргументный remove
        // не заденет запись другого экземпляра, открытого по тому же файлу
        OPEN_INSTANCES.remove(registryKey, this);
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
      try {
        Connection c = connections.connect(url, props);
        try {
          c.setAutoCommit(false);
        } catch (SQLException e) {
          // Транзакция ещё не началась: откат не нужен, соединение закрывается с подавлением
          closeQuietly(c, e);
          throw e;
        }
        try {
          T result = work.execute(c);
          c.commit();
          // Фиксация успешна: сбой закрытия соединения не превращает операцию в ошибку
          try {
            c.close();
          } catch (SQLException e) {
            LOG.log(
                System.Logger.Level.WARNING,
                "Не удалось закрыть соединение после фиксации транзакции",
                e);
          }
          return result;
        } catch (Throwable t) {
          SqliteSupport.rollbackQuietly(c, t);
          closeQuietly(c, t);
          throw t;
        }
      } catch (SQLException e) {
        throw new PersistenceException("Ошибка работы с БД", e);
      }
    } finally {
      lock.readLock().unlock();
    }
  }

  /** Закрывает соединение; ошибка закрытия добавляется к {@code cause} как подавленная. */
  private static void closeQuietly(Connection c, Throwable cause) {
    try {
      c.close();
    } catch (SQLException e) {
      cause.addSuppressed(e);
    }
  }

  /** Ключ реестра открытых экземпляров: нормализованный абсолютный путь файла БД. */
  private static Path keyOf(Path file) {
    return file.toAbsolutePath().normalize();
  }

  /**
   * Отказ до любых файловых операций, если файл уже открыт живым экземпляром приложения: служебные
   * операции не выполняются над файлом, которым владеет открытый экземпляр БД.
   */
  private static void ensureNotOpened(Path file) {
    Path key = keyOf(file);
    if (OPEN_INSTANCES.containsKey(key)) {
      throw new PersistenceException("Файл уже открыт этим экземпляром приложения: " + key);
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
}
