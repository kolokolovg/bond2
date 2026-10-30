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

  private static final String REASON_MISSING = "файл не найден";
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

  /** Создание отклонено: файл уже существует и не изменяется. */
  static OpenResult.Rejected fileExists(Path file) {
    return new OpenResult.Rejected(
        RejectionCause.FILE_EXISTS,
        "Файл уже существует: "
            + file
            + ". Выберите другое имя файла: существующий файл не изменён.");
  }

  /** Файла нет, а создавать его не просили. */
  static OpenResult.Failed missing(Path file) {
    return new OpenResult.Failed(
        REASON_MISSING,
        "Файл БД не найден: "
            + file
            + ". Чтобы создать новую базу, выберите «Файл → Создать базу…».");
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

  /** Ошибка миграции уже содержит путь к резервной копии. */
  static OpenResult.Failed migrationFailed(PersistenceException e) {
    return sqliteCodeOrByPlace(REASON_MIGRATION, e);
  }

  /**
   * Классифицирует сбой по коду результата SQLite из цепочки причин ({@link
   * ErrorMessages#sqliteResultCode}); известный код даёт отдельную причину с понятным текстом,
   * неизвестный (включая незаполненный драйвером) — прежнюю классификацию по месту возникновения.
   */
  private static OpenResult.Failed sqliteCodeOrByPlace(String placeReason, PersistenceException e) {
    OpenResult.Failed byCode = bySqliteCode(e);
    return byCode != null ? byCode : new OpenResult.Failed(placeReason, ErrorMessages.of(e));
  }

  /** Причина по коду результата SQLite; неизвестный код (в том числе 0) даёт {@code null}. */
  private static OpenResult.@Nullable Failed bySqliteCode(PersistenceException e) {
    return switch (ErrorMessages.sqliteResultCode(e)) {
      case SQLITE_BUSY, SQLITE_LOCKED ->
          new OpenResult.Failed(
              REASON_BUSY,
              "База данных занята другим процессом или соединением. Повторите операцию позже.");
      case SQLITE_CORRUPT ->
          new OpenResult.Failed(
              REASON_CORRUPT,
              "Файл базы данных повреждён: открыть его нельзя. Используйте резервную копию, если"
                  + " она есть.");
      case SQLITE_FULL ->
          new OpenResult.Failed(
              REASON_FULL,
              "На диске нет свободного места: операция не выполнена. Освободите место и повторите"
                  + " операцию.");
      case SQLITE_CANTOPEN, SQLITE_IOERR ->
          new OpenResult.Failed(
              REASON_FILE_UNAVAILABLE,
              "Файл базы данных недоступен: его не удалось открыть. Проверьте, что файл на месте"
                  + " и не занят другой программой.");
      default -> null;
    };
  }

  /**
   * Исключение или ошибка среды выполнения, не предусмотренные контрактом операции: поток
   * жизненного цикла продолжает работу. Фатальные ошибки виртуальной машины сюда не передаются.
   */
  static OpenResult.Failed unexpected(Throwable e) {
    return new OpenResult.Failed(
        REASON_UNEXPECTED, "Непредвиденная ошибка: " + ErrorMessages.of(e));
  }
}
