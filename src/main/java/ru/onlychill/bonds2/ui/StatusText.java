// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import ru.onlychill.bonds2.dbservice.DbState;

/** Текст строки состояния БД для каждого состояния сервиса. */
final class StatusText {

  /** Текст для закрытой БД без причины; также исходный текст до первой публикации состояния. */
  static final String CLOSED = "БД не открыта";

  private static final String OPEN = "БД открыта";
  private static final String OPENING = "Открытие базы…";

  private StatusText() {}

  /**
   * Текст строки состояния для {@code next}: «БД открыта», «БД не открыта» с причиной, либо
   * «Открытие базы…», пока выполняется открытие, миграция или ожидается решение о ней. Копирование
   * состояния БД не меняет: БД остаётся открытой.
   */
  static String of(DbState next) {
    return switch (next) {
      case DbState.Closed closed ->
          closed.reason() == null ? CLOSED : CLOSED + ": " + closed.reason();
      case DbState.Opening opening -> OPENING;
      case DbState.AwaitingMigration awaiting -> OPENING;
      case DbState.Migrating migrating -> OPENING;
      case DbState.Open open -> OPEN;
      case DbState.BackingUp backingUp -> OPEN;
    };
  }
}
