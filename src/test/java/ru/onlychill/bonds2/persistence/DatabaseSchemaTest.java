// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.NEW_FAIL;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.NEW_OK;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.OLD;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.TOO_NEW;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.createDatabase;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.createOldDatabaseWithRow;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.exec;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.executeSql;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.queryLong;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.queryString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseSchemaTest {

  private static final Duration TIMEOUT = Database.DEFAULT_BUSY_TIMEOUT;

  @TempDir Path dir;

  @Test
  void createMakesMissingDirectoriesAndAppliesAllMigrations() {
    Path file = dir.resolve("nested/app.db");

    try (var db = Database.create(file)) {
      assertThat(file).isRegularFile();
      assertThat(queryLong(db, "SELECT count(*) FROM flyway_schema_history WHERE success = 1"))
          .isEqualTo(1);
    }
    assertThat(Database.inspect(file)).isEqualTo(Database.State.UP_TO_DATE);
  }

  @Test
  void createFailsWithExplicitErrorWhenParentIsNotDirectory() throws Exception {
    Path blocker = Files.writeString(dir.resolve("blocker"), "обычный файл вместо каталога");

    assertThatThrownBy(() -> Database.create(blocker.resolve("app.db")))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("каталог");
    assertThat(blocker).hasContent("обычный файл вместо каталога");
  }

  @Test
  void createRejectsExistingFileAndKeepsIt() throws Exception {
    Path file = dir.resolve("app.db");
    Files.writeString(file, "уже существующий файл, не база");
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.create(file))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("уже существует");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void createRemovesFileWhenMigrationFailsSoRetryIsPossible() {
    Path file = dir.resolve("app.db");

    assertThatThrownBy(() -> Database.create(file, TIMEOUT, NEW_FAIL))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("Не удалось создать БД");
    assertThat(file).doesNotExist();

    createDatabase(file, OLD);
    assertThat(Database.inspect(file, OLD)).isEqualTo(Database.State.UP_TO_DATE);
  }

  @Test
  void inspectOutdatedSchemaNeedsMigrationAndDoesNotChangeFile() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    byte[] before = Files.readAllBytes(file);

    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.NEEDS_MIGRATION);

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void inspectActualSchemaIsUpToDateAndDoesNotChangeFile() throws Exception {
    Path file = createDatabase(dir.resolve("app.db"), NEW_OK);
    byte[] before = Files.readAllBytes(file);

    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void inspectOfUsedWalDatabaseDoesNotChangeFile() throws Exception {
    Path file = createDatabase(dir.resolve("app.db"), NEW_OK);
    try (var db = Database.open(file, TIMEOUT, NEW_OK)) {
      db.inTransaction(
          c -> {
            exec(c, "INSERT INTO app_info VALUES ('k', 'v')");
            return null;
          });
    }
    byte[] before = Files.readAllBytes(file);

    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void inspectSchemaNewerThanAppIsTooNewAndDoesNotChangeFile() throws Exception {
    Path file = createDatabase(dir.resolve("app.db"), TOO_NEW);
    byte[] before = Files.readAllBytes(file);

    assertThat(Database.inspect(file, OLD)).isEqualTo(Database.State.TOO_NEW);

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void inspectZeroByteFileIsEmptyAndStaysUntouched() throws Exception {
    Path file = Files.createFile(dir.resolve("empty.db"));

    assertThat(Database.inspect(file)).isEqualTo(Database.State.EMPTY);

    assertThat(file).isEmptyFile();
  }

  @Test
  void inspectSqliteWithoutTablesIsEmptyAndStaysUntouched() throws Exception {
    Path file = dir.resolve("no-tables.db");
    executeSql(file, "CREATE TABLE tmp (x INTEGER)", "DROP TABLE tmp");
    assertThat(file).isNotEmptyFile();
    byte[] before = Files.readAllBytes(file);

    assertThat(Database.inspect(file)).isEqualTo(Database.State.EMPTY);

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void inspectSqliteWithOnlyInternalTablesIsEmpty() throws Exception {
    // Таблица с AUTOINCREMENT удалена, а служебная sqlite_sequence осталась: пользовательских
    // таблиц нет
    Path file = dir.resolve("internal.db");
    executeSql(
        file,
        "CREATE TABLE tmp (id INTEGER PRIMARY KEY AUTOINCREMENT)",
        "INSERT INTO tmp DEFAULT VALUES",
        "DROP TABLE tmp");
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      assertThat(queryLong(c, "SELECT count(*) FROM sqlite_master WHERE name = 'sqlite_sequence'"))
          .isEqualTo(1);
    }

    assertThat(Database.inspect(file)).isEqualTo(Database.State.EMPTY);
  }

  @Test
  void inspectSqliteWithTablesButNoHistoryIsForeignAndStaysUntouched() throws Exception {
    Path file = dir.resolve("foreign.db");
    executeSql(file, "CREATE TABLE notes (id INTEGER PRIMARY KEY, text TEXT)");
    byte[] before = Files.readAllBytes(file);

    assertThat(Database.inspect(file)).isEqualTo(Database.State.FOREIGN);

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void openRejectsZeroByteFileAndKeepsIt() throws Exception {
    Path file = Files.createFile(dir.resolve("empty.db"));

    assertThatThrownBy(() -> Database.open(file))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("пуст");

    assertThat(file).isEmptyFile();
  }

  @Test
  void openRejectsForeignDatabaseAndKeepsIt() throws Exception {
    Path file = dir.resolve("foreign.db");
    executeSql(file, "CREATE TABLE notes (id INTEGER PRIMARY KEY, text TEXT)");
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.open(file))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("не база данных приложения");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void inspectMissingFileFailsWithoutCreatingIt() {
    Path file = dir.resolve("missing.db");

    assertThatThrownBy(() -> Database.inspect(file))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("не найден");
    assertThat(file).doesNotExist();
  }

  @Test
  void inspectRejectsFileThatIsNotDatabaseAndDoesNotChangeIt() throws Exception {
    Path file = dir.resolve("bad.db");
    Files.writeString(file, "not a sqlite database, just garbage text content here");
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.inspect(file))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("Не удалось прочитать схему БД");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void inspectRejectsChangedMigrationChecksum() throws Exception {
    Path file = createDatabase(dir.resolve("app.db"), OLD);
    try (var db = Database.open(file, TIMEOUT, OLD)) {
      db.inTransaction(
          c -> {
            exec(c, "UPDATE flyway_schema_history SET checksum = checksum + 1");
            return null;
          });
    }

    assertThatThrownBy(() -> Database.inspect(file, OLD))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("не соответствует");
  }

  @Test
  void openUpToDateDatabaseKeepsDataAndHistory() {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "kept");

    try (var db = Database.open(file, TIMEOUT, OLD)) {
      assertThat(queryString(db, "SELECT value FROM app_info WHERE key = 'k'")).isEqualTo("kept");
      assertThat(queryLong(db, "SELECT count(*) FROM flyway_schema_history")).isEqualTo(1);
    }
  }

  @Test
  void openAppliesGivenBusyTimeout() {
    Path file = createDatabase(dir.resolve("app.db"), OLD);

    try (var db = Database.open(file, Duration.ofMillis(777), OLD)) {
      assertThat(queryString(db, "PRAGMA busy_timeout")).isEqualTo("777");
    }
  }

  @Test
  void openRejectsOutdatedSchemaAndKeepsFile() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.open(file, TIMEOUT, NEW_OK))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("устарела");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void openRejectsSchemaNewerThanAppAndKeepsFile() throws Exception {
    Path file = createDatabase(dir.resolve("app.db"), TOO_NEW);
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.open(file, TIMEOUT, OLD))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("новее");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void openMissingFileFailsWithoutCreatingIt() {
    Path file = dir.resolve("missing.db");

    assertThatThrownBy(() -> Database.open(file)).isInstanceOf(PersistenceException.class);
    assertThat(file).doesNotExist();
  }

  @Test
  void initializeAppliesCurrentSchemaToEmptyFile() throws Exception {
    Path file = Files.createFile(dir.resolve("empty.db"));

    try (var db = Database.initialize(file, TIMEOUT, NEW_OK)) {
      assertThat(appliedMigrations(db)).isEqualTo(3);
    }
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
  }

  @Test
  void initializePublicAppliesMainSchemaToEmptyFile() throws Exception {
    Path file = Files.createFile(dir.resolve("empty.db"));

    try (var db = Database.initialize(file)) {
      assertThat(appliedMigrations(db)).isEqualTo(1);
    }
    assertThat(Database.inspect(file)).isEqualTo(Database.State.UP_TO_DATE);
  }

  @Test
  void initializeSqliteWithoutTablesAppliesCurrentSchema() throws Exception {
    Path file = dir.resolve("no-tables.db");
    executeSql(file, "CREATE TABLE tmp (x INTEGER)", "DROP TABLE tmp");

    try (var db = Database.initialize(file, TIMEOUT, NEW_OK)) {
      assertThat(appliedMigrations(db)).isEqualTo(3);
    }
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
  }

  @Test
  void initializeFailureKeepsFileEmptyAndRetryIsPossible() throws Exception {
    // NEW_FAIL применяет V2 и падает на V3: частичная схема должна исчезнуть, файл не удаляется
    Path file = Files.createFile(dir.resolve("empty.db"));

    assertThatThrownBy(() -> Database.initialize(file, TIMEOUT, NEW_FAIL))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("Не удалось инициализировать БД");

    assertThat(file).isEmptyFile();
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.EMPTY);
    try (var db = Database.initialize(file, TIMEOUT, NEW_OK)) {
      assertThat(appliedMigrations(db)).isEqualTo(3);
    }
  }

  @Test
  void initializeRejectsDatabaseWithTablesAndKeepsIt() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.initialize(file, TIMEOUT, NEW_OK))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("только для пустого файла");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void initializeRejectsForeignDatabaseAndKeepsIt() throws Exception {
    Path file = dir.resolve("foreign.db");
    executeSql(file, "CREATE TABLE notes (id INTEGER PRIMARY KEY, text TEXT)");
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.initialize(file, TIMEOUT, NEW_OK))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("только для пустого файла");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
  }

  @Test
  void initializeMissingFileFailsWithoutCreatingIt() {
    Path file = dir.resolve("missing.db");

    assertThatThrownBy(() -> Database.initialize(file)).isInstanceOf(PersistenceException.class);
    assertThat(file).doesNotExist();
  }

  /** Число успешно применённых миграций по истории Flyway. */
  private static long appliedMigrations(Database db) {
    return queryLong(db, "SELECT count(*) FROM flyway_schema_history WHERE success = 1");
  }
}
