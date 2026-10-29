// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

/** Диалоги пользователя; реализация для JavaFX — {@link FxDialogs}. */
interface Dialogs {

  /**
   * Вопрос о миграции с кнопками «Применить» и «Отмена»: {@code true}, если пользователь согласен.
   */
  boolean confirmMigration(String question);

  /** Предупреждение: операция отложена или выполнена частично, приложение продолжает работу. */
  void warn(String message);

  /** Сообщение об ошибке. */
  void error(String message);
}
