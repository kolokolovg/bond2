// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class StatePublisherTest {

  private static final DbState OPEN = new DbState.Open(Path.of("a.db"));

  /** Подписчик с ручным спросом. */
  private static final class Probe implements Flow.Subscriber<DbState> {

    final List<DbState> items = new ArrayList<>();
    final List<String> events = new ArrayList<>();
    Flow.Subscription subscription;
    long initialRequest;
    boolean failOnNext;

    Probe(long initialRequest) {
      this.initialRequest = initialRequest;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
      this.subscription = subscription;
      events.add("subscribe");
      if (initialRequest > 0) {
        subscription.request(initialRequest);
      }
    }

    @Override
    public void onNext(DbState item) {
      items.add(item);
      if (failOnNext) {
        throw new IllegalStateException("сбой подписчика");
      }
    }

    @Override
    public void onError(Throwable error) {
      events.add("error");
    }

    @Override
    public void onComplete() {
      events.add("complete");
    }
  }

  @Test
  void subscriberGetsCurrentStateThenChangesInOrder() {
    var publisher = new StatePublisher(DbState.CLOSED);
    publisher.publish(OPEN);
    var probe = new Probe(Long.MAX_VALUE);

    publisher.subscribe(probe);
    publisher.publish(DbState.CLOSED);

    assertThat(probe.items).containsExactly(OPEN, DbState.CLOSED);
  }

  @Test
  void respectsDemandWithoutLosingStates() {
    var publisher = new StatePublisher(DbState.CLOSED);
    var probe = new Probe(1);
    publisher.subscribe(probe);

    publisher.publish(OPEN);
    publisher.publish(DbState.CLOSED);
    assertThat(probe.items).containsExactly(DbState.CLOSED);

    probe.subscription.request(1);
    assertThat(probe.items).containsExactly(DbState.CLOSED, OPEN);
    probe.subscription.request(5);
    assertThat(probe.items).containsExactly(DbState.CLOSED, OPEN, DbState.CLOSED);
  }

  @Test
  void cancelStopsDelivery() {
    var publisher = new StatePublisher(DbState.CLOSED);
    var probe = new Probe(Long.MAX_VALUE);
    publisher.subscribe(probe);

    probe.subscription.cancel();
    publisher.publish(OPEN);

    assertThat(probe.items).containsExactly(DbState.CLOSED);
  }

  @Test
  void nonPositiveRequestCancels() {
    var publisher = new StatePublisher(DbState.CLOSED);
    var probe = new Probe(0);
    publisher.subscribe(probe);

    probe.subscription.request(0);
    probe.subscription.request(1);
    publisher.publish(OPEN);

    assertThat(probe.items).isEmpty();
  }

  @Test
  void failingSubscriberIsCancelledAndOthersKeepWorking() {
    var publisher = new StatePublisher(DbState.CLOSED);
    var bad = new Probe(Long.MAX_VALUE);
    bad.failOnNext = true;
    var good = new Probe(Long.MAX_VALUE);
    publisher.subscribe(bad);
    publisher.subscribe(good);

    publisher.publish(OPEN);
    publisher.publish(DbState.CLOSED);

    assertThat(bad.items).containsExactly(DbState.CLOSED);
    assertThat(good.items).containsExactly(DbState.CLOSED, OPEN, DbState.CLOSED);
  }

  @Test
  void closeDeliversPendingStatesThenCompletes() {
    var publisher = new StatePublisher(DbState.CLOSED);
    var probe = new Probe(1);
    publisher.subscribe(probe);
    publisher.publish(OPEN);

    publisher.close();
    assertThat(probe.events).containsExactly("subscribe");

    probe.subscription.request(1);
    assertThat(probe.items).containsExactly(DbState.CLOSED, OPEN);
    assertThat(probe.events).containsExactly("subscribe", "complete");

    publisher.publish(DbState.CLOSED);
    assertThat(probe.items).hasSize(2);
  }

  @Test
  void completesOnlyOnce() {
    var publisher = new StatePublisher(DbState.CLOSED);
    var probe = new Probe(Long.MAX_VALUE);
    publisher.subscribe(probe);

    publisher.close();
    publisher.close();

    assertThat(probe.events).containsExactly("subscribe", "complete");
  }

  @Test
  void hugeDemandDoesNotOverflow() {
    var publisher = new StatePublisher(DbState.CLOSED);
    var probe = new Probe(Long.MAX_VALUE);
    publisher.subscribe(probe);
    probe.subscription.request(Long.MAX_VALUE);

    publisher.publish(OPEN);

    assertThat(probe.items).containsExactly(DbState.CLOSED, OPEN);
  }
}
