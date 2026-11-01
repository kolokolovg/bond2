// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

/**
 * Итог остановки сервиса. Записи-варианты дают сравнение по значению; решения о вариантах
 * принимаются исчерпывающим {@code switch} — новое состояние автомата, влияющее на остановку,
 * описывается в {@link DbState} и {@link ShutdownRefusal}, а не в точках разбора результата.
 */
public sealed interface ShutdownResult {

  /** Сервис остановлен, БД закрыта. */
  Stopped STOPPED = new Stopped();

  /** Операция не завершилась в срок: ожидание прекращено, запоздавшая БД закроется сама. */
  TimedOut TIMED_OUT = new TimedOut();

  /** Сервис остановлен, БД закрыта. */
  record Stopped() implements ShutdownResult {}

  /** Операция не завершилась в срок: ожидание прекращено, запоздавшая БД закроется сама. */
  record TimedOut() implements ShutdownResult {}

  /**
   * Остановка отклонена, сервис работает.
   *
   * @param cause причина отказа
   */
  record Refused(ShutdownRefusal cause) implements ShutdownResult {}
}
