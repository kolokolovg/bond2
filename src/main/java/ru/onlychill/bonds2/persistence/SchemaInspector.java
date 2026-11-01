// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import ru.onlychill.bonds2.persistence.Database.State;
import ru.onlychill.bonds2.persistence.Migrations.Applied;
import ru.onlychill.bonds2.persistence.Migrations.Script;

/**
 * Определение состояния файла БД относительно миграций приложения. Файл не меняется: проверка
 * читает {@code sqlite_master}, заголовок SQLite и применённые версии из таблицы истории миграций
 * напрямую, без библиотеки миграций. Контрольные суммы применённых миграций не сверяются: решающими
 * являются версии.
 */
final class SchemaInspector {

  private SchemaInspector() {}

  /**
   * Состояние существующего файла по набору миграций {@code locations}; см. {@link
   * Database#inspect(Path)}.
   *
   * @throws PersistenceException если файла нет, он не является БД SQLite, история миграций
   *     содержит неудачную миграцию или версию, неизвестную приложению и не превосходящую последнюю
   *     известную (нарушение порядка набора)
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
    List<Applied> applied = readHistory(absolute);
    List<Script> scripts = Migrations.scripts(locations);
    return stateOf(applied, scripts);
  }

  /**
   * Состояние по применённым версиям и набору. Применённая версия, которой нет в наборе, — схема
   * новее приложения; неизвестная версия меньше последней известной — несоответствие истории
   * набору. Неудачная строка истории — несоответствие: такая БД требует внимания, а не миграции.
   */
  private static State stateOf(List<Applied> applied, List<Script> scripts) {
    Set<Long> available = new HashSet<>();
    long latest = 0;
    for (Script script : scripts) {
      available.add(script.version());
      latest = Math.max(latest, script.version());
    }
    Set<Long> appliedOk = new HashSet<>();
    for (Applied row : applied) {
      if (!row.success()) {
        throw new PersistenceException(
            "История миграций содержит неудачную миграцию версии " + row.version());
      }
      if (!available.contains(row.version())) {
        // Применённая версия вне набора: больше последней известной — схема новее приложения,
        // меньше — история нарушает порядок набора
        if (row.version() > latest || scripts.isEmpty()) {
          return State.TOO_NEW;
        }
        throw new PersistenceException(
            "История миграций не соответствует миграциям приложения: применена неизвестная версия "
                + row.version());
      }
      appliedOk.add(row.version());
    }
    return appliedOk.containsAll(available) ? State.UP_TO_DATE : State.NEEDS_MIGRATION;
  }

  /** Применённые версии из таблицы истории; соединение открывает только существующий файл. */
  private static List<Applied> readHistory(Path file) {
    try (Connection c =
        SqliteSupport.connect(SqliteSupport.url(file), SqliteSupport.maintenanceProperties())) {
      return Migrations.applied(c);
    } catch (SQLException e) {
      throw new PersistenceException("Не удалось прочитать историю миграций БД: " + file, e);
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
                  + Migrations.HISTORY_TABLE
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
}
