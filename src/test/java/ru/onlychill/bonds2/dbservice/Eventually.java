// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Ожидание условия в тестах без внешних библиотек. */
public final class Eventually {

  private Eventually() {}

  /** Ждёт условие до 10 секунд; {@code false}, если оно так и не выполнилось. */
  public static boolean holds(BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return true;
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
      }
    }
    return condition.getAsBoolean();
  }
}
