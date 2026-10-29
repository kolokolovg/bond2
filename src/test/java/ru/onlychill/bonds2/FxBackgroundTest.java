package ru.onlychill.bonds2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FxBackgroundTest {

  @BeforeAll
  static void startToolkit() throws InterruptedException {
    FxToolkit.start();
  }

  @Test
  void resultIsDeliveredOnFxThread() throws InterruptedException {
    var delivered = new CountDownLatch(1);
    var onFxThread = new AtomicBoolean();
    var outcome = new AtomicReference<OpenResult>();

    new FxBackground()
        .run(
            OpenResult.NeedsMigration::new,
            result -> {
              onFxThread.set(Platform.isFxApplicationThread());
              outcome.set(result);
              delivered.countDown();
            },
            error -> delivered.countDown());

    assertThat(delivered.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(onFxThread).isTrue();
    assertThat(outcome.get()).isInstanceOf(OpenResult.NeedsMigration.class);
  }

  @Test
  void errorIsDeliveredOnFxThread() throws InterruptedException {
    var delivered = new CountDownLatch(1);
    var onFxThread = new AtomicBoolean();
    var failure = new AtomicReference<Throwable>();

    new FxBackground()
        .run(
            () -> {
              throw new IllegalStateException("сбой");
            },
            result -> delivered.countDown(),
            error -> {
              onFxThread.set(Platform.isFxApplicationThread());
              failure.set(error);
              delivered.countDown();
            });

    assertThat(delivered.await(30, TimeUnit.SECONDS)).isTrue();
    assertThat(onFxThread).isTrue();
    assertThat(failure.get()).isInstanceOf(IllegalStateException.class).hasMessage("сбой");
  }

  @Test
  void returnedFutureCompletesWithoutFxThreadDelivery() throws Exception {
    // Поток JavaFX занят: результат работы всё равно доступен через возвращённый Future
    var release = new CountDownLatch(1);
    var fxBusy = new CountDownLatch(1);
    Platform.runLater(
        () -> {
          fxBusy.countDown();
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    assertThat(fxBusy.await(30, TimeUnit.SECONDS)).isTrue();

    try {
      var future =
          new FxBackground().run(OpenResult.NeedsMigration::new, result -> {}, error -> {});

      assertThat(future.get(30, TimeUnit.SECONDS)).isInstanceOf(OpenResult.NeedsMigration.class);
    } finally {
      release.countDown();
    }
  }

  @Test
  void returnedFutureFailsWhenWorkFails() {
    var future =
        new FxBackground()
            .run(
                () -> {
                  throw new IllegalStateException("сбой");
                },
                result -> {},
                error -> {});

    assertThatThrownBy(() -> future.get(30, TimeUnit.SECONDS))
        .hasRootCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void workThreadIsNotDaemon() throws Exception {
    var daemon = new AtomicReference<Boolean>();

    new FxBackground()
        .run(
            () -> {
              daemon.set(Thread.currentThread().isDaemon());
              return new OpenResult.NeedsMigration();
            },
            result -> {},
            error -> {})
        .get(30, TimeUnit.SECONDS);

    assertThat(daemon.get()).isFalse();
  }
}
