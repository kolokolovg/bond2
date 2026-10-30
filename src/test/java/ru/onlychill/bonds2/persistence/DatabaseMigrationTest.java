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
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.sibling;

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

    Path backup = Database.migrateWithBackup(file, CLOCK, NEW_OK);

    assertThat(backup.getFileName()).hasToString(BACKUP_NAME);
    // Копия VACUUM INTO может отличаться побайтно от исходника, поэтому сравниваем состояние данных
    assertThat(Database.inspect(backup, NEW_OK)).isEqualTo(Database.State.NEEDS_MIGRATION);
    assertThat(valueOf(backup, OLD)).isEqualTo("v");
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(valueOf(file, NEW_OK)).isEqualTo("v");
  }

  @Test
  void failedMigrationRestoresBackupAndReportsOriginalCause() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "kept");

    assertThatThrownBy(() -> Database.migrateWithBackup(file, CLOCK, NEW_FAIL))
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("восстановлена")
        .hasCauseInstanceOf(FlywayException.class)
        .hasRootCauseInstanceOf(SQLException.class);

    assertThat(backupsOf(file)).hasSize(1);
    // Совпадение логическое: схема и данные такие же, как до миграции. Побайтно файл может
    // отличаться и от исходного, и от копии, поэтому байты не сравниваются.
    assertThat(Database.inspect(file, OLD)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(tableExists(file, OLD, "item")).isFalse();
    assertThat(rowCount(file, OLD, "app_info")).isEqualTo(1);
    assertThat(valueOf(file, OLD)).isEqualTo("kept");
  }

  @Test
  void corruptedDatabaseStopsBackupCreationBeforeAnyChange() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "MARKER-7319");
    corruptPageHolding(file, "MARKER-7319");
    byte[] before = Files.readAllBytes(file);

    // VACUUM INTO не создаёт копию повреждённой БД: сбой создания копии останавливает миграцию
    assertThatThrownBy(() -> Database.migrateWithBackup(file, CLOCK, NEW_OK))
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("резервную копию")
        .hasCauseInstanceOf(SQLException.class);

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(backupsOf(file)).isEmpty();
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.NEEDS_MIGRATION);
  }

  @Test
  void corruptedCopyFailsIntegrityCheckAndIsDeleted() throws Exception {
    Path copy = createOldDatabaseWithRow(dir.resolve(BACKUP_NAME), "MARKER-5501");
    corruptPageHolding(copy, "MARKER-5501");

    assertThatThrownBy(() -> BackupManager.verifyBackup(copy))
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("целостности");
    assertThat(copy).doesNotExist();
  }

  @Test
  void existingBackupWithSameStampGetsNumericSuffix() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    // Имя копии занято (в том числе каталогом): существующее не трогаем
    Path occupied = Files.writeString(dir.resolve(BACKUP_NAME), "other");
    Path occupiedDir = Files.createDirectory(dir.resolve(BACKUP_NAME + "-1"));

    Path backup = Database.migrateWithBackup(file, CLOCK, NEW_OK);

    assertThat(backup.getFileName()).hasToString(BACKUP_NAME + "-2");
    assertThat(valueOf(backup, OLD)).isEqualTo("v");
    assertThat(Files.readString(occupied)).isEqualTo("other");
    assertThat(occupiedDir).isDirectory();
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
  }

  @Test
  void backupContainsDataThatIsStillOnlyInWalFile() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    Path wal = sibling(file, "-wal");
    // Соединение с чтением держит WAL открытым: без него последнее закрытие перенесёт WAL в файл
    try (Connection holder = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      queryLong(holder, "SELECT count(*) FROM app_info");
      executeSql(file, "UPDATE app_info SET value = 'in-wal' WHERE key = 'k'");
      assertThat(Files.readString(wal, StandardCharsets.ISO_8859_1)).contains("in-wal");
      assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).doesNotContain("in-wal");

      Path backup = Database.migrateWithBackup(file, CLOCK, NEW_OK);

      assertThat(valueOf(backup, OLD)).isEqualTo("in-wal");
    }
  }

  @Test
  void backupPathWithApostropheIsCreatedAndMigrated() throws Exception {
    Path apostropheDir = Files.createDirectory(dir.resolve("o'neil"));
    Path file = createOldDatabaseWithRow(apostropheDir.resolve("app.db"), "v");

    Path backup = Database.migrateWithBackup(file, CLOCK, NEW_OK);

    assertThat(backup).isRegularFile();
    assertThat(backup.getParent()).isEqualTo(apostropheDir);
    assertThat(valueOf(backup, OLD)).isEqualTo("v");
    assertThat(valueOf(file, NEW_OK)).isEqualTo("v");
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
  void newBackupIsKeptWhenOlderBackupsHaveLaterStamps() throws Exception {
    // Часы перевели назад: все прежние копии новее метки новой копии (20261029-101530)
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    for (String name :
        List.of(
            "app.db.bak-20261101-000000",
            "app.db.bak-20261102-000000",
            "app.db.bak-20261103-000000")) {
      Files.writeString(dir.resolve(name), name);
    }

    Path backup = Database.migrateWithBackup(file, CLOCK, NEW_OK);

    assertThat(backup.getFileName()).hasToString(BACKUP_NAME);
    assertThat(backup).isRegularFile();
    assertThat(backupNames())
        .containsExactlyInAnyOrder(
            BACKUP_NAME, "app.db.bak-20261103-000000", "app.db.bak-20261102-000000");
  }

  @Test
  void fewerThanThreeBackupsAreAllKept() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    Files.writeString(dir.resolve("app.db.bak-20200101-000000"), "старая");

    Database.migrateWithBackup(file, CLOCK, NEW_OK);

    assertThat(backupNames()).containsExactlyInAnyOrder(BACKUP_NAME, "app.db.bak-20200101-000000");
  }

  @Test
  void exactlyThreeBackupsWithNewOneAreAllKept() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    Files.writeString(dir.resolve("app.db.bak-20200101-000000"), "старая");
    Files.writeString(dir.resolve("app.db.bak-20210101-000000"), "старая");

    Database.migrateWithBackup(file, CLOCK, NEW_OK);

    assertThat(backupNames())
        .containsExactlyInAnyOrder(
            BACKUP_NAME, "app.db.bak-20200101-000000", "app.db.bak-20210101-000000");
  }

  @Test
  void copiesWithSameStampAreOrderedByNumericSuffix() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    // Одна метка у одиннадцати копий: без суффикса (номер 0), «-1»…«-10»; создаются не по порядку
    for (String suffix : List.of("", "-1", "-10", "-3", "-2", "-9", "-5", "-4", "-8", "-7", "-6")) {
      String name = "app.db.bak-20250101-000000" + suffix;
      Files.writeString(dir.resolve(name), name);
    }

    Database.migrateWithBackup(file, CLOCK, NEW_OK);

    // Сравниваются числа, а не строки: остаются «-10» и «-9», остальные удаляются
    assertThat(backupNames())
        .containsExactlyInAnyOrder(
            BACKUP_NAME, "app.db.bak-20250101-000000-10", "app.db.bak-20250101-000000-9");
  }

  @Test
  void keepsTwoNewestOfManyOlderBackups() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    // Метки создаются не по порядку, поэтому порядок каталога не совпадает с порядком меток
    for (int day : List.of(5, 1, 8, 3, 9, 2, 7, 4, 6)) {
      String name = "app.db.bak-202501" + String.format("%02d", day) + "-000000";
      Files.writeString(dir.resolve(name), name);
    }

    Database.migrateWithBackup(file, CLOCK, NEW_OK);

    assertThat(backupNames())
        .containsExactlyInAnyOrder(
            BACKUP_NAME, "app.db.bak-20250109-000000", "app.db.bak-20250108-000000");
  }

  @Test
  void foreignFilesCopiesOfOtherDatabasesAndDirectoriesAreNotPruned() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    for (String name :
        List.of(
            "app.db.bak-20200101-000000",
            "app.db.bak-20210101-000000",
            "app.db.bak-20220101-000000")) {
      Files.writeString(dir.resolve(name), name);
    }
    // Не копии этой БД: другая БД, другое расширение, другое имя и каталог с похожим именем
    Files.writeString(dir.resolve("other.db.bak-20190101-000000"), "x");
    Files.writeString(dir.resolve("app.db.bak-20190101-000000.txt"), "x");
    Files.writeString(dir.resolve("app.db.bak-old"), "x");
    Files.createDirectory(dir.resolve("app.db.bak-20190101-000000-9"));

    Database.migrateWithBackup(file, CLOCK, NEW_OK);

    // Четыре копии этой БД: самая старая удалена, все прочие имена не затронуты
    assertThat(backupNames())
        .containsExactlyInAnyOrder(
            BACKUP_NAME,
            "app.db.bak-20210101-000000",
            "app.db.bak-20220101-000000",
            "other.db.bak-20190101-000000",
            "app.db.bak-20190101-000000.txt",
            "app.db.bak-old",
            "app.db.bak-20190101-000000-9");
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

  @Test
  void rollbackRestoresBackupContentAndRemovesStaleSidecars() throws Exception {
    Path backup = createOldDatabaseWithRow(dir.resolve(BACKUP_NAME), "saved");
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "broken");
    // Журнал неудачной миграции не должен примениться к восстановленному файлу
    Path wal = Files.writeString(sibling(file, "-wal"), "stale journal");

    MigrationException failure =
        BackupManager.restore(file, backup, new IllegalStateException("миграция"));

    assertThat(failure).hasMessageContaining("восстановлена").hasMessageContaining(BACKUP_NAME);
    assertThat(Files.readAllBytes(file)).isEqualTo(Files.readAllBytes(backup));
    assertThat(wal).doesNotExist();
    assertThat(namesInDir()).containsExactlyInAnyOrder("app.db", BACKUP_NAME);
    assertThat(valueOf(file, OLD)).isEqualTo("saved");
  }

  @Test
  void failedReplacementKeepsDatabaseNamesBackupAndLeavesNoTempFile() throws Exception {
    Path backup = Files.writeString(dir.resolve(BACKUP_NAME), "копия");
    // Занятый каталогом путь БД: атомарно заменить его файлом нельзя ни на одной из систем
    Path file = Files.createDirectory(dir.resolve("app.db"));
    Files.writeString(file.resolve("keep.txt"), "не трогать");
    var cause = new IllegalStateException("миграция");

    MigrationException failure = BackupManager.restore(file, backup, cause);

    assertThat(failure).hasMessageContaining(backup.toString()).hasMessageContaining("вручную");
    // Причина сбоя замены прикладывается к исходной ошибке миграции
    assertThat(cause.getSuppressed()).singleElement().isInstanceOf(IOException.class);
    assertThat(file).isDirectory();
    assertThat(file.resolve("keep.txt")).hasContent("не трогать");
    assertThat(namesInDir()).containsExactlyInAnyOrder("app.db", BACKUP_NAME);
  }

  @Test
  void failedCopyOfBackupKeepsSidecarsAndLeavesNoTempFile() throws Exception {
    Path file = Files.writeString(dir.resolve("app.db"), "текущий файл");
    Path wal = Files.writeString(sibling(file, "-wal"), "журнал");
    Path missing = dir.resolve(BACKUP_NAME);

    MigrationException failure =
        BackupManager.restore(file, missing, new IllegalStateException("миграция"));

    assertThat(failure).hasMessageContaining(missing.toString());
    assertThat(file).hasContent("текущий файл");
    assertThat(wal).hasContent("журнал");
    assertThat(namesInDir()).containsExactlyInAnyOrder("app.db", "app.db-wal");
  }

  private List<String> namesInDir() throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.map(p -> String.valueOf(p.getFileName())).toList();
    }
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

  private static long rowCount(Path file, String locations, String table) {
    try (var db = Database.open(file, Database.DEFAULT_BUSY_TIMEOUT, locations)) {
      return queryLong(db, "SELECT count(*) FROM " + table);
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
