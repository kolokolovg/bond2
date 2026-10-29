package ru.onlychill.bonds2;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.concurrent.Task;

/** Фоновое выполнение на {@link Task}: результат и ошибка приходят в поток JavaFX. */
final class FxBackground implements DatabaseController.Background {

  @Override
  public void run(
      Callable<OpenResult> work, Consumer<OpenResult> onDone, Consumer<Throwable> onError) {
    var task = new WorkTask(work);
    task.setOnSucceeded(event -> onDone.accept(task.getValue()));
    task.setOnFailed(event -> onError.accept(task.getException()));
    var thread = new Thread(task, "bonds2-db");
    // Демон: незавершённая операция не должна удерживать процесс при выходе
    thread.setDaemon(true);
    thread.start();
  }

  /** Задача, которая выполняет работу с БД вне потока JavaFX. */
  private static final class WorkTask extends Task<OpenResult> {

    private final Callable<OpenResult> work;

    WorkTask(Callable<OpenResult> work) {
      this.work = work;
    }

    @Override
    protected OpenResult call() throws Exception {
      return work.call();
    }
  }
}
