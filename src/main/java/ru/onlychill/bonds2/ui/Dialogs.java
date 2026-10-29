// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.util.concurrent.CompletableFuture;

/** Диалоги пользователя; реализация для JavaFX — {@link FxDialogs}. */
interface Dialogs {

  /**
   * Вопрос о миграции с кнопками «Применить» и «Отмена» без блокировки потока интерфейса. Future
   * завершается {@code true}, если пользователь согласен; его отмена закрывает диалог.
   */
  CompletableFuture<Boolean> confirmMigration(String question);

  /**
   * Предупреждение без ожидания закрытия: операция отложена или выполнена частично, приложение
   * продолжает работу.
   */
  void warn(String message);

  /** Сообщение об ошибке без ожидания закрытия. */
  void error(String message);
}
