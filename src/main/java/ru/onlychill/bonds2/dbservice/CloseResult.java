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
   * Закрыть не удалось из-за непредвиденной ошибки: состояние «закрыта», причина «внутренняя
   * ошибка», сервис продолжает работать.
   *
   * @param message подробности для пользователя
   */
  record Failed(String message) implements CloseResult {}

  /**
   * Операция недопустима в текущем состоянии или сервис остановлен: состояние не менялось.
   *
   * @param cause типизированная причина отклонения
   * @param message текст для пользователя; может меняться независимо от причины
   */
  record Rejected(RejectionCause cause, String message) implements CloseResult {}
}
