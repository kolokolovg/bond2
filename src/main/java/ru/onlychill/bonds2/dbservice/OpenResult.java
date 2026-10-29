// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.nio.file.Path;

/** Итог открытия, создания или миграции БД. */
public sealed interface OpenResult {

  /** БД открыта и готова к работе. */
  record Opened(Path file) implements OpenResult {}

  /** Схема устарела: нужно решение о миграции. Файл при этом не изменён. */
  record NeedsMigration(Path file) implements OpenResult {}

  /**
   * Операция не выполнена, состояние стало «закрыта».
   *
   * @param reason короткая причина для строки состояния
   * @param message текст для диалога с подробностями
   */
  record Failed(String reason, String message) implements OpenResult {}

  /**
   * Операция недопустима в текущем состоянии или сервис остановлен: состояние и файл не менялись.
   *
   * @param message объяснение для пользователя
   */
  record Rejected(String message) implements OpenResult {}
}
