// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.sibling;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqliteSupportTest {

  @TempDir Path dir;

  /** Создаёт БД; одна транзакция переводит файл в WAL, как при обычной работе приложения. */
  private Path createDatabase() {
    Path file = dir.resolve("app.db");
    try (var db = Database.create(file)) {
      db.inTransaction(c -> null);
    }
    return file;
  }

  private static String busyTimeout(Connection c) throws SQLException {
    try (var st = c.createStatement();
        var rs = st.executeQuery("PRAGMA busy_timeout")) {
      rs.next();
      return rs.getString(1);
    }
  }

  /**
   * Читатель держит снимок до записи, сделанной другим соединением: WAL нельзя перенести в основной
   * файл. Соединение читателя закрывает вызывающий.
   */
  private static Connection readerBehindWrite(Path file) throws SQLException {
    String url = SqliteSupport.url(file);
    Connection reader = SqliteSupport.connect(url, SqliteSupport.maintenanceProperties());
    reader.setAutoCommit(false);
    try (var st = reader.createStatement();
        var rs = st.executeQuery("SELECT count(*) FROM app_info")) {
      rs.next();
    }
    try (Connection writer = SqliteSupport.connect(url, SqliteSupport.maintenanceProperties());
        var st = writer.createStatement()) {
      st.executeUpdate("INSERT INTO app_info VALUES ('late', 'write')");
    }
    return reader;
  }

  @Test
  void maintenanceConnectionUsesGivenBusyTimeout() throws SQLException {
    Path file = createDatabase();

    try (Connection c =
        SqliteSupport.connect(
            SqliteSupport.url(file),
            SqliteSupport.maintenanceProperties(Duration.ofMillis(1234)))) {
      assertThat(busyTimeout(c)).isEqualTo("1234");
    }
  }

  @Test
  void maintenanceConnectionWithoutTimeoutUsesFiveSeconds() throws SQLException {
    Path file = createDatabase();

    try (Connection c =
        SqliteSupport.connect(SqliteSupport.url(file), SqliteSupport.maintenanceProperties())) {
      assertThat(busyTimeout(c)).isEqualTo("5000");
    }
  }

  @Test
  void checkpointOfMissingFileFailsWithoutCreatingIt() {
    Path file = dir.resolve("missing.db");
    String url = SqliteSupport.url(file);

    assertThatThrownBy(() -> SqliteSupport.checkpoint(url, Duration.ofMillis(100)))
        .isInstanceOf(SQLException.class);
    assertThat(file).doesNotExist();
    assertThat(sibling(file, "-wal")).doesNotExist();
  }

  @Test
  void checkpointOfExistingFileSucceeds() throws SQLException {
    Path file = createDatabase();

    SqliteSupport.checkpoint(SqliteSupport.url(file), Duration.ofMillis(100));

    assertThat(file).isRegularFile();
  }

  @Test
  void checkpointGivesUpAfterGivenBusyTimeoutWhenReaderLagsBehind() throws SQLException {
    Path file = createDatabase();
    String url = SqliteSupport.url(file);

    try (Connection reader = readerBehindWrite(file)) {
      long started = System.nanoTime();
      assertThatThrownBy(() -> SqliteSupport.checkpoint(url, Duration.ofMillis(100)))
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("WAL");
      // Ожидание по умолчанию (5 с) заняло бы заметно дольше
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }
  }
}
