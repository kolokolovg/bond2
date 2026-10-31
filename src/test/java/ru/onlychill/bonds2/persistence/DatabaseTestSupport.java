// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/** Общие помощники тестов БД: наборы миграций, SQL-утилиты и подготовка файлов. */
final class DatabaseTestSupport {

  /** Исходная версия схемы: только V1. */
  static final String OLD = "classpath:migrations/old";

  /** Новая версия: V2 и V3 применяются успешно. */
  static final String NEW_OK = "classpath:migrations/new_ok";

  /** Новая версия: V2 применяется, V3 завершается ошибкой. */
  static final String NEW_FAIL = "classpath:migrations/new_fail";

  /** Схема новее {@link #OLD}: содержит V2, которой нет в наборе {@code old}. */
  static final String TOO_NEW = "classpath:migrations/too_new";

  private DatabaseTestSupport() {}

  static void exec(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement()) {
      st.execute(sql);
    }
  }

  static long queryLong(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getLong(1);
    }
  }

  static String queryString(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      rs.next();
      return rs.getString(1);
    }
  }

  static long queryLong(Database db, String sql) {
    return db.inReadTransaction(c -> queryLong(c, sql));
  }

  static String queryString(Database db, String sql) {
    return db.inReadTransaction(c -> queryString(c, sql));
  }

  /** Создаёт БД по набору миграций и сразу закрывает её; возвращает тот же путь. */
  static Path createDatabase(Path file, String locations) {
    Database.create(file, Database.DEFAULT_BUSY_TIMEOUT, locations).close();
    return file;
  }

  /** Создаёт БД в схеме {@link #OLD} с одной строкой {@code k = value} и закрывает её. */
  static Path createOldDatabaseWithRow(Path file, String value) {
    try (var db = Database.create(file, Database.DEFAULT_BUSY_TIMEOUT, OLD)) {
      db.inTransaction(
          c -> {
            try (var st = c.prepareStatement("INSERT INTO app_info VALUES ('k', ?)")) {
              st.setString(1, value);
              st.executeUpdate();
            }
            return null;
          });
    }
    return file;
  }

  /** Имя файла рядом с основным файлом БД, например {@code app.db-wal}. */
  static Path sibling(Path file, String suffix) {
    return file.resolveSibling(file.getFileName() + suffix);
  }
}
