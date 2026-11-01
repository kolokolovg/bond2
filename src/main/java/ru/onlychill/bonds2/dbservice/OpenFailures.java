// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.persistence.PersistenceException;

/**
 * Неуспешные исходы операций сервиса БД: короткая причина для строки состояния и подробный текст
 * для диалога. Классификация ошибок {@link PersistenceException} и исключений отделена от
 * управления потоком жизненного цикла в {@link DatabaseService}.
 */
final class OpenFailures {

  /** Причина, по которой БД закрыта после отказа от миграции. */
  static final String REASON_OUTDATED = "схема устарела";

  private static final String REASON_TOO_NEW = "схема новее приложения";
  private static final String REASON_FOREIGN = "не база bonds2";
  private static final String REASON_OPEN = "ошибка открытия";
  private static final String REASON_CREATE = "ошибка создания";
  private static final String REASON_MIGRATION = "ошибка миграции";
  private static final String REASON_UNEXPECTED = "внутренняя ошибка";

  /** Причина занятости: сбой классифицирован как «БД занята», операцию можно повторить позже. */
  static final String REASON_BUSY = "БД занята";

  private static final String REASON_CORRUPT = "файл БД повреждён";
  private static final String REASON_FULL = "нет места на диске";
  private static final String REASON_FILE_UNAVAILABLE = "файл БД недоступен";

  /**
   * Коды результатов SQLite, различаемые при классификации (имена — по документации SQLite);
   * литералы вместо зависимостей от классов драйвера.
   */
  private static final int SQLITE_BUSY = 5;

  private static final int SQLITE_LOCKED = 6;
  private static final int SQLITE_IOERR = 10;
  private static final int SQLITE_CORRUPT = 11;
  private static final int SQLITE_FULL = 13;
  private static final int SQLITE_CANTOPEN = 14;

  private OpenFailures() {}

  /** Операция недопустима в текущем состоянии; текст для пользователя. */
  static String notAllowed(String operation) {
    return "Операция «" + operation + "» недопустима в текущем состоянии БД.";
  }

  /**
   * Классифицированный сбой копирования: короткая причина по коду SQLite из цепочки причин либо
   * текст исключения. БД остаётся открытой, цель не изменена.
   */
  static BackupResult.Failed backupFailed(Path target, PersistenceException e) {
    Classified byCode = bySqliteCode(e);
    String details = byCode != null ? byCode.message() : ErrorMessages.of(e);
    return new BackupResult.Failed("Не удалось создать резервную копию " + target + ". " + details);
  }

  /** Схема файла новее приложения: файл не открывается и не меняется. */
  static OpenResult.Failed tooNew(Path file) {
    return new OpenResult.Failed(
        REASON_TOO_NEW,
        "Схема БД новее этой версии приложения. Файл не изменён: "
            + file
            + ". Обновите приложение.");
  }

  /** Чужая SQLite-база: её не открывают, миграцию не предлагают, файл не меняют. */
  static OpenResult.Failed foreign(Path file) {
    return new OpenResult.Failed(
        REASON_FOREIGN, "Файл " + file + " не является базой данных bonds2. Файл не изменён.");
  }

  static OpenResult.Failed openFailed(PersistenceException e) {
    return sqliteCodeOrByPlace(REASON_OPEN, e);
  }

  static OpenResult.Failed createFailed(PersistenceException e) {
    return sqliteCodeOrByPlace(REASON_CREATE, e);
  }

  /** Ошибка миграции: транзакция откатила изменения, файл БД остался прежним. */
  static OpenResult.Failed migrationFailed(PersistenceException e) {
    OpenResult.Failed failure = sqliteCodeOrByPlace(REASON_MIGRATION, e);
    return new OpenResult.Failed(failure.reason(), failure.message() + " Файл БД не изменён.");
  }

  /**
   * Классифицирует сбой по коду результата SQLite из цепочки причин ({@link
   * ErrorMessages#sqliteResultCode}); известный код даёт отдельную причину с понятным текстом,
   * неизвестный (включая незаполненный драйвером) — прежнюю классификацию по месту возникновения.
   */
  private static OpenResult.Failed sqliteCodeOrByPlace(String placeReason, PersistenceException e) {
    Classified byCode = bySqliteCode(e);
    return byCode != null
        ? new OpenResult.Failed(byCode.reason(), byCode.message())
        : new OpenResult.Failed(placeReason, ErrorMessages.of(e));
  }

  /** Причина по коду результата SQLite; неизвестный код (в том числе 0) даёт {@code null}. */
  private static @Nullable Classified bySqliteCode(PersistenceException e) {
    return switch (ErrorMessages.sqliteResultCode(e)) {
      case SQLITE_BUSY, SQLITE_LOCKED ->
          new Classified(
              REASON_BUSY,
              "База данных занята другим процессом или соединением. Повторите операцию позже.");
      case SQLITE_CORRUPT ->
          new Classified(
              REASON_CORRUPT,
              "Файл базы данных повреждён: открыть его нельзя. Используйте резервную копию, если"
                  + " она есть.");
      case SQLITE_FULL ->
          new Classified(
              REASON_FULL,
              "На диске нет свободного места: операция не выполнена. Освободите место и повторите"
                  + " операцию.");
      case SQLITE_CANTOPEN, SQLITE_IOERR ->
          new Classified(
              REASON_FILE_UNAVAILABLE,
              "Файл базы данных недоступен: его не удалось открыть. Проверьте, что файл на месте"
                  + " и не занят другой программой.");
      default -> null;
    };
  }

  /** Короткая причина и подробный текст классифицированного сбоя. */
  private record Classified(String reason, String message) {}

  /**
   * Исключение или ошибка среды выполнения, не предусмотренные контрактом операции: поток
   * жизненного цикла продолжает работу. Фатальные ошибки виртуальной машины сюда не передаются.
   */
  static OpenResult.Failed unexpected(Throwable e) {
    return new OpenResult.Failed(
        REASON_UNEXPECTED, "Непредвиденная ошибка: " + ErrorMessages.of(e));
  }
}
