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
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.queryLong;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.queryString;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.sibling;
import static ru.onlychill.bonds2.testutil.TestSql.executeSql;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConfig;

class DatabaseMigrationTest {

  /** Служебная копия перед миграцией: фиксированное имя в каталоге данных. */
  private static final String PRE_MIGRATION = "bonds2.pre-migration.db";

  /** Новая версия: V2 создаёт таблицы с внешним ключом, V3 вставляет строку-сироту и падает. */
  private static final String FK_FAIL = "classpath:migrations/fk_fail";

  @TempDir Path dir;

  @Test
  void successfulMigrationKeepsPreMigrationBackupOfPreviousState() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");

    Database.migrateWithBackup(file, NEW_OK);

    Path backup = dir.resolve(PRE_MIGRATION);
    // Копия VACUUM INTO может отличаться побайтно от исходника, поэтому сравниваем состояние данных
    assertThat(Database.inspect(backup, NEW_OK)).isEqualTo(Database.State.NEEDS_MIGRATION);
    assertThat(valueOf(backup, OLD)).isEqualTo("v");
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(valueOf(file, NEW_OK)).isEqualTo("v");
    assertThat(dirContent()).containsExactlyInAnyOrder("app.db", PRE_MIGRATION);
  }

  @Test
  void preMigrationBackupIsOverwrittenWithNewPreviousState() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    // Прежняя копия другой миграции: фиксированное имя атомарно перезаписывается
    Files.writeString(dir.resolve(PRE_MIGRATION), "остаток прежней копии");
    Database.migrateWithBackup(file, NEW_OK);

    Path backup = dir.resolve(PRE_MIGRATION);
    assertThat(valueOf(backup, OLD)).isEqualTo("v");
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(dirContent()).containsExactlyInAnyOrder("app.db", PRE_MIGRATION);
  }

  @Test
  void failedMigrationLeavesFilePreMigrationAndBackupIntact() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "kept");

    assertThatThrownBy(() -> Database.migrateWithBackup(file, NEW_FAIL))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("Миграции не применены")
        .hasRootCauseInstanceOf(SQLException.class);

    // Транзакция откатила весь набор: файл до-миграционный, служебная копия сохранена
    assertThat(Database.inspect(file, OLD)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(tableExists(file, OLD, "item")).isFalse();
    assertThat(rowCount(file, OLD, "app_info")).isEqualTo(1);
    assertThat(valueOf(file, OLD)).isEqualTo("kept");
    assertThat(valueOf(dir.resolve(PRE_MIGRATION), OLD)).isEqualTo("kept");
    assertThat(dirContent()).containsExactlyInAnyOrder("app.db", PRE_MIGRATION);
  }

  @Test
  void foreignKeyViolationFailsMigrationAndFileStaysPreMigration() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "kept");

    // Внешние ключи включены и у соединений миграции: строка-сирота из V3 не применяется
    assertThatThrownBy(() -> Database.migrateWithBackup(file, FK_FAIL))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("Миграции не применены")
        .hasRootCauseInstanceOf(SQLException.class);

    assertThat(Database.inspect(file, OLD)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(tableExists(file, OLD, "parent")).isFalse();
    assertThat(tableExists(file, OLD, "child")).isFalse();
    assertThat(valueOf(file, OLD)).isEqualTo("kept");
  }

  @Test
  void migrationKeepsWalJournalModeOfExistingFile() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    // Файл переводится в WAL напрямую тем же способом, что и соединения транзакций приложения
    try (Connection c =
        SqliteSupport.connect(
            SqliteSupport.url(file),
            SqliteSupport.properties(
                Database.DEFAULT_BUSY_TIMEOUT, SQLiteConfig.TransactionMode.DEFERRED))) {
      exec(c, "UPDATE app_info SET value = 'v' WHERE key = 'k'");
    }
    assertThat(journalMode(file)).isEqualToIgnoringCase("wal");

    Database.migrateWithBackup(file, NEW_OK);

    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
    // Режим журнала файла остался прежним: соединения миграции его не задают
    assertThat(journalMode(file)).isEqualToIgnoringCase("wal");
    assertThat(valueOf(file, NEW_OK)).isEqualTo("v");
  }

  @Test
  void corruptedDatabaseStopsBackupCreationBeforeAnyChange() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "MARKER-7319");
    corruptPageHolding(file, "MARKER-7319");
    byte[] before = Files.readAllBytes(file);

    // VACUUM INTO не создаёт копию повреждённой БД: сбой создания копии останавливает миграцию
    assertThatThrownBy(() -> Database.migrateWithBackup(file, NEW_OK))
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("служебную копию")
        .hasRootCauseInstanceOf(SQLException.class);

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.NEEDS_MIGRATION);
    assertThat(dirContent()).containsExactly("app.db");
  }

  @Test
  void corruptedCopyFailsIntegrityCheck() throws Exception {
    Path copy = createOldDatabaseWithRow(dir.resolve(PRE_MIGRATION), "MARKER-5501");
    corruptPageHolding(copy, "MARKER-5501");

    // Проверка целостности копии обязана отвергнуть повреждённый файл
    assertThatThrownBy(() -> BackupManager.verifyCopy(copy)).isInstanceOf(SQLException.class);
    // Повреждённая копия остаётся на месте: решение о её судьбе за вызывающим кодом
    assertThat(copy).isRegularFile();
  }

  @Test
  void missingCopyFailsIntegrityCheckWithoutCreatingFile() {
    Path copy = dir.resolve(PRE_MIGRATION);

    // Отсутствующая копия не «проходит» проверку по заново созданному пустому файлу
    assertThatThrownBy(() -> BackupManager.verifyCopy(copy)).isInstanceOf(SQLException.class);

    assertThat(copy).doesNotExist();
  }

  @Test
  void preMigrationCopyOfRemovedFileFailsWithoutCreatingEmptyFiles() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    // Окно из спеки: файл удалён после распознавания состояния, до создания копии
    Files.delete(file);

    assertThatThrownBy(() -> BackupManager.preMigrationCopy(file))
        .isInstanceOf(MigrationException.class)
        .hasMessageContaining("служебную копию");

    // Пустой файл не появляется ни на месте БД, ни на месте копии
    assertThat(dirContent()).isEmpty();
  }

  @Test
  void migrationOfRemovedFileFailsWithoutCreatingEmptyFile() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    // Окно: файл удалён после распознавания состояния, до применения миграций. Соединение миграции
    // «только существующий файл» не создаёт пустой файл на месте удалённого
    Files.delete(file);

    assertThatThrownBy(() -> Database.migrateWithBackup(file, NEW_OK))
        .isInstanceOf(PersistenceException.class);

    assertThat(file).doesNotExist();
    assertThat(sibling(file, "-wal")).doesNotExist();
    assertThat(sibling(file, "-journal")).doesNotExist();
    assertThat(dirContent()).isEmpty();
  }

  @Test
  void preMigrationCopyContainsDataThatIsStillOnlyInWalFile() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    Path wal = sibling(file, "-wal");
    // Соединение с чтением держит WAL открытым: без него последнее закрытие перенесёт WAL в файл
    try (Connection holder = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      queryLong(holder, "SELECT count(*) FROM app_info");
      executeSql(file, "UPDATE app_info SET value = 'in-wal' WHERE key = 'k'");
      assertThat(Files.readString(wal, StandardCharsets.ISO_8859_1)).contains("in-wal");
      assertThat(Files.readString(file, StandardCharsets.ISO_8859_1)).doesNotContain("in-wal");

      Database.migrateWithBackup(file, NEW_OK);

      // VACUUM INTO читает согласованный снимок вместе с журналом WAL
      assertThat(valueOf(dir.resolve(PRE_MIGRATION), OLD)).isEqualTo("in-wal");
    }
  }

  @Test
  void migrationWorksInDirectoryWhoseNameHasApostrophe() throws Exception {
    Path apostropheDir = Files.createDirectory(dir.resolve("o'neil"));
    Path file = createOldDatabaseWithRow(apostropheDir.resolve("app.db"), "v");

    Database.migrateWithBackup(file, NEW_OK);

    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(valueOf(apostropheDir.resolve(PRE_MIGRATION), OLD)).isEqualTo("v");
    assertThat(valueOf(file, NEW_OK)).isEqualTo("v");
  }

  @Test
  void oldStampedBackupsAreNotTouchedByMigration() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    // Копии прошлых версий приложения остаются на диске без действий приложения
    Path oldBackup = Files.writeString(dir.resolve("app.db.bak-20200101-000000"), "старая копия");
    Path restore = Files.writeString(dir.resolve("app.db.abc123.restore"), "обрывок отката");

    Database.migrateWithBackup(file, NEW_OK);

    assertThat(oldBackup).hasContent("старая копия");
    assertThat(restore).hasContent("обрывок отката");
    assertThat(dirContent())
        .containsExactlyInAnyOrder(
            "app.db", PRE_MIGRATION, "app.db.bak-20200101-000000", "app.db.abc123.restore");
  }

  @Test
  void upToDateDatabaseIsNotMigratedAndGetsNoBackup() throws Exception {
    Path file = createDatabase(dir.resolve("app.db"), NEW_OK);
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.migrateWithBackup(file, NEW_OK))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("не требуется");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(dirContent()).containsExactly("app.db");
  }

  @Test
  void tooNewDatabaseIsNotMigratedAndGetsNoBackup() throws Exception {
    Path file = createDatabase(dir.resolve("app.db"), TOO_NEW);
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.migrateWithBackup(file, OLD))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("новее");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(dirContent()).containsExactly("app.db");
  }

  @Test
  void migrationRefusesEmptyFileAndKeepsItEmpty() throws Exception {
    // Пустой файл не мигрируют, а инициализируют (Database.initialize): копия не нужна
    Path file = Files.createFile(dir.resolve("empty.db"));

    assertThatThrownBy(() -> Database.migrateWithBackup(file))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("пуст");

    assertThat(file).isEmptyFile();
    assertThat(dirContent()).containsExactly("empty.db");
  }

  @Test
  void migrationRefusesForeignDatabaseAndKeepsIt() throws Exception {
    Path file = dir.resolve("app.db");
    executeSql(file, "CREATE TABLE notes (id INTEGER PRIMARY KEY, text TEXT)");
    byte[] before = Files.readAllBytes(file);

    assertThatThrownBy(() -> Database.migrateWithBackup(file, NEW_OK))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("не база данных приложения");

    assertThat(Files.readAllBytes(file)).isEqualTo(before);
    assertThat(dirContent()).containsExactly("app.db");
  }

  @Test
  void migrationRefusesFileOpenedByInstanceAndRunsAfterClose() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    var db = Database.open(file, Database.DEFAULT_BUSY_TIMEOUT, OLD);
    byte[] before = Files.readAllBytes(file);

    // Открытый экземпляр владеет файлом: миграция отказывает до любых файловых операций
    assertThatThrownBy(() -> Database.migrateWithBackup(file, NEW_OK))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("уже открыт")
        .hasMessageContaining(file.toAbsolutePath().normalize().toString());
    assertThat(Files.readAllBytes(file)).isEqualTo(before);

    // После закрытия экземпляра владение освобождено: миграция проходит по общим правилам
    db.close();
    Database.migrateWithBackup(file, NEW_OK);
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(valueOf(file, NEW_OK)).isEqualTo("v");
  }

  @Test
  void initializationRefusesFileOwnedByInstanceAndRunsAfterClose() throws Exception {
    // Владение пустым файлом без соединения: пакетный конструктор не подключается к файлу
    Path file = Files.createFile(dir.resolve("empty.db"));
    var db = new Database(file, Database.DEFAULT_BUSY_TIMEOUT);

    assertThatThrownBy(() -> Database.initialize(file, Database.DEFAULT_BUSY_TIMEOUT, NEW_OK))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("уже открыт")
        .hasMessageContaining(file.toAbsolutePath().normalize().toString());
    assertThat(file).isEmptyFile();

    // После закрытия экземпляра инициализация проходит
    db.close();
    try (var initialized = Database.initialize(file, Database.DEFAULT_BUSY_TIMEOUT, NEW_OK)) {
      assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
    }
  }

  @Test
  void creationRefusesOpenedFileBeforeCheckingItsExistence() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "v");
    var db = Database.open(file, Database.DEFAULT_BUSY_TIMEOUT, OLD);

    // Проверка реестра стоит до проверки существования файла: отказ не про «уже существует»
    assertThatThrownBy(() -> Database.create(file, Database.DEFAULT_BUSY_TIMEOUT, NEW_OK))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("уже открыт")
        .hasMessageNotContaining("уже существует");

    db.close();
  }

  private List<String> dirContent() throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.map(p -> String.valueOf(p.getFileName())).toList();
    }
  }

  private static String valueOf(Path file, String locations) {
    try (var db = Database.open(file, Database.DEFAULT_BUSY_TIMEOUT, locations)) {
      return queryString(db, "SELECT value FROM app_info WHERE key = 'k'");
    }
  }

  /**
   * Режим журнала файла, прочитанный служебным соединением: оно не задаёт режим, поэтому не
   * подменяет проверяемое значение.
   */
  private static String journalMode(Path file) throws SQLException {
    try (Connection c =
        SqliteSupport.connect(SqliteSupport.url(file), SqliteSupport.maintenanceProperties())) {
      return queryString(c, "PRAGMA journal_mode");
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

  /**
   * Портит страницу таблицы с маркером: тип страницы B-дерева заменяется недопустимым значением, и
   * {@code PRAGMA integrity_check} обязан это обнаружить. Служебные таблицы истории не
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
