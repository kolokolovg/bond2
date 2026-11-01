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
   * Предупреждение перед завершением приложения. Future завершается, когда пользователь подтвердил
   * (закрыл диалог); показ без вложенного цикла событий, как у остальных диалогов; отмена future
   * закрывает диалог.
   */
  CompletableFuture<Void> warnBeforeExit(String message);

  /**
   * Предупреждение без ожидания закрытия: операция отложена или выполнена частично, приложение
   * продолжает работу.
   */
  void warn(String message);

  /** Информационное сообщение без ожидания закрытия: операция выполнена успешно. */
  void info(String message);

  /** Сообщение об ошибке без ожидания закрытия. */
  void error(String message);
}
