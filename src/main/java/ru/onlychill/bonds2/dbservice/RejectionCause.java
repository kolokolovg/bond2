// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

/**
 * Причина отклонения операции жизненного цикла БД: различима потребителем без разбора текста
 * сообщения.
 */
public enum RejectionCause {
  /** Операция недопустима в текущем состоянии. */
  STATE,
  /** Сервис БД остановлен или остановка началась. */
  STOPPED
}
