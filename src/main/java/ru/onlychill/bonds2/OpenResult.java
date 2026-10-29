// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2;

import ru.onlychill.bonds2.persistence.Database;

/** Исход фоновой операции с БД: открытия, создания или миграции. */
sealed interface OpenResult {

  /** БД открыта и готова к работе. */
  record Opened(Database database) implements OpenResult {}

  /** Схема устарела: нужно согласие пользователя на миграцию. Файл при этом не изменён. */
  record NeedsMigration() implements OpenResult {}

  /**
   * Операция не выполнена.
   *
   * @param reason короткая причина для строки состояния
   * @param message текст для диалога с подробностями
   */
  record Failed(String reason, String message) implements OpenResult {}
}
