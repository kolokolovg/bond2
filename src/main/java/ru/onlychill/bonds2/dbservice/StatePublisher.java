// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Flow;

/**
 * Издатель состояния БД. Подписчик сразу получает текущее состояние, затем все изменения по
 * порядку: очередь каждого подписчика не ограничена, поэтому переходы не теряются, а спрос
 * подписчика ({@code request}) соблюдается. Состояния малы, поэтому память не растёт заметно.
 */
final class StatePublisher implements Flow.Publisher<DbState> {

  private final Object lock = new Object();
  private final List<Subscription> subscriptions = new ArrayList<>();
  private DbState current;
  private boolean closed;

  StatePublisher(DbState initial) {
    this.current = initial;
  }

  /** Рассылает новое состояние; после {@link #close()} ничего не делает. */
  void publish(DbState next) {
    List<Subscription> targets;
    synchronized (lock) {
      if (closed) {
        return;
      }
      current = next;
      targets = List.copyOf(subscriptions);
      // Постановка в очередь под общей блокировкой задаёт единый порядок для всех подписчиков
      targets.forEach(s -> s.enqueue(next));
    }
    targets.forEach(Subscription::drain);
  }

  /** Завершает подписки: подписчики получают всё накопленное, затем {@code onComplete}. */
  void close() {
    List<Subscription> targets;
    synchronized (lock) {
      closed = true;
      targets = List.copyOf(subscriptions);
      subscriptions.clear();
    }
    targets.forEach(Subscription::complete);
  }

  @Override
  public void subscribe(Flow.Subscriber<? super DbState> subscriber) {
    var subscription = new Subscription(subscriber);
    boolean finished;
    synchronized (lock) {
      subscription.enqueue(current);
      finished = closed;
      if (!finished) {
        subscriptions.add(subscription);
      }
    }
    subscriber.onSubscribe(subscription);
    if (finished) {
      subscription.complete();
    }
  }

  private void remove(Subscription subscription) {
    synchronized (lock) {
      subscriptions.remove(subscription);
    }
  }

  /** Подписка: очередь, спрос и последовательная доставка. */
  private final class Subscription implements Flow.Subscription {

    private final Flow.Subscriber<? super DbState> subscriber;
    private final Queue<DbState> queue = new ArrayDeque<>();
    private long demand;
    private boolean draining;
    private boolean completing;
    private boolean completed;
    private boolean cancelled;

    Subscription(Flow.Subscriber<? super DbState> subscriber) {
      this.subscriber = subscriber;
    }

    synchronized void enqueue(DbState state) {
      queue.add(state);
    }

    void complete() {
      synchronized (this) {
        completing = true;
      }
      drain();
    }

    @Override
    public void request(long n) {
      if (n <= 0) {
        cancel();
        return;
      }
      synchronized (this) {
        demand = demand + n < 0 ? Long.MAX_VALUE : demand + n;
      }
      drain();
    }

    @Override
    public void cancel() {
      synchronized (this) {
        cancelled = true;
        queue.clear();
      }
      remove(this);
    }

    /** Доставляет накопленное по одному подписчику строго последовательно. */
    void drain() {
      synchronized (this) {
        if (draining) {
          return;
        }
        draining = true;
      }
      while (true) {
        DbState item;
        synchronized (this) {
          if (cancelled || completed) {
            draining = false;
            return;
          }
          if (demand > 0 && !queue.isEmpty()) {
            item = queue.remove();
            demand--;
          } else if (completing && queue.isEmpty()) {
            completed = true;
            item = null;
          } else {
            draining = false;
            return;
          }
        }
        try {
          if (item == null) {
            subscriber.onComplete();
          } else {
            subscriber.onNext(item);
          }
        } catch (RuntimeException e) {
          cancel();
        }
      }
    }
  }
}
