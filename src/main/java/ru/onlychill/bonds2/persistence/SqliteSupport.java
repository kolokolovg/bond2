// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteOpenMode;

/**
 * Низкоуровневые операции с SQLite: JDBC-адрес, соединения и их настройки, перенос WAL в основной
 * файл, копия {@code VACUUM INTO}, проверка целостности и служебные файлы рядом с БД. Схему и
 * миграции не знает.
 */
final class SqliteSupport {

  /** Служебные файлы SQLite рядом с основным файлом БД. */
  private static final String[] SIDECAR_SUFFIXES = {"-wal", "-shm", "-journal"};

  private SqliteSupport() {}

  /** JDBC-адрес файла БД с абсолютным путём. */
  static String url(Path file) {
    return "jdbc:sqlite:" + file.toAbsolutePath();
  }

  static Connection connect(String url, Properties props) throws SQLException {
    return new org.sqlite.JDBC().connect(url, props);
  }

  /**
   * Настройки соединения транзакций. Файл открывается только существующим, поэтому удалённый или
   * перемещённый файл БД даёт ошибку открытия, а пустой файл на его месте не появляется.
   * Синхронизация полная: зафиксированная транзакция переживает потерю питания и сбой операционной
   * системы (при {@code NORMAL} в режиме WAL последние фиксации могут откатиться).
   */
  static Properties properties(Duration busyTimeout, SQLiteConfig.TransactionMode mode) {
    var config = new SQLiteConfig();
    existingFileOnly(config);
    config.setJournalMode(SQLiteConfig.JournalMode.WAL);
    config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
    config.enforceForeignKeys(true);
    config.setBusyTimeout((int) busyTimeout.toMillis());
    config.setTransactionMode(mode);
    return config.toProperties();
  }

  /** Флаг создания файла снят: соединение открывает только существующий файл. */
  private static void existingFileOnly(SQLiteConfig config) {
    config.setOpenMode(SQLiteOpenMode.READWRITE);
    config.resetOpenMode(SQLiteOpenMode.CREATE);
  }

  /**
   * Общие свойства соединения «только существующий файл» без смены режима журнала: файл открывается
   * существующим, ожидание блокировки — по переданному таймауту. Синхронизация полная, внешние
   * ключи включены: миграции Flyway и служебные соединения соблюдают те же гарантии, что и
   * транзакции. Основа служебных свойств и соединений Flyway.
   */
  static Properties existingFileProperties(Duration busyTimeout) {
    var config = new SQLiteConfig();
    existingFileOnly(config);
    config.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
    config.enforceForeignKeys(true);
    config.setBusyTimeout((int) busyTimeout.toMillis());
    return config.toProperties();
  }

  /**
   * Настройки соединения переноса WAL: как у транзакций, файл открывается только существующим.
   * Отсутствующий файл БД даёт ошибку, а пустой файл на его месте не появляется.
   */
  static Properties checkpointProperties(Duration busyTimeout) {
    return existingFileProperties(busyTimeout);
  }

  /**
   * Настройки служебных соединений (проверки, копия): не переводят файл в WAL и не меняют его,
   * ожидание блокировки — по умолчанию. Файл открывается только существующим, поэтому удалённый или
   * перемещённый файл БД даёт ошибку открытия, а пустой файл на его месте не появляется.
   */
  static Properties maintenanceProperties() {
    return existingFileProperties(Database.DEFAULT_BUSY_TIMEOUT);
  }

  /**
   * DataSource соединений Flyway к существующему файлу БД: каждое {@link #getConnection()}
   * открывает новое соединение со свойствами «только существующий файл», поэтому Flyway (чтение
   * истории, миграции, инициализация) не может создать файл на месте удалённого — открытие даёт
   * ошибку.
   */
  static DataSource existingFileDataSource(Path file) {
    return new ExistingFileDataSource(
        url(file), existingFileProperties(Database.DEFAULT_BUSY_TIMEOUT));
  }

  /**
   * Минимальная реализация {@link DataSource} без пула: новое соединение SQLite открывается на
   * каждый вызов {@link #getConnection()}. Логины и таймаут входа к локальному файлу не
   * применяются.
   */
  static final class ExistingFileDataSource implements DataSource {

    private final String url;
    private final Properties properties;

    ExistingFileDataSource(String url, Properties properties) {
      this.url = url;
      this.properties = properties;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return SqliteSupport.connect(url, properties);
    }

    /** Логины SQLite не применяются: соединение открывается тем же способом, без них. */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      return getConnection();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
      if (iface.isInstance(this)) {
        return iface.cast(this);
      }
      throw new SQLException("Тип " + getClass().getName() + " не реализует " + iface.getName());
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
      return iface.isInstance(this);
    }

    /** Журналирование идёт через исключения соединений и System.Logger самого приложения. */
    @Override
    public @Nullable PrintWriter getLogWriter() {
      return null;
    }

    @Override
    public void setLogWriter(PrintWriter out) {
      // Журналирование DataSource не настраивается: ошибки открытия видны в исключениях соединений
    }

    @Override
    public void setLoginTimeout(int seconds) {
      // Таймаут входа к локальному файлу не применяется: ожидание блокировки задаёт busy_timeout
    }

    @Override
    public int getLoginTimeout() {
      return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
      throw new SQLFeatureNotSupportedException("Драйвер SQLite не поддерживает java.util.logging");
    }
  }

  /**
   * Переносит WAL в основной файл и обрезает его; если WAL занят другим соединением, ошибка. Файл
   * БД не создаётся: если его нет, соединение не открывается и бросает {@link SQLException}.
   */
  static void checkpoint(String url, Duration busyTimeout) throws SQLException {
    try (Connection c = connect(url, checkpointProperties(busyTimeout));
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
      if (!rs.next() || rs.getInt(1) != 0) {
        throw new SQLException("WAL занят другим соединением");
      }
    }
  }

  /** Результат {@code PRAGMA integrity_check}: строка {@code ok} для целой БД. */
  static String integrityCheck(String url) throws SQLException {
    try (Connection c = connect(url, maintenanceProperties());
        Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
      return rs.next() ? rs.getString(1) : "";
    }
  }

  /**
   * Пишет согласованную копию БД в файл {@code target} командой {@code VACUUM INTO}. Команда читает
   * данные вместе с журналом WAL, поэтому перенос WAL перед копией не нужен. Файл назначения должен
   * быть пустым или отсутствовать, иначе SQLite отказывает. Путь передаётся связанным параметром, а
   * не вставляется в текст SQL, поэтому кавычки и апострофы в пути не требуют экранирования.
   */
  static void vacuumInto(String url, Path target) throws SQLException {
    try (Connection c = connect(url, maintenanceProperties());
        PreparedStatement st = c.prepareStatement("VACUUM INTO ?")) {
      st.setString(1, target.toAbsolutePath().toString());
      st.execute();
    }
  }

  /** Откатывает транзакцию; ошибка отката добавляется к {@code cause} как подавленная. */
  static void rollbackQuietly(Connection c, Throwable cause) {
    try {
      c.rollback();
    } catch (SQLException e) {
      cause.addSuppressed(e);
    }
  }

  static Path withSuffix(Path file, String suffix) {
    return file.resolveSibling(file.getFileName() + suffix);
  }

  /** Удаляет файл, если он есть; ошибка добавляется к {@code cause} как подавленная. */
  static void deleteQuietly(Path file, Throwable cause) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      cause.addSuppressed(e);
    }
  }

  /** Удаляет файл БД вместе с его служебными файлами; ошибки добавляются к {@code cause}. */
  static void deleteWithSidecarsQuietly(Path file, Throwable cause) {
    deleteQuietly(file, cause);
    for (String suffix : SIDECAR_SUFFIXES) {
      deleteQuietly(withSuffix(file, suffix), cause);
    }
  }

  /** Удаляет служебные файлы WAL и журнала; первая ошибка прерывает удаление. */
  static void deleteSidecars(Path file) throws IOException {
    for (String suffix : SIDECAR_SUFFIXES) {
      Files.deleteIfExists(withSuffix(file, suffix));
    }
  }
}
