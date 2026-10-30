// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Queue;
import org.jspecify.annotations.Nullable;

/** Текст ошибки для показа пользователю. */
public final class ErrorMessages {

  /** Ограничение обхода цепочки причин: длинные и циклические цепочки завершаются. */
  private static final int MAX_CAUSE_DEPTH = 5;

  private ErrorMessages() {}

  /**
   * Первый ненулевой код результата SQLite ({@code getErrorCode()}) среди {@link SQLException}
   * цепочки причин, включая {@code getNextException()}; обход не идёт глубже {@link
   * #MAX_CAUSE_DEPTH} причин. Код драйвер не заполняет повсеместно: {@code 0} означает «кода в
   * цепочке нет», и вызывающий классифицирует ошибку по месту возникновения.
   */
  static int sqliteResultCode(Throwable error) {
    var pending = new ArrayDeque<Throwable>();
    addCausesOf(error, pending);
    for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
      Throwable cause = pending.poll();
      if (cause == null) {
        break;
      }
      if (cause instanceof SQLException sql && sql.getErrorCode() != 0) {
        return sql.getErrorCode();
      }
      addCausesOf(cause, pending);
    }
    return 0;
  }

  /**
   * Сообщение исключения. Если у непосредственной причины сообщения нет или оно не вошло в текст,
   * берётся первое непустое сообщение из глубины цепочки причин ({@code getCause()}, а для {@link
   * SQLException} ещё и {@code getNextException()}) не дальше пяти причин; формат: {@code <текст>
   * Причина: <сообщение>}. Без сообщения используется имя класса исключения.
   */
  public static String of(Throwable error) {
    String message = error.getMessage();
    String text = message == null ? error.getClass().getSimpleName() : message;
    String causeText = firstUnmentionedCause(error, text);
    if (causeText == null) {
      return text;
    }
    return text + " Причина: " + causeText;
  }

  /**
   * Первое непустое сообщение цепочки причин, ещё не вошедшее в текст; обход прекращается после
   * {@link #MAX_CAUSE_DEPTH} причин, поэтому циклические цепочки не зацикливаются.
   */
  private static @Nullable String firstUnmentionedCause(Throwable error, String text) {
    var pending = new ArrayDeque<Throwable>();
    addCausesOf(error, pending);
    for (int depth = 0; depth < MAX_CAUSE_DEPTH; depth++) {
      Throwable cause = pending.poll();
      if (cause == null) {
        break;
      }
      String message = cause.getMessage();
      if (message != null && !message.isBlank() && !text.contains(message)) {
        return message;
      }
      addCausesOf(cause, pending);
    }
    return null;
  }

  /**
   * Ставит в очередь непосредственную причину и, для {@link SQLException}, следующее исключение.
   */
  private static void addCausesOf(Throwable error, Queue<Throwable> pending) {
    Throwable cause = error.getCause();
    if (cause != null) {
      pending.add(cause);
    }
    if (error instanceof SQLException sql) {
      SQLException next = sql.getNextException();
      if (next != null) {
        pending.add(next);
      }
    }
  }
}
