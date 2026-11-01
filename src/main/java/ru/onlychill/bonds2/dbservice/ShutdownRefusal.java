// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

/** Причина отказа остановки сервиса: остановка возможна после смены обстоятельств. */
public enum ShutdownRefusal {
  /** Идёт миграция: прерывать нельзя, остановка возможна после её завершения. */
  MIGRATING,
  /** Сервис ждёт решения пользователя о миграции: решение можно принять за пользователя. */
  DECISION_PENDING
}
