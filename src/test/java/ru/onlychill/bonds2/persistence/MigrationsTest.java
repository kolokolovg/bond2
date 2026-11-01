// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.NEW_FAIL;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.NEW_OK;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.OLD;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.createOldDatabaseWithRow;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.queryLong;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.queryString;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {

  /** Сбой записи истории: V2 выполняется, но таблицу истории перед вставкой удаляет сам скрипт. */
  private static final String HISTORY_FAIL = "classpath:migrations/history_fail";

  /** Дубликат версии в наборе: ошибка конфигурации. */
  private static final String DUPLICATE = "classpath:migrations/dup";

  /** Имя вне формата V<версия>__<описание>.sql: ошибка конфигурации. */
  private static final String BAD_NAME = "classpath:migrations/bad_name";

  @TempDir Path dir;

  @Test
  void successfulSetIsAppliedInOneTransactionWithHistoryRows() throws Exception {
    Path file = Files.createFile(dir.resolve("fresh.db"));

    Migrations.applyPending(file, NEW_OK);

    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(appliedVersions(file)).isEqualTo(List.of("1", "2", "3"));
    // Каждая миграция набора применена и записана в историю
    assertThat(tablesOf(file)).contains("app_info", "item", "item_note");
  }

  @Test
  void alreadyAppliedMigrationsAreNotRunAgain() throws Exception {
    Path file = dir.resolve("app.db");
    Migrations.applyPending(Files.createFile(file), NEW_OK);

    // Повторный прогон того же набора ничего не меняет: применённые версии читаются из истории
    Migrations.applyPending(file, NEW_OK);

    assertThat(appliedVersions(file)).isEqualTo(List.of("1", "2", "3"));
    assertThat(queryLong(historyConnection(file), "SELECT count(*) FROM app_info")).isZero();
  }

  @Test
  void failureInTheMiddleRollsBackWholeSetOfFreshFile() throws Exception {
    Path file = Files.createFile(dir.resolve("fresh.db"));

    // V2 применяется, V3 падает: ни одна миграция набора не остаётся, файл до-миграционный
    assertThatThrownBy(() -> Migrations.applyPending(file, NEW_FAIL))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("до-миграционным")
        .hasRootCauseInstanceOf(java.sql.SQLException.class);

    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.EMPTY);
    // Откат убрал и таблицу истории: она создавалась в той же транзакции
    assertThat(tablesOf(file)).isEmpty();
  }

  @Test
  void failureInTheMiddleKeepsExistingDatabasePreMigration() throws Exception {
    Path file = createOldDatabaseWithRow(dir.resolve("app.db"), "kept");

    assertThatThrownBy(() -> Migrations.applyPending(file, NEW_FAIL))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("до-миграционным");

    // История не содержит записей набора: применён только прежний V1
    assertThat(appliedVersions(file)).isEqualTo(List.of("1"));
    assertThat(Database.inspect(file, NEW_OK)).isEqualTo(Database.State.NEEDS_MIGRATION);
    assertThat(tablesOf(file)).containsExactly("app_info", Migrations.HISTORY_TABLE);
    assertThat(valueOf(file)).isEqualTo("kept");
  }

  @Test
  void historyWriteFailureRollsBackTheMigration() throws Exception {
    Path file = Files.createFile(dir.resolve("fresh.db"));

    // V2 выполняется, но запись истории невозможна: транзакция откатывает и V1, и V2
    assertThatThrownBy(() -> Migrations.applyPending(file, HISTORY_FAIL))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("до-миграционным")
        .hasRootCauseInstanceOf(java.sql.SQLException.class);

    assertThat(Database.inspect(file, HISTORY_FAIL)).isEqualTo(Database.State.EMPTY);
    assertThat(tablesOf(file)).isEmpty();
  }

  @Test
  void missingFileIsNotCreatedByFailedRun() {
    Path file = dir.resolve("missing.db");

    assertThatThrownBy(() -> Migrations.applyPending(file, NEW_OK))
        .isInstanceOf(PersistenceException.class);

    assertThat(file).doesNotExist();
    assertThat(file.resolveSibling(file.getFileName() + "-wal")).doesNotExist();
    assertThat(file.resolveSibling(file.getFileName() + "-journal")).doesNotExist();
  }

  @Test
  void scriptsAreSortedByVersion() {
    var scripts = Migrations.scripts(NEW_OK);

    assertThat(scripts).extracting(Migrations.Script::version).containsExactly(1L, 2L, 3L);
    assertThat(scripts.get(0).description()).isEqualTo("init");
    assertThat(scripts.get(0).fileName()).isEqualTo("V1__init.sql");
    // Скрипты обоих наборов сортируются вместе, наборы не перемешиваются внутри версии
    assertThat(Migrations.scripts(OLD)).extracting(Migrations.Script::version).containsExactly(1L);
  }

  @Test
  void duplicateVersionIsAConfigurationError() {
    assertThatThrownBy(() -> Migrations.scripts(DUPLICATE))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("повторяется версия 1");
  }

  @Test
  void fileNameOutsideTheFormatIsAConfigurationError() {
    assertThatThrownBy(() -> Migrations.scripts(BAD_NAME))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("not_a_migration.sql");
  }

  @Test
  void statementsSplitOnSemicolonsOutsideLiteralsAndComments() {
    assertThat(Migrations.statements("CREATE TABLE a (x INTEGER);\nCREATE TABLE b (y TEXT);"))
        .containsExactly("CREATE TABLE a (x INTEGER)", "CREATE TABLE b (y TEXT)");
  }

  @Test
  void statementsKeepSemicolonsInsideStringLiterals() {
    assertThat(
            Migrations.statements("INSERT INTO t VALUES ('текст; с точкой; с запятой'); SELECT 1"))
        .containsExactly("INSERT INTO t VALUES ('текст; с точкой; с запятой')", "SELECT 1");
  }

  @Test
  void statementsKeepSemicolonsInsideQuotedIdentifiersAndBrackets() {
    assertThat(Migrations.statements("SELECT \"a;b\" FROM [c;d]; SELECT 2"))
        .containsExactly("SELECT \"a;b\" FROM [c;d]", "SELECT 2");
  }

  @Test
  void statementsIgnoreSemicolonsInComments() {
    assertThat(
            Migrations.statements(
                "-- комментарий; с разделителем\nSELECT 1;\n/* блок; тоже */ SELECT 2;"))
        .containsExactly("-- комментарий; с разделителем\nSELECT 1", "/* блок; тоже */ SELECT 2");
  }

  @Test
  void statementsKeepDoubledQuotesInsideStrings() {
    assertThat(Migrations.statements("INSERT INTO t VALUES ('это ''не'' конец; строки')"))
        .containsExactly("INSERT INTO t VALUES ('это ''не'' конец; строки')");
  }

  @Test
  void statementsSkipEmptyAndBlankFragments() {
    assertThat(Migrations.statements("  ;\n\n ;; SELECT 3; ;")).containsExactly("SELECT 3");
    assertThat(Migrations.statements("")).isEmpty();
  }

  @Test
  void checksumCountsEachLineWithLineFeedAndIgnoresCarriageReturns() {
    // Перевод строки завершает каждую строку; последний не порождает пустую строку
    assertThat(Migrations.checksum("A")).isEqualTo(Migrations.checksum("A\n"));
    assertThat(Migrations.checksum("A")).isEqualTo(independentChecksum("A\n"));
    assertThat(Migrations.checksum("A")).isNotEqualTo(Migrations.checksum("A\nA"));
    // CRLF и CR нормализуются к LF: файлы, написанные в Windows, дают ту же сумму
    assertThat(Migrations.checksum("A\r\nB")).isEqualTo(Migrations.checksum("A\nB"));
    assertThat(Migrations.checksum("A\rB")).isEqualTo(independentChecksum("A\nB"));
  }

  /**
   * Независимое вычисление: CRC32 нормализованного текста, каждая строка завершается переводом
   * строки (финальный перевод строки исходника новой строки не порождает).
   */
  private static int independentChecksum(String content) {
    String normalized = content.replace("\r\n", "\n").replace('\r', '\n');
    String terminated =
        normalized.isEmpty() || normalized.endsWith("\n") ? normalized : normalized + "\n";
    var crc = new java.util.zip.CRC32();
    crc.update(terminated.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    return (int) crc.getValue();
  }

  private static String valueOf(Path file) {
    try (var db = Database.open(file, Database.DEFAULT_BUSY_TIMEOUT, OLD)) {
      return queryString(db, "SELECT value FROM app_info WHERE key = 'k'");
    }
  }

  private static Connection historyConnection(Path file) throws Exception {
    return DriverManager.getConnection("jdbc:sqlite:" + file);
  }

  private static List<String> appliedVersions(Path file) throws Exception {
    try (Connection c = historyConnection(file)) {
      return java.util.Arrays.stream(
              queryString(
                      c,
                      "SELECT group_concat(version) FROM (SELECT version FROM "
                          + Migrations.HISTORY_TABLE
                          + " WHERE success = 1 ORDER BY installed_rank)")
                  .split(","))
          .toList();
    }
  }

  private static List<String> tablesOf(Path file) throws Exception {
    try (Connection c = historyConnection(file)) {
      String names =
          queryString(
              c,
              "SELECT coalesce(group_concat(name), '') FROM (SELECT name FROM sqlite_master"
                  + " WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name)");
      return names.isEmpty()
          ? List.of()
          : java.util.Arrays.stream(names.split(",")).sorted().toList();
    }
  }
}
