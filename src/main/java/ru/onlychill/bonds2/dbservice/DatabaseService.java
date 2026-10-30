// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.persistence.Database;
import ru.onlychill.bonds2.persistence.PersistenceException;

/**
 * Единственный владелец файла БД: открытие, создание, миграция, закрытие и остановка выполняются
 * строго по одной на отдельном потоке {@code bonds2-db}. Сервис не зависит от интерфейса и ничего
 * не спрашивает у пользователя: решение о миграции приходит командами {@link #migrate()} или {@link
 * #declineMigration()}. Состояние публикуется через {@link #states()}.
 *
 * <p>Методы можно вызывать из любого потока. Транзакции данных выполняются самой {@link Database}
 * вне потока жизненного цикла.
 */
public final class DatabaseService {

  private static final String MESSAGE_STOPPED = "Сервис БД остановлен.";

  private static final System.Logger LOG = System.getLogger(DatabaseService.class.getName());

  /** Операции с файлами БД; тесты подменяют их, чтобы управлять временем и сбоями. */
  public interface Storage {

    Database.State inspect(Path file);

    Database open(Path file);

    Database create(Path file);

    Database initialize(Path file);

    void migrateWithBackup(Path file);

    /** Закрывает БД и переносит журнал WAL в основной файл. */
    void close(Database database);
  }

  /** Итог попытки открыть: публичный результат и открытая БД, если она есть. */
  private record Attempt(OpenResult result, @Nullable Database database) {}

  private final Storage storage;
  private final ExecutorService executor =
      Executors.newSingleThreadExecutor(
          runnable -> {
            var thread = new Thread(runnable, "bonds2-db");
            // Демон: зависшая операция не должна удерживать процесс после таймаута остановки
            thread.setDaemon(true);
            return thread;
          });
  private final StatePublisher publisher = new StatePublisher(DbState.CLOSED);
  private final Object shutdownLock = new Object();

  private volatile DbState state = DbState.CLOSED;

  /** Остановка начата: новые операции отклоняются. */
  private volatile boolean stopping;

  /** Вызывающий перестал ждать остановку: запоздавшая БД закрывается без публикации. */
  private volatile boolean abandoned;

  /** Открытая БД; трогается только потоком жизненного цикла. */
  private @Nullable Database database;

  private @Nullable CompletableFuture<ShutdownResult> shutdownFuture;

  /** Сервис с настоящими файлами БД. */
  public DatabaseService() {
    this(new FileStorage());
  }

  /** Сервис с заданными операциями над файлами БД. */
  public DatabaseService(Storage storage) {
    this.storage = storage;
  }

  /** Текущее состояние. */
  public DbState state() {
    return state;
  }

  /** Издатель состояния: подписчик получает текущее состояние, затем изменения по порядку. */
  public Flow.Publisher<DbState> states() {
    return subscriber -> publisher.subscribe(subscriber);
  }

  /**
   * Открывает БД. Устаревшая схема даёт {@link OpenResult.NeedsMigration} без изменения файла.
   *
   * @param createIfMissing создать БД, если файла нет; иначе отсутствие файла — ошибка
   */
  public CompletableFuture<OpenResult> open(Path file, boolean createIfMissing) {
    return submit(() -> doOpen(file, createIfMissing), OpenResult.Rejected::new);
  }

  /** Создаёт БД с актуальной схемой; существующий файл не изменяется. */
  public CompletableFuture<OpenResult> create(Path file) {
    return submit(() -> doCreate(file), OpenResult.Rejected::new);
  }

  /** Мигрирует БД с резервной копией и открывает её; допустимо после {@code NeedsMigration}. */
  public CompletableFuture<OpenResult> migrate() {
    return submit(this::doMigrate, OpenResult.Rejected::new);
  }

  /** Отказ от миграции: БД закрыта с причиной «схема устарела», файл не изменён. */
  public CompletableFuture<CloseResult> declineMigration() {
    return submit(this::doDecline, CloseResult.Rejected::new);
  }

  /** Закрывает открытую БД на потоке жизненного цикла. */
  public CompletableFuture<CloseResult> close() {
    return submit(this::doClose, CloseResult.Rejected::new);
  }

  /**
   * Останавливает сервис и ждёт не дольше {@code timeout}: обёртка над {@link
   * #shutdownAsync(Duration)}. Вызов блокирует вызывающего, поэтому из потока интерфейса его
   * применять не следует.
   */
  public ShutdownResult shutdown(Duration timeout) {
    CompletableFuture<ShutdownResult> done = shutdownAsync(timeout);
    try {
      return done.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (TimeoutException e) {
      LOG.log(System.Logger.Level.WARNING, "Операция с БД не завершилась при остановке", e);
    } catch (ExecutionException e) {
      LOG.log(System.Logger.Level.WARNING, "Остановка сервиса БД завершилась ошибкой", e);
      return ShutdownResult.STOPPED;
    }
    abandoned = true;
    return ShutdownResult.TIMED_OUT;
  }

  /**
   * Останавливает сервис, не блокируя вызывающего: ждёт завершения текущей операции не дольше
   * {@code timeout}, затем закрывает БД. Во время миграции и ожидания решения о ней результат —
   * {@link ShutdownResult#REFUSED}. По истечении срока результат — {@link
   * ShutdownResult#TIMED_OUT}, а БД, открытая позже, закрывается без публикации состояния.
   * Повторный вызов после начала остановки возвращает future с тем же результатом.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public CompletableFuture<ShutdownResult> shutdownAsync(Duration timeout) {
    synchronized (shutdownLock) {
      CompletableFuture<ShutdownResult> existing = shutdownFuture;
      if (existing != null) {
        return existing.copy();
      }
      if (mustNotStop(state)) {
        return CompletableFuture.completedFuture(ShutdownResult.REFUSED);
      }
      var done = new CompletableFuture<ShutdownResult>();
      shutdownFuture = done;
      stopping = true;
      try {
        executor.execute(() -> runShutdown(done));
      } catch (RejectedExecutionException e) {
        done.complete(ShutdownResult.STOPPED);
      }
      done.completeOnTimeout(ShutdownResult.TIMED_OUT, timeout.toMillis(), TimeUnit.MILLISECONDS);
      done.thenAccept(
          result -> {
            if (result == ShutdownResult.TIMED_OUT) {
              abandoned = true;
            }
          });
      return done;
    }
  }

  private static boolean mustNotStop(DbState current) {
    return current instanceof DbState.Migrating || current instanceof DbState.AwaitingMigration;
  }

  private void runShutdown(CompletableFuture<ShutdownResult> done) {
    try {
      if (!abandoned && mustNotStop(state)) {
        synchronized (shutdownLock) {
          stopping = false;
          shutdownFuture = null;
        }
        done.complete(ShutdownResult.REFUSED);
        return;
      }
      Database opened = database;
      database = null;
      if (opened != null) {
        closeQuietly(opened);
        if (!abandoned) {
          publish(DbState.CLOSED);
        }
      }
      publisher.close();
      executor.shutdown();
      done.complete(ShutdownResult.STOPPED);
    } catch (RuntimeException | Error e) {
      LOG.log(System.Logger.Level.WARNING, "Остановка сервиса БД завершилась ошибкой", e);
      done.complete(ShutdownResult.STOPPED);
      throw e;
    }
  }

  /** Ставит работу в очередь потока жизненного цикла; после остановки отклоняет. */
  private <T> CompletableFuture<T> submit(Supplier<T> work, Function<String, T> rejection) {
    if (stopping) {
      return CompletableFuture.completedFuture(rejection.apply(MESSAGE_STOPPED));
    }
    var result = new CompletableFuture<T>();
    try {
      executor.execute(
          () -> {
            try {
              result.complete(work.get());
            } catch (RuntimeException | Error e) {
              result.completeExceptionally(e);
            }
          });
    } catch (RejectedExecutionException e) {
      return CompletableFuture.completedFuture(rejection.apply(MESSAGE_STOPPED));
    }
    return result;
  }

  private void publish(DbState next) {
    state = next;
    publisher.publish(next);
  }

  private OpenResult doOpen(Path file, boolean createIfMissing) {
    if (!(state instanceof DbState.Closed)) {
      return new OpenResult.Rejected(OpenFailures.notAllowed("открыть"));
    }
    publish(new DbState.Opening(DbState.Kind.OPEN, file));
    return finish(file, () -> attempt(file, createIfMissing));
  }

  private OpenResult doCreate(Path file) {
    if (!(state instanceof DbState.Closed)) {
      return new OpenResult.Rejected(OpenFailures.notAllowed("создать"));
    }
    if (Files.exists(file)) {
      return OpenFailures.fileExists(file);
    }
    publish(new DbState.Opening(DbState.Kind.CREATE, file));
    return finish(file, () -> createDatabase(file));
  }

  private OpenResult doMigrate() {
    if (!(state instanceof DbState.AwaitingMigration awaiting)) {
      return new OpenResult.Rejected(OpenFailures.notAllowed("мигрировать"));
    }
    Path file = awaiting.file();
    publish(new DbState.Migrating(file));
    return finish(file, () -> migrateAndOpen(file));
  }

  private CloseResult doDecline() {
    if (!(state instanceof DbState.AwaitingMigration)) {
      return new CloseResult.Rejected(OpenFailures.notAllowed("отказаться от миграции"));
    }
    publish(new DbState.Closed(OpenFailures.REASON_OUTDATED, null));
    return new CloseResult.Closed(null);
  }

  private CloseResult doClose() {
    Database opened = database;
    if (opened == null || !(state instanceof DbState.Open)) {
      return new CloseResult.Rejected(OpenFailures.notAllowed("закрыть"));
    }
    database = null;
    String warning = null;
    try {
      storage.close(opened);
    } catch (VirtualMachineError e) {
      throw e;
    } catch (PersistenceException e) {
      warning =
          "Не удалось полностью закрыть БД: "
              + ErrorMessages.of(e)
              + " Зафиксированные данные не потеряны: они остаются в журнале WAL и будут применены"
              + " при следующем открытии.";
    } catch (RuntimeException | Error e) {
      LOG.log(System.Logger.Level.ERROR, "Внутренняя ошибка при закрытии БД", e);
      OpenResult.Failed failure = failed(OpenFailures.unexpected(e));
      return new CloseResult.Failed(failure.message());
    }
    publish(DbState.CLOSED);
    return new CloseResult.Closed(warning);
  }

  /**
   * Выполняет попытку и публикует её итог. Любой сбой среды выполнения превращается в «внутреннюю
   * ошибку» с публикацией «закрыта»: поток жизненного цикла не завершается. Фатальные ошибки
   * виртуальной машины пробрасываются без поглощения.
   */
  private OpenResult finish(Path file, Supplier<Attempt> operation) {
    Attempt attempt;
    try {
      attempt = operation.get();
    } catch (VirtualMachineError e) {
      throw e;
    } catch (RuntimeException | Error e) {
      LOG.log(System.Logger.Level.ERROR, "Внутренняя ошибка при работе с БД: " + file, e);
      return failed(OpenFailures.unexpected(e));
    }
    Database opened = attempt.database();
    switch (attempt.result()) {
      case OpenResult.Opened result -> {
        if (opened == null) {
          throw new IllegalStateException("Открытая БД не передана");
        }
        if (abandoned) {
          // Остановка уже не ждёт: БД закрывается, состояние не публикуется
          closeQuietly(opened);
          return new OpenResult.Rejected(MESSAGE_STOPPED);
        }
        database = opened;
        publish(new DbState.Open(file));
        return result;
      }
      case OpenResult.NeedsMigration result -> publish(new DbState.AwaitingMigration(file));
      case OpenResult.Failed result ->
          publish(new DbState.Closed(result.reason(), result.message()));
      case OpenResult.Rejected result -> throw new IllegalStateException(result.message());
    }
    return attempt.result();
  }

  private OpenResult.Failed failed(OpenResult.Failed failure) {
    publish(new DbState.Closed(failure.reason(), failure.message()));
    return failure;
  }

  /**
   * Открытие: по наличию файла и состоянию схемы выбирает действие. Отсутствующий файл создаётся
   * только с {@code createIfMissing}. Пустой файл инициализируется без вопроса о миграции; чужой
   * файл отклоняется и не меняется.
   */
  private Attempt attempt(Path file, boolean createIfMissing) {
    if (Files.notExists(file)) {
      return createIfMissing ? createDatabase(file) : new Attempt(OpenFailures.missing(file), null);
    }
    try {
      return switch (storage.inspect(file)) {
        case UP_TO_DATE -> opened(file, storage.open(file));
        case NEEDS_MIGRATION -> new Attempt(new OpenResult.NeedsMigration(file), null);
        case TOO_NEW -> new Attempt(OpenFailures.tooNew(file), null);
        case EMPTY -> opened(file, storage.initialize(file));
        case FOREIGN -> new Attempt(OpenFailures.foreign(file), null);
      };
    } catch (PersistenceException e) {
      return new Attempt(OpenFailures.openFailed(e), null);
    }
  }

  private Attempt createDatabase(Path file) {
    try {
      return opened(file, storage.create(file));
    } catch (PersistenceException e) {
      return new Attempt(OpenFailures.createFailed(e), null);
    }
  }

  /** Миграция с резервной копией и открытие. Ошибка миграции уже содержит путь к копии. */
  private Attempt migrateAndOpen(Path file) {
    try {
      storage.migrateWithBackup(file);
      return opened(file, storage.open(file));
    } catch (PersistenceException e) {
      return new Attempt(OpenFailures.migrationFailed(e), null);
    }
  }

  private static Attempt opened(Path file, Database opened) {
    return new Attempt(new OpenResult.Opened(file), opened);
  }

  /** Закрывает БД без результата: сбой пишется в журнал, остановка продолжается. */
  private void closeQuietly(Database opened) {
    try {
      storage.close(opened);
    } catch (VirtualMachineError e) {
      throw e;
    } catch (PersistenceException e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось закрыть БД", e);
    } catch (RuntimeException | Error e) {
      LOG.log(System.Logger.Level.ERROR, "Внутренняя ошибка при закрытии БД", e);
    }
  }
}
