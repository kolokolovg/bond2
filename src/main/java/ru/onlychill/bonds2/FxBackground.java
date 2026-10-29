// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import javafx.concurrent.Task;

/** Фоновое выполнение на {@link Task}: результат и ошибка приходят в поток JavaFX. */
final class FxBackground implements DatabaseController.Background {

  @Override
  public Future<OpenResult> run(
      Callable<OpenResult> work, Consumer<OpenResult> onDone, Consumer<Throwable> onError) {
    var outcome = new CompletableFuture<OpenResult>();
    var task = new WorkTask(work, outcome);
    task.setOnSucceeded(event -> onDone.accept(task.getValue()));
    task.setOnFailed(event -> onError.accept(task.getException()));
    var thread = new Thread(task, "bonds2-db");
    // Демон: зависшая операция с БД не удерживает JVM; ожидание при выходе ведёт контроллер
    thread.setDaemon(true);
    thread.start();
    return outcome;
  }

  /**
   * Задача, которая выполняет работу с БД вне потока JavaFX. Итог доступен через {@code outcome}
   * сразу по окончании работы, не дожидаясь очереди потока JavaFX.
   */
  private static final class WorkTask extends Task<OpenResult> {

    private final Callable<OpenResult> work;
    private final CompletableFuture<OpenResult> outcome;

    WorkTask(Callable<OpenResult> work, CompletableFuture<OpenResult> outcome) {
      this.work = work;
      this.outcome = outcome;
    }

    @Override
    protected OpenResult call() throws Exception {
      try {
        OpenResult result = work.call();
        outcome.complete(result);
        return result;
      } catch (Exception | Error e) {
        outcome.completeExceptionally(e);
        throw e;
      }
    }
  }
}
