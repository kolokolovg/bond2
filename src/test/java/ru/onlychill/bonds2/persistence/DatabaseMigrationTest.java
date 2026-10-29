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
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.executeSql;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.queryLong;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.queryString;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseMigrationTest {

  /** Фиксированное время: имя копии предсказуемо — {@code app.db.bak-20261029-101530}. */
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-10-29T10:15:30Z"), ZoneOffset.UTC);

  private static final String BACKUP_NAME = "app.db.bak-20261029-101530";

  @TempDir Path dir;

  @Test
  void successfulMigrationKeepsBackupOfPreviousState() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    byte[] before = Files.readAllBytes(file);

    Path backup = Database.migrateWithBackup(file, CLOCK, NEW_OK);

    assertThat(backup.getFileName()).hasToString(BACKUP_NAME);
    assertThat(Files.readAllBytes(backup)).isEqualTo(before);
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(valueOf(file, NEW_OK)).isEqualTo("v");
  }

  @Test
  void failedMigrationRestoresBackupAndReportsOriginalCause() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "kept");
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.migrateWithBackup(file, CLOCK, NEW_FAIL))
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("восстановлена")
        .hasCauseInstanceOf(FlywayException.class)
        .hasRootCauseInstanceOf(SQLException.class);

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    List<Path> backups = backupsOf(file);
    assertThat(backups).hasSize(1);
    assertThat(Files.readAllBytes(backups.get(0))).isEqualTo(before);
    assertThat(Database.inspect(file, OLD)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(valueOf(file, OLD)).isEqualTo("kept");
    assertThat(tableExists(file, OLD, "item")).isFalse();
  }

  @Test
  void corruptedBackupStopsMigrationBeforeAnyChange() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "MARKER-7319");
    corruptPageHolding(file, "MARKER-7319");
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.migrateWithBackup(file, CLOCK, NEW_OK))
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("целостности");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(backupsOf(file)).isEmpty();
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.NEEDS_MIGRATION);
  }

  @Test
  void existingBackupWithSameStampGetsNumericSuffix() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    // Имя копии занято (в том числе каталогом): существующее не трогаем
    Path occupied = Files.writeString(dir.resolve(BACKUP_NAME), "other");
    Files.createDirectory(dir.resolve(BACKUP_NAME + "-1"));
    byte[] before = Files.readAllBytes(file);

    Path backup = Database.migrateWithBackup(file, CLOCK, NEW_OK);

    assertThat(backup.getFileName()).hasToString(BACKUP_NAME + "-2");
    assertThat(Files.readAllBytes(backup)).isEqualTo(before);
    assertThat(Files.readString(occupied)).isEqualTo("other");
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
  }

  @Test
  void successfulMigrationKeepsOnlyThreeNewestBackupsOfThisFile() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    for (String name :
        List.of(
            "app.db.bak-20200101-000000",
            "app.db.bak-20250101-000000",
            "app.db.bak-20250101-000000-1",
            "app.db.bak-20250101-000000-10",
            "app.db.bak-20250101-000000-2")) {
      Files.writeString(dir.resolve(name), name);
    }
    Files.writeString(dir.resolve("other.db.bak-20200101-000000"), "x");
    Files.writeString(dir.resolve("app.db.bak-old"), "x");
    Files.writeString(dir.resolve("app.db.bak-20200101-000000.txt"), "x");

    Database.migrateWithBackup(file, CLOCK, NEW_OK);

    assertThat(backupNames())
        .containsExactlyInAnyOrder(
            BACKUP_NAME,
            "app.db.bak-20250101-000000-10",
            "app.db.bak-20250101-000000-2",
            "other.db.bak-20200101-000000",
            "app.db.bak-old",
            "app.db.bak-20200101-000000.txt");
  }

  @Test
  void failedMigrationDeletesNoBackups() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    for (String name :
        List.of(
            "app.db.bak-20200101-000000",
            "app.db.bak-20210101-000000",
            "app.db.bak-20220101-000000",
            "app.db.bak-20230101-000000")) {
      Files.writeString(dir.resolve(name), name);
    }

    assertThatThrownBy(() -> Database.migrateWithBackup(file, CLOCK, NEW_FAIL))
        .isInstanceOf(MigrationException.class);

    assertThat(backupNames()).hasSize(5).contains("app.db.bak-20200101-000000", BACKUP_NAME);
  }

  @Test
  void upToDateDatabaseIsNotMigratedAndGetsNoBackup() throws Exception {
    Path file = createDatabase(dir.resolve("app.db"), NEW_OK);
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.migrateWithBackup(file, CLOCK, NEW_OK))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("не требуется");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(backupsOf(file)).isEmpty();
  }

  @Test
  void tooNewDatabaseIsNotMigratedAndGetsNoBackup() throws Exception {
    Path file = createDatabase(dir.resolve("app.db"), TOO_NEW);
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.migrateWithBackup(file, CLOCK, OLD))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("новее");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(backupsOf(file)).isEmpty();
  }

  @Test
  void migrationRefusesEmptyFileAndKeepsItEmpty() throws Exception {
    // Пустой файл не мигрируют, а инициализируют (Database.initialize): копия не нужна
    Path file = Files.createFile(dir.resolve("empty.db"));

    assertThatThrownBy(() -> Database.migrateWithBackup(file))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("пуст");

    assertThat(file).isEmptyFile();
    assertThat(backupsOf(file)).isEmpty();
  }

  @Test
  void migrationRefusesForeignDatabaseAndKeepsIt() throws Exception {
    Path file = dir.resolve("app.db");
    executeSql(file, "CREATE TABLE notes (id INTEGER PRIMARY KEY, text TEXT)");
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.migrateWithBackup(file, CLOCK, NEW_OK))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("не база данных приложения");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(backupsOf(file)).isEmpty();
  }

  private static String valueOf(Path file, String locations) {
    try (var db = Database.open(file, Database.DEFAULT_BUSY_TIMEOUT, locations)) {
      return queryString(db, "SELECT value FROM app_info WHERE key = 'k'");
    }
  }

  private static boolean tableExists(Path file, String locations, String table) {
    try (var db = Database.open(file, Database.DEFAULT_BUSY_TIMEOUT, locations)) {
      return queryLong(
              db,
              "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = '" + table + "'")
          > 0;
    }
  }

  private List<String> backupNames() throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files
          .map(p -> String.valueOf(p.getFileName()))
          .filter(n -> n.contains(".bak-"))
          .toList();
    }
  }

  private static List<Path> backupsOf(Path file) throws IOException {
    String prefix = file + ".bak-";
    try (Stream<Path> files = Files.list(file.getParent())) {
      return files.filter(p -> p.toString().startsWith(prefix)).toList();
    }
  }

  /**
   * Портит страницу таблицы с маркером: тип страницы B-дерева заменяется недопустимым значением, и
   * {@code PRAGMA integrity_check} обязан это обнаружить. Служебные таблицы Flyway не
   * затрагиваются.
   */
  private static void corruptPageHolding(Path file, String marker) throws Exception {
    byte[] bytes = Files.readAllBytes(file);
    // ISO-8859-1 сопоставляет каждому байту ровно один символ: индекс символа равен смещению в
    // файле
    int offset = new String(bytes, StandardCharsets.ISO_8859_1).indexOf(marker);
    assertThat(offset).isPositive();
    int pageSize = pageSize(file);
    int pageStart = offset / pageSize * pageSize;
    // Первая страница содержит заголовок файла, её не трогаем
    assertThat(pageStart).isPositive();
    bytes[pageStart] = 0;
    Files.write(file, bytes);
  }

  private static int pageSize(Path file) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      return Math.toIntExact(queryLong(c, "PRAGMA page_size"));
    }
  }
}
