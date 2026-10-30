// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Properties;
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
   * Настройки соединения переноса WAL: как у транзакций, файл открывается только существующим.
   * Отсутствующий файл БД даёт ошибку, а пустой файл на его месте не появляется.
   */
  static Properties checkpointProperties(Duration busyTimeout) {
    var config = new SQLiteConfig();
    existingFileOnly(config);
    config.setBusyTimeout((int) busyTimeout.toMillis());
    return config.toProperties();
  }

  /**
   * Настройки служебных соединений (проверки, копия): не переводят файл в WAL и не меняют его,
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
