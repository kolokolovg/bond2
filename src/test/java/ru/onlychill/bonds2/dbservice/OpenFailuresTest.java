// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import ru.onlychill.bonds2.persistence.MigrationException;
import ru.onlychill.bonds2.persistence.PersistenceException;

/**
 * Сценарии классификации сбоев по коду результата SQLite: «БД занята», «Нет места на диске»,
 * «Повреждение файла БД», «Неизвестный код результата». Исключение оборачивается в цепочку причин
 * так же, как это делает слой персистентности.
 */
class OpenFailuresTest {

  /** Сбой работы с БД с кодом результата SQLite, как его несёт драйвер. */
  private static PersistenceException dbFailure(int sqliteCode, String driverMessage) {
    return new PersistenceException(
        "Ошибка работы с БД", new SQLException(driverMessage, null, sqliteCode));
  }

  @Test
  void busyCodeIsClassifiedAsBusy() {
    var failure = OpenFailures.openFailed(dbFailure(5, "database is locked"));

    assertThat(failure.reason()).isEqualTo(OpenFailures.REASON_BUSY);
    assertThat(failure.message()).contains("занята").contains("Повторите").contains("позже");
  }

  @Test
  void lockedCodeIsClassifiedAsBusyToo() {
    var failure = OpenFailures.createFailed(dbFailure(6, "database table is locked"));

    assertThat(failure.reason()).isEqualTo(OpenFailures.REASON_BUSY);
  }

  @Test
  void fullCodeIsClassifiedAsNoDiskSpace() {
    var failure = OpenFailures.openFailed(dbFailure(13, "database or disk is full"));

    assertThat(failure.reason()).isEqualTo("нет места на диске");
    assertThat(failure.message())
        .contains("места")
        .doesNotContain("занята")
        .doesNotContain("повреждён");
  }

  @Test
  void corruptCodeIsClassifiedAsFileCorruption() {
    var failure = OpenFailures.migrationFailed(dbFailure(11, "database disk image is malformed"));

    assertThat(failure.reason()).isEqualTo("файл БД повреждён");
    assertThat(failure.message())
        .contains("повреждён")
        .doesNotContain("занята")
        .doesNotContain("места");
  }

  @Test
  void cantOpenCodeIsClassifiedAsFileUnavailable() {
    var failure = OpenFailures.openFailed(dbFailure(14, "unable to open database file"));

    assertThat(failure.reason()).isEqualTo("файл БД недоступен");
    assertThat(failure.message()).contains("недоступен");
  }

  @Test
  void ioErrCodeIsClassifiedAsFileUnavailable() {
    var failure = OpenFailures.createFailed(dbFailure(10, "disk I/O error"));

    assertThat(failure.reason()).isEqualTo("файл БД недоступен");
    assertThat(failure.message()).contains("недоступен");
  }

  @Test
  void unknownCodeKeepsPlaceClassification() {
    // Сценарий «Неизвестный код результата»: SQLITE_CONSTRAINT (19) не входит в известный набор —
    // классификация по месту возникновения, как до изменения
    var failure = OpenFailures.openFailed(dbFailure(19, "UNIQUE constraint failed"));

    assertThat(failure.reason()).isEqualTo("ошибка открытия");
    assertThat(failure.message()).isEqualTo("Ошибка работы с БД Причина: UNIQUE constraint failed");

    var creation = OpenFailures.createFailed(dbFailure(19, "UNIQUE constraint failed"));
    assertThat(creation.reason()).isEqualTo("ошибка создания");

    var migration =
        OpenFailures.migrationFailed(
            new MigrationException(
                "Миграция не выполнена", new SQLException("UNIQUE constraint failed", null, 19)));
    assertThat(migration.reason()).isEqualTo("ошибка миграции");
  }

  @Test
  void missingCodeKeepsPlaceClassification() {
    // Код 0: драйвер не заполнил код результата — путь прежний, по месту возникновения
    var failure =
        OpenFailures.openFailed(
            new PersistenceException(
                "Ошибка работы с БД", new SQLException("disk I/O error", null, 0)));

    assertThat(failure.reason()).isEqualTo("ошибка открытия");
    assertThat(failure.message()).isEqualTo("Ошибка работы с БД Причина: disk I/O error");
  }

  @Test
  void codeIsFoundDeepInCauseChain() {
    // Драйверный код завёрнут несколькими обёртками, как при миграции поверх Flyway
    var deep = new SQLException("database is locked", null, 5);
    var middle = new IllegalStateException();
    middle.initCause(deep);
    var failure =
        OpenFailures.migrationFailed(new MigrationException("Миграция не выполнена", middle));

    assertThat(failure.reason()).isEqualTo(OpenFailures.REASON_BUSY);
  }

  @Test
  void codeIsFoundInNextException() {
    // Код несёт следующее исключение SQLException, а не непосредственная причина
    var head = new SQLException("обёртка без кода");
    head.setNextException(new SQLException("database is locked", null, 5));
    var failure = OpenFailures.openFailed(new PersistenceException("Ошибка работы с БД", head));

    assertThat(failure.reason()).isEqualTo(OpenFailures.REASON_BUSY);
  }

  @Test
  void unexpectedFailuresAreNotClassifiedBySqliteCode() {
    // Классификация по коду применяется только к открытию, созданию и миграции: прочие сбои
    // остаются «внутренней ошибкой» с текстом причины, как до изменения
    var unexpected = OpenFailures.unexpected(dbFailure(5, "database is locked"));

    assertThat(unexpected.reason()).isEqualTo("внутренняя ошибка");
    assertThat(unexpected.message()).contains("database is locked");
  }

  @Test
  void placeReasonsAreUnchanged() {
    var missing = OpenFailures.missing(Path.of("data", "bonds.db"));
    assertThat(missing.reason()).isEqualTo("файл не найден");
  }

  @Test
  void fileExistsCarriesTypedCauseAndUnchangedMessage() {
    // Причина «файл существует» различима по типу; пользовательский текст остаётся прежним
    var rejected = OpenFailures.fileExists(Path.of("data", "bonds.db"));

    assertThat(rejected.cause()).isEqualTo(RejectionCause.FILE_EXISTS);
    assertThat(rejected.message())
        .contains("уже существует")
        .contains("Выберите другое имя файла")
        .contains("не изменён");
  }
}
