// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2;

/** Текст ошибки для показа пользователю. */
final class ErrorMessages {

  private ErrorMessages() {}

  /**
   * Сообщение исключения. Если причина не вошла в него, её текст добавляется в конце. Без сообщения
   * используется имя класса исключения.
   */
  static String of(Throwable error) {
    String message = error.getMessage();
    String text = message == null ? error.getClass().getSimpleName() : message;
    Throwable cause = error.getCause();
    String causeText = cause == null ? null : cause.getMessage();
    if (causeText == null || text.contains(causeText)) {
      return text;
    }
    return text + " Причина: " + causeText;
  }
}
