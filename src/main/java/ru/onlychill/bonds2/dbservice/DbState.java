// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Неизменяемый снимок состояния БД. Определяет доступность пунктов меню и возможность выхода.
 *
 * <ul>
 *   <li>{@link Closed} — БД не открыта: её можно открыть или создать новую;
 *   <li>{@link Opening} — БД открывается или создаётся: другие операции недопустимы;
 *   <li>{@link AwaitingMigration} — схема устарела, сервис ждёт решения о миграции; файл не
 *       изменён;
 *   <li>{@link Migrating} — выполняется миграция с резервной копией;
 *   <li>{@link Open} — БД открыта: её можно закрыть.
 * </ul>
 */
public sealed interface DbState {

  /** Начальное состояние: БД не открыта, причины нет. */
  DbState CLOSED = new Closed(null, null);

  /** Что делает {@link Opening}: открывает существующий файл или создаёт новый. */
  enum Kind {
    OPEN,
    CREATE
  }

  /**
   * БД не открыта.
   *
   * @param reason короткая причина для строки состояния; {@code null}, если БД просто закрыта
   * @param message подробности для пользователя; {@code null}, если их нет
   */
  record Closed(@Nullable String reason, @Nullable String message) implements DbState {}

  /** БД открывается или создаётся по пути {@code file}. */
  record Opening(Kind kind, Path file) implements DbState {}

  /** Схема файла {@code file} устарела; команды миграции или отказа ещё нет. */
  record AwaitingMigration(Path file) implements DbState {}

  /** Выполняется миграция файла {@code file}. */
  record Migrating(Path file) implements DbState {}

  /** БД {@code file} открыта. */
  record Open(Path file) implements DbState {}

  /** «Открыть базу»: только когда БД закрыта и никакая операция не идёт. */
  default boolean allowsOpen() {
    return this instanceof Closed;
  }

  /** «Создать базу…»: только когда БД закрыта и никакая операция не идёт. */
  default boolean allowsCreate() {
    return this instanceof Closed;
  }

  /** «Закрыть базу»: только когда БД открыта. */
  default boolean allowsClose() {
    return this instanceof Open;
  }

  /** «Настройки…»: недоступны во время операций с БД. */
  default boolean allowsSettings() {
    return this instanceof Closed || this instanceof Open;
  }
}
