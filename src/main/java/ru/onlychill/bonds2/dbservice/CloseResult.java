// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import org.jspecify.annotations.Nullable;

/** Итог закрытия БД или отказа от миграции. */
public sealed interface CloseResult {

  /**
   * БД закрыта.
   *
   * @param warning предупреждение, если файл закрыт не полностью; {@code null} при чистом закрытии
   */
  record Closed(@Nullable String warning) implements CloseResult {}

  /**
   * Операция недопустима в текущем состоянии или сервис остановлен: состояние не менялось.
   *
   * @param message объяснение для пользователя
   */
  record Rejected(String message) implements CloseResult {}
}
