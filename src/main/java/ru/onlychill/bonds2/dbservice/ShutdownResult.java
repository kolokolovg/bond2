// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

/** Итог остановки сервиса. */
public enum ShutdownResult {
  /** Сервис остановлен, БД закрыта. */
  STOPPED,
  /** Операция не завершилась в срок: ожидание прекращено, запоздавшая БД закроется сама. */
  TIMED_OUT,
  /** Идёт миграция или ожидается решение о ней: остановка отклонена, сервис работает. */
  REFUSED
}
