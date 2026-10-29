// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.application.Platform;

/** Запуск тулкита JavaFX для тестов, которым нужны контролы или поток JavaFX. Нужен дисплей. */
final class FxToolkit {

  private FxToolkit() {}

  /** Запускает тулкит один раз; повторные вызовы безопасны. */
  static void start() throws InterruptedException {
    var started = new CountDownLatch(1);
    try {
      Platform.startup(started::countDown);
    } catch (IllegalStateException alreadyStarted) {
      started.countDown();
    }
    started.await();
  }

  /**
   * Выполняет действие в потоке JavaFX и ждёт его окончания. Ошибки утверждений и исключения
   * действия пробрасываются вызывающему.
   */
  static void runOnFxThread(Runnable action) {
    var done = new CompletableFuture<Void>();
    Platform.runLater(
        () -> {
          try {
            action.run();
            done.complete(null);
          } catch (RuntimeException | AssertionError e) {
            done.completeExceptionally(e);
          }
        });
    try {
      done.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      if (e.getCause() instanceof AssertionError error) {
        throw error;
      }
      throw new IllegalStateException(e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (TimeoutException e) {
      throw new IllegalStateException("Поток JavaFX не ответил за 30 секунд", e);
    }
  }
}
