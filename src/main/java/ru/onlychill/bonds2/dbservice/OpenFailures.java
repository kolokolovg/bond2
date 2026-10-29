// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.nio.file.Path;
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

  private OpenFailures() {}

  /** Операция недопустима в текущем состоянии; текст для пользователя. */
  static String notAllowed(String operation) {
    return "Операция «" + operation + "» недопустима в текущем состоянии БД.";
  }

  /** Создание отклонено: файл уже существует и не изменяется. */
  static OpenResult.Rejected fileExists(Path file) {
    return new OpenResult.Rejected(
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
    return new OpenResult.Failed(REASON_OPEN, ErrorMessages.of(e));
  }

  static OpenResult.Failed createFailed(PersistenceException e) {
    return new OpenResult.Failed(REASON_CREATE, ErrorMessages.of(e));
  }

  /** Ошибка миграции уже содержит путь к резервной копии. */
  static OpenResult.Failed migrationFailed(PersistenceException e) {
    return new OpenResult.Failed(REASON_MIGRATION, ErrorMessages.of(e));
  }

  /**
   * Исключение, не предусмотренное контрактом операции: поток жизненного цикла продолжает работу.
   */
  static OpenResult.Failed unexpected(RuntimeException e) {
    return new OpenResult.Failed(
        REASON_UNEXPECTED, "Непредвиденная ошибка: " + ErrorMessages.of(e));
  }
}
