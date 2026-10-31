// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.testutil;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** Общие SQL-помощники тестов всех пакетов. */
public final class TestSql {

  private TestSql() {}

  /** Выполняет SQL отдельным соединением; изменения фиксируются и попадают в файл. */
  public static void executeSql(Path file, String... sql) throws SQLException {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement statement = connection.createStatement()) {
      for (String text : sql) {
        statement.execute(text);
      }
    }
  }
}
