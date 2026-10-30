// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import ru.onlychill.bonds2.persistence.Database.State;

/**
 * Определение состояния файла БД относительно миграций приложения. Файл не меняется: проверка
 * читает {@code sqlite_master}, заголовок SQLite и историю миграций Flyway. Здесь же настраивается
 * Flyway: для существующего файла соединения не имеют права создать файл, для {@code
 * Database.create} — имеют.
 */
final class SchemaInspector {

  /** Таблица истории миграций Flyway: по ней БД приложения отличается от чужой SQLite-базы. */
  private static final String HISTORY_TABLE = "flyway_schema_history";

  private SchemaInspector() {}

  /**
   * Flyway для существующего файла БД: соединения открываются через DataSource «только существующий
   * файл», поэтому удалённый к моменту открытия файл даёт ошибку, а пустой файл на его месте не
   * появляется. Используется при чтении схемы, применении миграций и инициализации.
   */
  static Flyway flywayExisting(Path file, String... locations) {
    return Flyway.configure()
        .dataSource(SqliteSupport.existingFileDataSource(file))
        .locations(locations)
        // Неприменённые и более новые миграции не ошибка валидации: их состояние определяет inspect
        .ignoreMigrationPatterns("*:pending", "*:future")
        .load();
  }

  /**
   * Flyway по JDBC-адресу: соединения имеют право создать файл, если его нет. Только для {@link
   * Database#create(Path)}, где создание файла БД — само назначение операции.
   */
  static Flyway flywayCreating(String url, String... locations) {
    return Flyway.configure()
        .dataSource(url, null, null)
        .locations(locations)
        // Неприменённые и более новые миграции не ошибка валидации: их состояние определяет inspect
        .ignoreMigrationPatterns("*:pending", "*:future")
        .load();
  }

  /**
   * Состояние существующего файла по набору миграций {@code locations}; см. {@link
   * Database#inspect(Path)}.
   */
  static State inspect(Path file, String... locations) {
    Path absolute = file.toAbsolutePath();
    if (Files.notExists(absolute)) {
      throw new PersistenceException("Файл БД не найден: " + absolute);
    }
    if (isZeroBytes(absolute)) {
      return State.EMPTY;
    }
    Contents contents = contents(absolute);
    if (!contents.hasHistory()) {
      return contents.blank() ? State.EMPTY : State.FOREIGN;
    }
    Flyway flyway = flywayExisting(absolute, locations);
    try {
      MigrationInfo[] migrations = flyway.info().all();
      if (Arrays.stream(migrations).anyMatch(SchemaInspector::isNewer)) {
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

  /** Признаки содержимого SQLite-файла, нужные для определения его состояния. */
  private record Contents(boolean hasHistory, boolean blank) {}

  /**
   * Читает {@code sqlite_master} и заголовочные значения. Файл пуст ({@code blank}), если в {@code
   * sqlite_master} нет ни одной записи, а {@code application_id} и {@code user_version} равны 0.
   * Соединение не меняет файл.
   */
  private static Contents contents(Path file) {
    try (Connection c =
            SqliteSupport.connect(SqliteSupport.url(file), SqliteSupport.maintenanceProperties());
        Statement st = c.createStatement()) {
      int objects;
      boolean history;
      try (ResultSet rs =
          st.executeQuery(
              "SELECT count(*), coalesce(sum(type = 'table' AND name = '"
                  + HISTORY_TABLE
                  + "'), 0) FROM sqlite_master")) {
        rs.next();
        objects = rs.getInt(1);
        history = rs.getInt(2) > 0;
      }
      boolean zeroHeader =
          pragmaInt(st, "application_id") == 0 && pragmaInt(st, "user_version") == 0;
      return new Contents(history, objects == 0 && zeroHeader);
    } catch (SQLException e) {
      throw new PersistenceException("Не удалось прочитать схему БД: " + file, e);
    }
  }

  private static int pragmaInt(Statement st, String pragma) throws SQLException {
    try (ResultSet rs = st.executeQuery("PRAGMA " + pragma)) {
      return rs.next() ? rs.getInt(1) : 0;
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

  /** Миграция новее приложения: она уже применена к файлу, но неизвестна этой сборке. */
  private static boolean isNewer(MigrationInfo migration) {
    return migration.getState() == MigrationState.FUTURE_SUCCESS
        || migration.getState() == MigrationState.FUTURE_FAILED;
  }
}
