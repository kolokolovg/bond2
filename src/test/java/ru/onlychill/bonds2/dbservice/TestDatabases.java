// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;
import ru.onlychill.bonds2.persistence.Database;

/** Подготовка файлов БД для тестов сервиса и контроллера. */
public final class TestDatabases {

  private TestDatabases() {}

  /**
   * Создаёт БД с актуальной схемой и закрывает её. Одна транзакция переводит файл в WAL, как при
   * обычной работе приложения.
   */
  public static void newDatabaseAt(Path file) {
    try (var db = Database.create(file)) {
      db.inTransaction(connection -> null);
    }
  }

  /** Файл с устаревшей схемой: история миграций пуста, таблицы {@code app_info} нет. */
  public static void outdatedAt(Path file) throws SQLException {
    newDatabaseAt(file);
    executeSql(file, "DELETE FROM flyway_schema_history", "DROP TABLE app_info");
  }

  /** БД со стёртой историей: миграция падает на создании уже существующей таблицы. */
  public static void forgottenHistoryAt(Path file) throws SQLException {
    newDatabaseAt(file);
    executeSql(file, "DELETE FROM flyway_schema_history");
  }

  /** Чужая SQLite-база: таблица есть, истории миграций приложения нет. */
  public static void foreignAt(Path file) throws SQLException, IOException {
    Files.createDirectories(Objects.requireNonNull(file.getParent()));
    executeSql(file, "CREATE TABLE notes (id INTEGER PRIMARY KEY, text TEXT)");
  }

  /** БД, схема которой новее приложения. */
  public static void tooNewAt(Path file) throws SQLException {
    newDatabaseAt(file);
    executeSql(
        file,
        "INSERT INTO flyway_schema_history (installed_rank, version, description, type, script,"
            + " checksum, installed_by, installed_on, execution_time, success) VALUES (2, '99',"
            + " 'future', 'SQL', 'V99__future.sql', 1, 'test', CURRENT_TIMESTAMP, 0, 1)");
  }

  /** Выполняет SQL отдельным соединением; изменения фиксируются и попадают в файл. */
  public static void executeSql(Path file, String... sql) throws SQLException {
    try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement statement = connection.createStatement()) {
      for (String text : sql) {
        statement.execute(text);
      }
    }
  }

  /**
   * Выполняет действие, пока другое соединение держит читающую транзакцию на старом снимке: WAL
   * нельзя перенести в файл, закрытие обязано сообщить об ошибке.
   */
  public static void whileReaderLagsBehind(Path file, Runnable action) throws SQLException {
    try (Connection reader = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      reader.setAutoCommit(false);
      try (Statement statement = reader.createStatement();
          ResultSet rs = statement.executeQuery("SELECT count(*) FROM app_info")) {
        rs.next();
      }
      executeSql(file, "INSERT INTO app_info VALUES ('late', 'write')");
      action.run();
    }
  }

  /** Резервные копии рядом с файлом БД: имена содержат {@code .bak-}. */
  public static List<String> backupsIn(Path directory) throws IOException {
    try (Stream<Path> files = Files.list(directory)) {
      return files.map(Path::toString).filter(name -> name.contains(".bak-")).toList();
    }
  }

  /** Верно, если БД закрыта: новая транзакция отклоняется. */
  public static boolean isClosed(Database database) {
    try {
      database.inTransaction(connection -> null);
      return false;
    } catch (ru.onlychill.bonds2.persistence.PersistenceException e) {
      return true;
    }
  }
}
