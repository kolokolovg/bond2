// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.ErrorMessages;
import ru.onlychill.bonds2.dbservice.OpenResult;
import ru.onlychill.bonds2.dbservice.ShutdownResult;

/**
 * Машина выхода: решения о завершении приложения в одном месте, без зависимости от интерфейса.
 * Владеет фазой {@link ExitPhase}, сроками ожидания и порядком цепочки завершения («остановка БД →
 * освобождение блокировки экземпляра → снятие хука завершения JVM»). Решения о состояниях берутся
 * из предикатов {@link DbState} и причины {@link ShutdownResult.Refused}; тексты предупреждений
 * показывает через {@link ExitView}.
 *
 * <p>Методы запуска выхода и доставки результатов выполняются в потоке интерфейса; {@link
 * #shutdownForExternalTermination()} и {@link #runExitChain(Runnable, Runnable)} — на любом потоке
 * (в приложении — выделенный поток завершения). Фаза хранится атомарно: читают её поток интерфейса,
 * поток жизненного цикла БД и поток завершения.
 */
public final class ExitCoordinator {

  /** Сколько ждать окончания открытия или создания при выходе через меню и окно. */
  public static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

  /** Сколько ждать открытия или создания при завершении в обход меню. */
  public static final Duration STOP_TIMEOUT = Duration.ofSeconds(5);

  /** Сколько ждать окончания миграции при завершении в обход меню. */
  public static final Duration MIGRATION_TIMEOUT = Duration.ofSeconds(30);

  private static final String MIGRATION_EXIT_WARNING =
      "Идёт миграция базы. Дождитесь её завершения, затем закройте окно.";

  private static final String TIMED_OUT_EXIT_WARNING =
      "БД могла закрыться не полностью; данные не потеряны и восстановятся при следующем открытии.";

  private static final System.Logger LOG = System.getLogger(ExitCoordinator.class.getName());

  private final DatabaseService service;
  private final ExitView view;
  private final Runnable exit;
  private final Executor uiExecutor;
  private final Supplier<DbState> uiState;

  /**
   * Взгляд на сценарий миграции; назначается после построения, потому что сценарий сам читает фазу
   * координатора. До назначения ни один метод выхода не вызывается.
   */
  private volatile @Nullable MigrationView migration;

  private final AtomicReference<ExitPhase> phase = new AtomicReference<>(ExitPhase.RUNNING);

  /** Цепочка завершения запускается один раз: повторный запуск ничего не делает. */
  private final AtomicBoolean chainLaunched = new AtomicBoolean();

  /**
   * @param service сервис жизненного цикла БД
   * @param view порты интерфейса: предупреждения и ошибки цепочки выхода
   * @param exit завершение приложения
   * @param uiExecutor выполняет задачу в потоке интерфейса
   * @param uiState состояние БД, видимое интерфейсу (доставленная копия)
   */
  public ExitCoordinator(
      DatabaseService service,
      ExitView view,
      Runnable exit,
      Executor uiExecutor,
      Supplier<DbState> uiState) {
    this.service = service;
    this.view = view;
    this.exit = exit;
    this.uiExecutor = uiExecutor;
    this.uiState = uiState;
  }

  /** Назначает взгляд на сценарий миграции; см. поле {@code migration}. */
  public void attachMigration(MigrationView migrationView) {
    this.migration = migrationView;
  }

  /** Выход начат ({@link ExitPhase#EXITING}) или итог уже известен ({@code CONCLUDED}). */
  public boolean exiting() {
    return phase.get() != ExitPhase.RUNNING;
  }

  /** Итог остановки известен: поздние результаты операций игнорируются. */
  public boolean concluded() {
    return phase.get() == ExitPhase.CONCLUDED;
  }

  /**
   * «Файл → Выход» и закрытие окна кнопкой. Во время миграции выход откладывается с сообщением;
   * повторный запрос игнорируется. Ожидающая решения миграция считается отклонённой, её диалог
   * закрывается. Сбой цепочки возвращает интерфейс к работе: повторный «Выход» начинает цепочку
   * заново.
   */
  public void requestExit() {
    DbState current = uiState.get();
    if (!current.allowsShutdown() && !current.awaitsUserDecision()) {
      // Идёт миграция: выход откладывается до её завершения
      view.warnMigrationInProgress(MIGRATION_EXIT_WARNING);
      return;
    }
    if (!phase.compareAndSet(ExitPhase.RUNNING, ExitPhase.EXITING)) {
      return;
    }
    if (current.awaitsUserDecision()) {
      migration().cancelDialog();
      declineAndStop();
    } else {
      deliverExitResult(service.shutdownAsync(SHUTDOWN_TIMEOUT));
    }
  }

  /**
   * Итог остановки в потоке интерфейса. Истечение срока показывает предупреждение и завершает
   * приложение после его подтверждения; отказ с ожидаемым решением отклоняет миграцию и повторяет
   * остановку; отказ из-за идущей миграции возвращает интерфейс к работе. Точка доставки из тестов:
   * тесты передают результаты напрямую.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void onExitResult(ShutdownResult result) {
    switch (result) {
      case ShutdownResult.TimedOut ignored -> {
        phase.set(ExitPhase.CONCLUDED);
        view.warnTimedOutBeforeExit(TIMED_OUT_EXIT_WARNING)
            .thenRunAsync(exit, uiExecutor)
            .exceptionallyAsync(
                error -> {
                  onExitFailure(error);
                  return null;
                },
                uiExecutor);
      }
      // Остановлено: завершение сразу
      case ShutdownResult.Stopped stoppedResult -> {
        phase.set(ExitPhase.CONCLUDED);
        exit.run();
      }
      case ShutdownResult.Refused refusal -> {
        switch (refusal.cause()) {
          // Ожидалось решение о миграции: принимаем его за пользователя и повторяем остановку
          case DECISION_PENDING -> declineAndStop();
          // Идёт миграция: завершение невозможно, интерфейс возвращается к работе
          case MIGRATING -> {
            // Откат только из фазы, установленной этим же путём: параллельное внешнее
            // завершение могло уже завершить фазу — её не откатываем
            phase.compareAndSet(ExitPhase.EXITING, ExitPhase.RUNNING);
            view.warnMigrationInProgress(MIGRATION_EXIT_WARNING);
          }
        }
      }
    }
  }

  /**
   * Отклоняет ожидающую решения миграцию и затем останавливает сервис: миграция считается
   * отклонённой, файл БД не меняется, а сервис допускает остановку.
   */
  private void declineAndStop() {
    CompletableFuture<ShutdownResult> stopping =
        service.declineMigration().thenCompose(ignored -> service.shutdownAsync(SHUTDOWN_TIMEOUT));
    deliverExitResult(stopping);
  }

  /**
   * Передаёт итог остановки в поток интерфейса. Сбой цепочки выхода — сама остановка или её
   * обработка — восстанавливает интерфейс: если итог ещё не известен, фаза сброшена, ошибка
   * показана, приложение продолжает работу; если итог уже известен, завершение продолжается, а
   * повторный сбой самого завершения возвращает интерфейс к работе — «зомби» с замолчавшими
   * результатами не возникает.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  private void deliverExitResult(CompletableFuture<ShutdownResult> stopping) {
    stopping
        .thenAcceptAsync(this::onExitResult, uiExecutor)
        .exceptionallyAsync(
            error -> {
              onExitFailure(error);
              return null;
            },
            uiExecutor);
  }

  private void onExitFailure(Throwable error) {
    LOG.log(System.Logger.Level.ERROR, "Сбой остановки сервиса БД", error);
    if (phase.get() == ExitPhase.CONCLUDED) {
      // Итог остановки известен: завершаемся; сбой интерфейса остаётся в журнале. Повторный сбой
      // самого завершения возвращает приложение к работе — иначе фаза застряла бы в CONCLUDED
      try {
        exit.run();
      } catch (RuntimeException | Error repeated) {
        LOG.log(System.Logger.Level.ERROR, "Повторный сбой завершения приложения", repeated);
        phase.set(ExitPhase.RUNNING);
        view.showError(ErrorMessages.of(failureOf(repeated)));
      }
      return;
    }
    phase.set(ExitPhase.RUNNING);
    view.showError(ErrorMessages.of(failureOf(error)));
  }

  /** Первый значимый сбой: обёртка {@link CompletionException} снята до причины. */
  private static Throwable failureOf(Throwable error) {
    if (error instanceof CompletionException completion && completion.getCause() != null) {
      return completion.getCause();
    }
    return error;
  }

  /**
   * Завершение приложения в обход меню: останавливает сервис не дольше срока {@link #STOP_TIMEOUT}.
   * Ожидающая решения миграция считается отклонённой, идущая доводится до конца не дольше {@link
   * #MIGRATION_TIMEOUT}. Сбой остановки журналируется и завершению не мешает: диалог в пути
   * завершения невозможен.
   *
   * <p>Метод выполняется на любом потоке: в приложении это выделенный поток завершения.
   */
  public void shutdownForExternalTermination() {
    try {
      if (!(shutdownWaiting(STOP_TIMEOUT) instanceof ShutdownResult.Refused)) {
        return;
      }
      migration().cancelDialog();
      // Ожидание миграции сознательно: прервать миграцию хуже, чем подождать — откат при
      // следующем запуске потерял бы гарантию «файл не тронут»
      CompletableFuture<?> prepared = CompletableFuture.completedFuture(null);
      CompletableFuture<OpenResult> current = migration().currentMigration();
      if (service.state().awaitsUserDecision()) {
        prepared = service.declineMigration();
      } else if (current != null) {
        // currentMigration уже возвращает защитную копию
        prepared = current;
      }
      ShutdownResult retried =
          prepared
              .orTimeout(MIGRATION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
              .handle((value, error) -> null)
              .thenCompose(ignored -> service.shutdownAsync(MIGRATION_TIMEOUT))
              .join();
      logTimedOutStop(retried);
      phase.set(ExitPhase.CONCLUDED);
    } catch (RuntimeException | Error e) {
      // Диалог здесь невозможен: тулкит гаснет, процесс обязан завершиться. Сбой остановки
      // журналируется, а завершение продолжается
      LOG.log(System.Logger.Level.ERROR, "Сбой остановки сервиса БД при внешнем завершении", e);
    }
  }

  /**
   * Синхронная остановка сервиса; поздние результаты игнорируются, если отказа не было. Точка
   * доставки из тестов.
   */
  public ShutdownResult shutdownWaiting(Duration timeout) {
    ShutdownResult result = service.shutdown(timeout);
    if (!(result instanceof ShutdownResult.Refused)) {
      phase.set(ExitPhase.CONCLUDED);
      logTimedOutStop(result);
    }
    return result;
  }

  /** Действие хука завершения JVM: останавливает сервис не дольше {@link #STOP_TIMEOUT}. */
  public void stopForExternalShutdown() {
    shutdownWaiting(STOP_TIMEOUT);
  }

  /**
   * Цепочка штатного завершения: остановка БД, затем освобождение блокировки экземпляра (даже при
   * сбое остановки), последним снимается хук завершения JVM. Запускается один раз: повторный вызов
   * ничего не делает.
   *
   * @return выполнялась ли цепочка: {@code false} — уже запускалась
   */
  public boolean runExitChain(Runnable releaseLock, Runnable disarmHook) {
    if (!chainLaunched.compareAndSet(false, true)) {
      return false;
    }
    try {
      shutdownForExternalTermination();
    } finally {
      // Снятие хука обязано произойти даже при сбое освобождения блокировки:
      // вооружённый хук дёрнул бы остановку уже после освобождения блокировки
      try {
        releaseLock.run();
      } finally {
        disarmHook.run();
      }
    }
    return true;
  }

  /** Истечение срока остановки — в журнал: диалог в путях без меню невозможен. */
  private static void logTimedOutStop(ShutdownResult result) {
    if (result instanceof ShutdownResult.TimedOut) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Остановка БД не завершилась в срок; данные восстановятся при следующем открытии из"
              + " журнала WAL");
    }
  }

  private MigrationView migration() {
    return Objects.requireNonNull(migration, "взгляд на сценарий миграции не назначен");
  }
}
