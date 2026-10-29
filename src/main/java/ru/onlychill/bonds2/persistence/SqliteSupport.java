// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
import org.sqlite.SQLiteConfig;

/**
 * Низкоуровневые операции с SQLite: JDBC-адрес, соединения и их настройки, перенос WAL в основной
 * файл, проверка целостности и служебные файлы рядом с БД. Схему и миграции не знает.
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

  static Properties properties(Duration busyTimeout, SQLiteConfig.TransactionMode mode) {
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
  static Properties maintenanceProperties() {
    return maintenanceProperties(Database.DEFAULT_BUSY_TIMEOUT);
  }

  static Properties maintenanceProperties(Duration busyTimeout) {
    var config = new SQLiteConfig();
    config.setBusyTimeout((int) busyTimeout.toMillis());
    return config.toProperties();
  }

  /** Переносит WAL в основной файл и обрезает его; если WAL занят другим соединением, ошибка. */
  static void checkpoint(String url) throws SQLException {
    checkpoint(url, Database.DEFAULT_BUSY_TIMEOUT);
  }

  static void checkpoint(String url, Duration busyTimeout) throws SQLException {
    try (Connection c = connect(url, maintenanceProperties(busyTimeout));
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
