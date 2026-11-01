// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.function.Consumer;
import ru.onlychill.bonds2.dbservice.DbState;

/**
 * Подписчик на состояние БД: переносит каждое состояние в поток интерфейса по одному, в порядке
 * публикации.
 */
final class StateSubscriber implements Flow.Subscriber<DbState> {

  private static final System.Logger LOG = System.getLogger(StateSubscriber.class.getName());

  private final Executor uiExecutor;
  private final Consumer<DbState> apply;

  /**
   * @param uiExecutor выполняет задачу в потоке интерфейса
   * @param apply применяет состояние в потоке интерфейса
   */
  StateSubscriber(Executor uiExecutor, Consumer<DbState> apply) {
    this.uiExecutor = uiExecutor;
    this.apply = apply;
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(DbState item) {
    uiExecutor.execute(() -> apply.accept(item));
  }

  @Override
  public void onError(Throwable error) {
    LOG.log(System.Logger.Level.ERROR, "Сбой публикации состояния БД", error);
  }

  @Override
  public void onComplete() {}
}
