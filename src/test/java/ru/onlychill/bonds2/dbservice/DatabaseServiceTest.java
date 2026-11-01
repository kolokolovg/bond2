// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static ru.onlychill.bonds2.dbservice.TestDatabases.backupsIn;
import static ru.onlychill.bonds2.dbservice.TestDatabases.isClosed;
import static ru.onlychill.bonds2.dbservice.TestDatabases.newDatabaseAt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.persistence.Database;
import ru.onlychill.bonds2.persistence.PersistenceException;

class DatabaseServiceTest {

  @TempDir Path dir;

  private final GatedStorage storage = new GatedStorage();
  private final DatabaseService service = new DatabaseService(storage);

  @AfterEach
  void stopService() {
    storage.openGate.release();
    storage.migrateGate.release();
    service.shutdown(Duration.ofSeconds(30));
  }

  private Path dataDir() {
    return dir.resolve("data");
  }

  private Path file() {
    return dir.resolve("data/bonds.db");
  }

  private static <T> T get(CompletableFuture<T> future) {
    // Дефолтный бюджет меньше pitest-пола таймаута (~3 с): зависший на мутанте тест
    // падает сам как обычный failure, не вынуждая pitest перезапускать миньона
    return get(future, Duration.ofSeconds(2));
  }

  private static <T> T get(CompletableFuture<T> future, Duration budget) {
    try {
      return future.get(budget.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<DbState> record() {
    return record(service);
  }

  private List<DbState> record(DatabaseService target) {
    var states = new CopyOnWriteArrayList<DbState>();
    target
        .states()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(DbState item) {
                states.add(item);
              }

              @Override
              public void onError(Throwable error) {}

              @Override
              public void onComplete() {}
            });
    return states;
  }

  /** БД действительно закрыта: новая транзакция по держателю отклоняется с точной причиной. */
  private static void assertReallyClosed(Database database) {
    assertThatThrownBy(() -> database.inTransaction(connection -> null))
        .isInstanceOf(PersistenceException.class)
        .hasMessage("БД закрыта");
  }

  /** Подписчик, бросающий заданную ошибку среды только при доставке состояния «открыта». */
  private static Flow.Subscriber<DbState> failingOnOpenState(Error error) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(DbState item) {
        if (item instanceof DbState.Open) {
          throw error;
        }
      }

      @Override
      public void onError(Throwable throwable) {}

      @Override
      public void onComplete() {}
    };
  }

  /** Подписчик, бросающий заданную ошибку среды только при доставке состояния «закрыта». */
  private static Flow.Subscriber<DbState> failingOnClosedState(Error error) {
    return new Flow.Subscriber<>() {
      @Override
      public void onSubscribe(Flow.Subscription subscription) {
        subscription.request(Long.MAX_VALUE);
      }

      @Override
      public void onNext(DbState item) {
        if (item instanceof DbState.Closed) {
          throw error;
        }
      }

      @Override
      public void onError(Throwable throwable) {}

      @Override
      public void onComplete() {}
    };
  }

  @Test
  void lifecycleRunsWithoutUserInterface() {
    var opened = get(service.create(file()));
    assertThat(opened).isEqualTo(new OpenResult.Opened(file()));
    assertThat(service.state()).isEqualTo(new DbState.Open(file()));

    assertThat(get(service.close())).isEqualTo(new CloseResult.Closed(null));
    assertThat(service.state()).isEqualTo(DbState.CLOSED);

    assertThat(get(service.open(file(), false))).isEqualTo(new OpenResult.Opened(file()));
    assertThat(get(service.close())).isEqualTo(new CloseResult.Closed(null));
    assertThat(file()).isRegularFile();
  }

  @Test
  void operationsRunOnSingleBackgroundThread() {
    Thread caller = Thread.currentThread();
    get(service.create(file()));
    get(service.close());
    get(service.open(file(), false));
    get(service.close());

    // Все операции выполнились на одном и том же фоновом потоке, не совпадающем с потоком теста.
    // Имя потока («bonds2-db») и признак демона остаются в производственном коде как диагностика
    // и в тесте сознательно не проверяются.
    assertThat(storage.threads).isNotEmpty();
    assertThat(storage.threads).containsOnly(storage.threads.get(0));
    assertThat(storage.threads).doesNotContain(caller);
  }

  @Test
  void publishesCurrentStateOnSubscribeThenChangesInOrder() {
    var states = record();
    assertThat(states).containsExactly(DbState.CLOSED);

    get(service.create(file()));
    get(service.close());

    assertThat(Eventually.holds(() -> states.size() == 4)).isTrue();
    assertThat(states)
        .containsExactly(
            DbState.CLOSED,
            new DbState.Opening(DbState.Kind.CREATE, file()),
            new DbState.Open(file()),
            DbState.CLOSED);
  }

  @Test
  void lateSubscriberGetsCurrentStateFirst() {
    get(service.create(file()));

    var states = record();

    assertThat(states).containsExactly(new DbState.Open(file()));
  }

  @Test
  void operationsRunOneAtATimeInOrderOfRequests() throws InterruptedException {
    newDatabaseAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var close = service.close();

    assertThat(close).isNotDone();
    assertThat(service.state()).isEqualTo(new DbState.Opening(DbState.Kind.OPEN, file()));

    storage.openGate.release();
    assertThat(get(open)).isEqualTo(new OpenResult.Opened(file()));
    assertThat(get(close)).isEqualTo(new CloseResult.Closed(null));
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void invalidOperationsAreRejectedWithoutChangingStateOrFile() throws IOException {
    // Неверное состояние даёт типизированную причину STATE, пользовательский текст сохраняется
    assertThat(get(service.close()))
        .isInstanceOfSatisfying(
            CloseResult.Rejected.class,
            rejected -> {
              assertThat(rejected.cause()).isEqualTo(RejectionCause.STATE);
              assertThat(rejected.message()).contains("недопустима в текущем состоянии");
            });
    assertThat(get(service.migrate()))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> {
              assertThat(rejected.cause()).isEqualTo(RejectionCause.STATE);
              assertThat(rejected.message()).contains("недопустима в текущем состоянии");
            });
    assertThat(get(service.declineMigration()))
        .isInstanceOfSatisfying(
            CloseResult.Rejected.class,
            rejected -> {
              assertThat(rejected.cause()).isEqualTo(RejectionCause.STATE);
              assertThat(rejected.message()).contains("недопустима в текущем состоянии");
            });
    assertThat(service.state()).isEqualTo(DbState.CLOSED);

    get(service.create(file()));
    byte[] before = Files.readAllBytes(file());
    assertThat(get(service.open(file(), false)))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> {
              assertThat(rejected.cause()).isEqualTo(RejectionCause.STATE);
              assertThat(rejected.message()).contains("недопустима в текущем состоянии");
            });
    assertThat(get(service.create(dir.resolve("other.db"))))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> assertThat(rejected.cause()).isEqualTo(RejectionCause.STATE));
    assertThat(get(service.migrate()))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> assertThat(rejected.cause()).isEqualTo(RejectionCause.STATE));

    assertThat(service.state()).isEqualTo(new DbState.Open(file()));
    assertThat(Files.readAllBytes(file())).isEqualTo(before);
    assertThat(dir.resolve("other.db")).doesNotExist();
  }

  @Test
  void createOverExistingFileIsRejectedAndFileKept() throws IOException {
    Files.createDirectories(dataDir());
    Files.writeString(file(), "чужой файл");
    byte[] before = Files.readAllBytes(file());

    var result = get(service.create(file()));

    // Причина «файл существует» типизирована, текст и неизменность файла сохраняются
    assertThat(result)
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> {
              assertThat(rejected.cause()).isEqualTo(RejectionCause.FILE_EXISTS);
              assertThat(rejected.message()).contains("уже существует");
            });
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(Files.readAllBytes(file())).isEqualTo(before);
  }

  @Test
  void missingFileIsFailureUnlessCreationIsAllowed() {
    var failure = get(service.open(file(), false));

    assertThat(failure).isInstanceOf(OpenResult.Failed.class);
    assertThat(((OpenResult.Failed) failure).reason()).isEqualTo("файл не найден");
    assertThat(((OpenResult.Failed) failure).message()).contains("Создать базу");
    assertThat(service.state())
        .isEqualTo(new DbState.Closed("файл не найден", ((OpenResult.Failed) failure).message()));
    assertThat(file()).doesNotExist();

    assertThat(get(service.open(file(), true))).isEqualTo(new OpenResult.Opened(file()));
    assertThat(file()).isRegularFile();
  }

  @Test
  void outdatedSchemaWaitsForDecisionWithoutChangingFile() throws SQLException, IOException {
    TestDatabases.outdatedAt(file());
    byte[] before = Files.readAllBytes(file());

    var result = get(service.open(file(), false));

    assertThat(result).isEqualTo(new OpenResult.NeedsMigration(file()));
    assertThat(service.state()).isEqualTo(new DbState.AwaitingMigration(file()));
    assertThat(Files.readAllBytes(file())).isEqualTo(before);
    assertThat(backupsIn(dataDir())).isEmpty();
  }

  @Test
  void migrateCommandMigratesWithBackupAndOpens() throws SQLException, IOException {
    TestDatabases.outdatedAt(file());
    get(service.open(file(), false));
    var states = record();

    var result = get(service.migrate());

    assertThat(result).isEqualTo(new OpenResult.Opened(file()));
    assertThat(service.state()).isEqualTo(new DbState.Open(file()));
    assertThat(backupsIn(dataDir())).hasSize(1);
    assertThat(Eventually.holds(() -> states.size() == 3)).isTrue();
    assertThat(states)
        .containsExactly(
            new DbState.AwaitingMigration(file()),
            new DbState.Migrating(file()),
            new DbState.Open(file()));
    get(service.close());
    assertThat(Database.inspect(file())).isEqualTo(Database.State.UP_TO_DATE);
  }

  @Test
  void declineCommandClosesWithOutdatedReasonAndKeepsFile() throws SQLException, IOException {
    TestDatabases.outdatedAt(file());
    byte[] before = Files.readAllBytes(file());
    get(service.open(file(), false));

    var result = get(service.declineMigration());

    assertThat(result).isEqualTo(new CloseResult.Closed(null));
    assertThat(service.state()).isEqualTo(new DbState.Closed("схема устарела", null));
    assertThat(Files.readAllBytes(file())).isEqualTo(before);
    assertThat(backupsIn(dataDir())).isEmpty();
  }

  @Test
  void failedMigrationRestoresFileAndClosesWithReason() throws SQLException, IOException {
    TestDatabases.forgottenHistoryAt(file());
    // Файл с «забытой» историей считается устаревшим: миграция падает и восстанавливает файл
    get(service.open(file(), false));

    var result = get(service.migrate());

    assertThat(result).isInstanceOf(OpenResult.Failed.class);
    var failure = (OpenResult.Failed) result;
    assertThat(failure.reason()).isEqualTo("ошибка миграции");
    assertThat(failure.message()).contains("восстановлена").contains(".bak-");
    assertThat(service.state()).isEqualTo(new DbState.Closed(failure.reason(), failure.message()));
    // Файл восстановлен копией: VACUUM INTO пишет компактный файл, поэтому сравниваем с копией
    List<String> backups = backupsIn(dataDir());
    assertThat(backups).hasSize(1);
    assertThat(Files.readAllBytes(file())).isEqualTo(Files.readAllBytes(Path.of(backups.get(0))));
  }

  @Test
  void busyDatabaseMigrationIsClassifiedAsBusy() throws SQLException {
    // Сценарий «БД занята» на настоящей БД: постороннее соединение держит незавершённую транзакцию
    // записи, миграция ждёт busy_timeout драйвера и получает SQLITE_BUSY
    TestDatabases.outdatedAt(file());
    get(service.open(file(), false));

    try (Connection blocker = DriverManager.getConnection("jdbc:sqlite:" + file())) {
      blocker.setAutoCommit(false);
      try (Statement statement = blocker.createStatement()) {
        statement.execute("CREATE TABLE busy_lock (id INTEGER)");
      }

      // Реальное ожидание busy-timeout SQLite при миграции — щедрый явный бюджет
      var result = get(service.migrate(), Duration.ofSeconds(30));

      assertThat(result).isInstanceOf(OpenResult.Failed.class);
      var failure = (OpenResult.Failed) result;
      assertThat(failure.reason()).isEqualTo(OpenFailures.REASON_BUSY);
      assertThat(failure.message()).contains("занята").contains("Повторите").contains("позже");
      assertThat(service.state())
          .isEqualTo(new DbState.Closed(failure.reason(), failure.message()));
    }
  }

  @Test
  void foreignNewerAndCorruptFilesAreRejectedWithoutChanges() throws SQLException, IOException {
    TestDatabases.foreignAt(file());
    byte[] foreign = Files.readAllBytes(file());
    var result = (OpenResult.Failed) get(service.open(file(), false));
    assertThat(result.reason()).isEqualTo("не база bonds2");
    assertThat(result.message()).contains("не является базой данных bonds2");
    assertThat(Files.readAllBytes(file())).isEqualTo(foreign);

    Path newer = dir.resolve("newer.db");
    TestDatabases.tooNewAt(newer);
    byte[] newerBytes = Files.readAllBytes(newer);
    result = (OpenResult.Failed) get(service.open(newer, false));
    assertThat(result.reason()).isEqualTo("схема новее приложения");
    assertThat(result.message()).contains("новее");
    assertThat(Files.readAllBytes(newer)).isEqualTo(newerBytes);

    Path corrupt = dir.resolve("corrupt.db");
    Files.writeString(
        corrupt,
        "это обычный текст, а не база данных SQLite, поэтому открыть его нельзя",
        StandardCharsets.UTF_8);
    byte[] corruptBytes = Files.readAllBytes(corrupt);
    result = (OpenResult.Failed) get(service.open(corrupt, false));
    assertThat(result.reason()).isEqualTo("ошибка открытия");
    assertThat(Files.readAllBytes(corrupt)).isEqualTo(corruptBytes);
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);
  }

  @Test
  void emptyFileIsInitializedWithoutMigrationOffer() throws IOException {
    Files.createDirectories(dataDir());
    Files.createFile(file());

    assertThat(get(service.open(file(), false))).isEqualTo(new OpenResult.Opened(file()));
    get(service.close());

    assertThat(Database.inspect(file())).isEqualTo(Database.State.UP_TO_DATE);
  }

  @Test
  void creationFailureClosesWithCreateReason() throws IOException {
    Files.writeString(dir.resolve("blocker"), "файл на месте каталога");

    var result = get(service.create(dir.resolve("blocker/bonds.db")));

    assertThat(result).isInstanceOf(OpenResult.Failed.class);
    assertThat(((OpenResult.Failed) result).reason()).isEqualTo("ошибка создания");
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);
  }

  @Test
  void unexpectedExceptionKeepsServiceAliveAndReportsInternalError() {
    newDatabaseAt(file());
    storage.openFailure = new IllegalStateException("сбой");

    var result = get(service.open(file(), false));

    assertThat(result).isInstanceOf(OpenResult.Failed.class);
    var failure = (OpenResult.Failed) result;
    assertThat(failure.reason()).isEqualTo("внутренняя ошибка");
    assertThat(failure.message()).contains("сбой");
    assertThat(service.state()).isEqualTo(new DbState.Closed(failure.reason(), failure.message()));

    storage.openFailure = null;
    assertThat(get(service.open(file(), false))).isEqualTo(new OpenResult.Opened(file()));
  }

  @Test
  void linkageErrorDuringOpenClosesServiceAndNextOpenIsAccepted() {
    newDatabaseAt(file());
    var states = record();
    // Например, не загрузилась встроенная библиотека SQLite: это ошибка среды, а не исключение
    storage.openFailure = new UnsatisfiedLinkError("библиотека SQLite не загружена");

    var result = get(service.open(file(), false));

    assertThat(result).isInstanceOf(OpenResult.Failed.class);
    var failure = (OpenResult.Failed) result;
    assertThat(failure.reason()).isEqualTo("внутренняя ошибка");
    assertThat(failure.message()).contains("библиотека SQLite не загружена");
    // Состояние не застревает в «открывается»: публикуется «закрыта» с причиной
    assertThat(states)
        .containsExactly(
            DbState.CLOSED,
            new DbState.Opening(DbState.Kind.OPEN, file()),
            new DbState.Closed(failure.reason(), failure.message()));

    storage.openFailure = null;
    assertThat(get(service.open(file(), false))).isEqualTo(new OpenResult.Opened(file()));
    assertThat(service.state()).isEqualTo(new DbState.Open(file()));
  }

  @Test
  void virtualMachineErrorIsNotSwallowedByOpen() {
    newDatabaseAt(file());
    storage.openFailure = new OutOfMemoryError("тест: нехватка памяти");

    var future = service.open(file(), false);

    assertThatThrownBy(() -> future.get(30, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(OutOfMemoryError.class);
    // Фатальная ошибка передана вызывающему, промежуточное состояние не осталось
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);
    assertThat(((DbState.Closed) service.state()).reason()).isEqualTo("внутренняя ошибка");
  }

  @Test
  void unexpectedErrorDuringCloseClosesServiceAndReturnsFailure() {
    newDatabaseAt(file());
    get(service.open(file(), false));
    var states = record();
    storage.closeFailure = new UnsatisfiedLinkError("сбой закрытия");

    var result = get(service.close());

    assertThat(result).isInstanceOf(CloseResult.Failed.class);
    var message = ((CloseResult.Failed) result).message();
    assertThat(message).contains("сбой закрытия");
    assertThat(service.state()).isEqualTo(new DbState.Closed("внутренняя ошибка", message));
    assertThat(states)
        .containsExactly(
            new DbState.Open(file()), new DbState.Closed("внутренняя ошибка", message));
    assertThat(isClosed(storage.opened.get(0))).isTrue();

    // Сервис остаётся работоспособным: после сбоя БД снова открывается и закрывается
    storage.closeFailure = null;
    assertThat(get(service.open(file(), false))).isEqualTo(new OpenResult.Opened(file()));
    assertThat(get(service.close())).isEqualTo(new CloseResult.Closed(null));
  }

  @Test
  void virtualMachineErrorIsNotSwallowedByClose() {
    newDatabaseAt(file());
    get(service.open(file(), false));
    storage.closeFailure = new OutOfMemoryError("тест: нехватка памяти");

    var future = service.close();

    assertThatThrownBy(() -> future.get(30, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(OutOfMemoryError.class);
  }

  @Test
  void internalCloseErrorRetriesQuietlyAndDatabaseIsReallyClosed() {
    var failingStorage = new FailingCloseStorage();
    failingStorage.failFirstClose = true;
    var failingService = new DatabaseService(failingStorage);
    assertThat(get(failingService.create(file()))).isEqualTo(new OpenResult.Opened(file()));
    var states = record(failingService);
    Database opened = failingStorage.opened.get(0);

    var result = get(failingService.close());

    // Вызывающему возвращена ошибка, состояние — «закрыта» с причиной «внутренняя ошибка»
    assertThat(result).isInstanceOf(CloseResult.Failed.class);
    var message = ((CloseResult.Failed) result).message();
    assertThat(message).contains("сбой закрытия без пометки закрытой");
    assertThat(failingService.state()).isEqualTo(new DbState.Closed("внутренняя ошибка", message));
    assertThat(states)
        .containsExactly(
            new DbState.Open(file()), new DbState.Closed("внутренняя ошибка", message));
    // Тихий повтор закрыл БД по-настоящему: второй вызов close, транзакции больше не проходят
    assertThat(failingStorage.closeAttempts).hasValue(2);
    assertReallyClosed(opened);

    failingStorage.failFirstClose = false;
    assertThat(failingService.shutdown(Duration.ofSeconds(30))).isEqualTo(ShutdownResult.STOPPED);
  }

  @Test
  void reopenAfterInternalCloseErrorSucceeds() {
    var failingStorage = new FailingCloseStorage();
    failingStorage.failFirstClose = true;
    var failingService = new DatabaseService(failingStorage);
    assertThat(get(failingService.create(file()))).isEqualTo(new OpenResult.Opened(file()));
    Database opened = failingStorage.opened.get(0);
    assertThat(get(failingService.close())).isInstanceOf(CloseResult.Failed.class);
    assertReallyClosed(opened);

    // Повторное открытие того же файла после сбойного закрытия выполняется успешно
    failingStorage.failFirstClose = false;
    assertThat(get(failingService.open(file(), false))).isEqualTo(new OpenResult.Opened(file()));
    assertThat(failingService.state()).isEqualTo(new DbState.Open(file()));
    assertThat(get(failingService.close())).isEqualTo(new CloseResult.Closed(null));
    assertThat(failingService.state()).isEqualTo(DbState.CLOSED);

    assertThat(failingService.shutdown(Duration.ofSeconds(30))).isEqualTo(ShutdownResult.STOPPED);
  }

  @Test
  void subscriberErrorDoesNotLeaveIntermediateStateAndServiceKeepsWorking() {
    newDatabaseAt(file());
    var states = record();
    // Сбойный подписчик бросает ошибку среды при доставке промежуточного состояния:
    // первое delivery — текущее состояние, второе — «открывается»
    var delivered = new AtomicInteger();
    service
        .states()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(DbState item) {
                if (delivered.incrementAndGet() > 1) {
                  throw new UnsatisfiedLinkError("сбой подписчика");
                }
              }

              @Override
              public void onError(Throwable error) {}

              @Override
              public void onComplete() {}
            });

    var open = service.open(file(), false);

    assertThatThrownBy(() -> open.get(30, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(UnsatisfiedLinkError.class);

    // Промежуточное состояние не осталось: опубликовано «закрыта» с причиной «внутренняя ошибка»
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);
    var closed = (DbState.Closed) service.state();
    assertThat(closed.reason()).isEqualTo("внутренняя ошибка");
    assertThat(closed.message()).contains("сбой подписчика");
    assertThat(states)
        .containsExactly(
            DbState.CLOSED,
            new DbState.Opening(DbState.Kind.OPEN, file()),
            new DbState.Closed(closed.reason(), closed.message()));

    // Сервис продолжает принимать операции
    assertThat(get(service.open(file(), false))).isEqualTo(new OpenResult.Opened(file()));
  }

  @Test
  void subscriberErrorOnOpenStateClosesDatabaseQuietlyAndServiceKeepsWorking() {
    newDatabaseAt(file());
    var states = record();
    service
        .states()
        .subscribe(failingOnOpenState(new AssertionError("сбой подписчика на «открыта»")));

    var open = service.open(file(), false);

    // Ошибка доходит до вызывающего исключительно: будущее не завершается успехом
    assertThatThrownBy(() -> open.get(30, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(AssertionError.class);

    // Полученная БД закрыта тихо, опубликовано «закрыта (внутренняя ошибка)»
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);
    var closed = (DbState.Closed) service.state();
    assertThat(closed.reason()).isEqualTo("внутренняя ошибка");
    assertThat(closed.message()).contains("сбой подписчика");
    assertThat(states)
        .containsExactly(
            DbState.CLOSED,
            new DbState.Opening(DbState.Kind.OPEN, file()),
            new DbState.Open(file()),
            new DbState.Closed(closed.reason(), closed.message()));
    assertReallyClosed(storage.opened.get(0));

    // Последующие открытие и закрытие того же файла работают
    assertThat(get(service.open(file(), false))).isEqualTo(new OpenResult.Opened(file()));
    assertThat(get(service.close())).isEqualTo(new CloseResult.Closed(null));
  }

  @Test
  void virtualMachineErrorFromSubscriberOnOpenIsPropagatedToCaller() {
    newDatabaseAt(file());
    service
        .states()
        .subscribe(failingOnOpenState(new StackOverflowError("тест: переполнение стека")));

    var open = service.open(file(), false);

    // Фатальная ошибка виртуальной машины не поглощается и доходит до вызывающего
    assertThatThrownBy(() -> open.get(30, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(StackOverflowError.class);
  }

  @Test
  void closeFailureIsReportedAsWarningAndDatabaseIsClosed() throws SQLException {
    newDatabaseAt(file());
    get(service.open(file(), false));
    var results = new CopyOnWriteArrayList<CloseResult>();

    // Реальное ожидание busy-timeout SQLite при закрытии — щедрый явный бюджет
    TestDatabases.whileReaderLagsBehind(
        file(), () -> results.add(get(service.close(), Duration.ofSeconds(10))));

    assertThat(results).hasSize(1);
    assertThat(results.get(0)).isInstanceOf(CloseResult.Closed.class);
    assertThat(((CloseResult.Closed) results.get(0)).warning())
        .contains("Не удалось полностью закрыть БД");
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void closeAfterFileDeletionPublishesClosedWithWarningAndCreatesNoFile() throws IOException {
    newDatabaseAt(file());
    get(service.open(file(), false));
    var states = record();
    Files.delete(file());

    var result = get(service.close());

    assertThat(result).isInstanceOf(CloseResult.Closed.class);
    assertThat(((CloseResult.Closed) result).warning()).contains("Не удалось полностью закрыть БД");
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(states).containsExactly(new DbState.Open(file()), DbState.CLOSED);
    assertThat(file()).doesNotExist();
  }

  @Test
  void shutdownCompletesEvenIfClosingDatabaseFailsWithError() {
    get(service.create(file()));
    storage.closeFailure = new UnsatisfiedLinkError("сбой закрытия при остановке");

    assertThat(service.shutdown(Duration.ofSeconds(30))).isEqualTo(ShutdownResult.STOPPED);

    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(get(service.open(file(), false)))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected ->
                // После остановки операции отклоняются с причиной STOPPED
                assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED));
  }

  @Test
  void shutdownClosesOpenDatabaseAndRejectsNewOperations() {
    get(service.create(file()));
    var database = storage.opened.get(0);

    assertThat(service.shutdown(Duration.ofSeconds(30))).isEqualTo(ShutdownResult.STOPPED);

    assertThat(isClosed(database)).isTrue();
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(get(service.open(file(), false)))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected ->
                // После остановки операции отклоняются с причиной STOPPED
                assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED));
    assertThat(get(service.close()))
        .isInstanceOfSatisfying(
            CloseResult.Rejected.class,
            rejected ->
                // После остановки операции отклоняются с причиной STOPPED
                assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED));
    assertThat(service.shutdown(Duration.ofSeconds(1))).isEqualTo(ShutdownResult.STOPPED);
  }

  @Test
  void shutdownCompletesSubscriptions() {
    var completed = new CompletableFuture<Boolean>();
    service
        .states()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
              }

              @Override
              public void onNext(DbState item) {}

              @Override
              public void onError(Throwable error) {}

              @Override
              public void onComplete() {
                completed.complete(true);
              }
            });

    service.shutdown(Duration.ofSeconds(30));

    assertThat(completed).isCompletedWithValue(true);
  }

  @Test
  void subscribingAfterShutdownCompletesAtOnce() {
    service.shutdown(Duration.ofSeconds(30));
    var events = new CopyOnWriteArrayList<String>();

    service
        .states()
        .subscribe(
            new Flow.Subscriber<>() {
              @Override
              public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(1);
              }

              @Override
              public void onNext(DbState item) {
                events.add("next");
              }

              @Override
              public void onError(Throwable error) {}

              @Override
              public void onComplete() {
                events.add("complete");
              }
            });

    assertThat(events).containsExactly("next", "complete");
  }

  @Test
  void shutdownDuringOpeningWithinTimeoutClosesResultingDatabase() throws InterruptedException {
    newDatabaseAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var releaser = new Thread(() -> release(storage.openGate));
    releaser.start();

    var result = service.shutdown(Duration.ofSeconds(30));
    releaser.join();

    assertThat(result).isEqualTo(ShutdownResult.STOPPED);
    assertThat(get(open)).isEqualTo(new OpenResult.Opened(file()));
    assertThat(storage.opened).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void shutdownAsyncDoesNotBlockCallerAndCompletesAfterOperation() throws InterruptedException {
    newDatabaseAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();

    var stopping = service.shutdownAsync(Duration.ofSeconds(30));

    assertThat(stopping).isNotDone();
    storage.openGate.release();
    assertThat(get(stopping)).isEqualTo(ShutdownResult.STOPPED);
    assertThat(get(open)).isEqualTo(new OpenResult.Opened(file()));
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  @Test
  void operationSubmittedAfterStopBeganIsRejectedWithStoppedCause() throws InterruptedException {
    // Операция, поставленная после начала остановки, отклоняется сразу с типизированной причиной
    // STOPPED и каноническим текстом
    newDatabaseAt(file());
    storage.openGate.hold();
    service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    service.shutdownAsync(Duration.ofSeconds(30));

    assertThat(get(service.create(file())))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> {
              assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED);
              assertThat(rejected.message()).isEqualTo("Сервис БД остановлен.");
            });

    storage.openGate.release();
  }

  @Test
  void shutdownAsyncTimesOutAndLateDatabaseIsClosed() throws InterruptedException {
    newDatabaseAt(file());
    storage.openGate.hold();
    service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();

    var stopping = service.shutdownAsync(Duration.ofMillis(50));

    assertThat(get(stopping)).isEqualTo(ShutdownResult.TIMED_OUT);
    storage.openGate.release();
    assertThat(
            Eventually.holds(() -> storage.opened.size() == 1 && isClosed(storage.opened.get(0))))
        .isTrue();
  }

  @Test
  void shutdownAsyncIsIdempotent() {
    var first = service.shutdownAsync(Duration.ofSeconds(30));
    var second = service.shutdownAsync(Duration.ofSeconds(30));

    assertThat(get(first)).isEqualTo(ShutdownResult.STOPPED);
    assertThat(get(second)).isEqualTo(ShutdownResult.STOPPED);
    assertThat(get(service.shutdownAsync(Duration.ofSeconds(1)))).isEqualTo(ShutdownResult.STOPPED);
  }

  @Test
  void shutdownAsyncIsRefusedWhileAwaitingMigrationDecision() throws SQLException {
    TestDatabases.outdatedAt(file());
    get(service.open(file(), false));

    assertThat(get(service.shutdownAsync(Duration.ofSeconds(1))))
        .isEqualTo(new ShutdownResult.Refused(ShutdownRefusal.DECISION_PENDING));
  }

  @Test
  void nonPositiveTimeoutIsRejectedWithoutStartingStop() {
    // Сценарий «Нулевой срок»: ноль и отрицательный срок — ошибка программиста, отвергается
    // до любых действий
    assertThatThrownBy(() -> service.shutdown(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("положительн");
    assertThatThrownBy(() -> service.shutdownAsync(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.shutdown(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.shutdownAsync(Duration.ofMillis(-5)))
        .isInstanceOf(IllegalArgumentException.class);

    // Состояние не изменилось, остановка не началась: сервис работает как до отвергнутых вызовов
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(get(service.open(file(), true))).isEqualTo(new OpenResult.Opened(file()));
    assertThat(get(service.close())).isEqualTo(new CloseResult.Closed(null));
    assertThat(file()).isRegularFile();
  }

  @Test
  void shutdownAfterRejectedTimeoutProceedsNormally() {
    // Сценарий «Корректный вызов после отвергнутого»: после отказа остановка с положительным
    // сроком выполняется по общим правилам
    assertThatThrownBy(() -> service.shutdownAsync(Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.shutdown(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);

    // Остановка не началась: БД открывается, а остановка завершается как обычно
    get(service.create(file()));
    assertThat(service.shutdown(Duration.ofSeconds(30))).isEqualTo(ShutdownResult.STOPPED);
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  private static void release(GatedStorage.Gate gate) {
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    gate.release();
  }

  @Test
  void lateOperationAfterTimeoutClosesItsDatabaseWithoutPublishing() throws InterruptedException {
    newDatabaseAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var states = record();

    var result =
        assertTimeoutPreemptively(
            Duration.ofSeconds(10), () -> service.shutdown(Duration.ofMillis(50)));
    assertThat(result).isEqualTo(ShutdownResult.TIMED_OUT);
    storage.openGate.release();

    assertThat(Eventually.holds(open::isDone)).isTrue();
    assertThat(get(open))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> {
              // Запоздавший итог покинутой операции — отклонение остановленного сервиса
              assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED);
              assertThat(rejected.message()).isEqualTo("Сервис БД остановлен.");
            });
    assertThat(
            Eventually.holds(() -> storage.opened.size() == 1 && isClosed(storage.opened.get(0))))
        .isTrue();
    // Результат операции не публикуется, но остановка завершается согласованно: «закрыта»
    assertThat(Eventually.holds(() -> states.size() == 2)).isTrue();
    assertThat(states)
        .containsExactly(new DbState.Opening(DbState.Kind.OPEN, file()), DbState.CLOSED);
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void resultCompletingAtShutdownTimeoutIsNotPublishedAndStateHealsToClosed()
      throws InterruptedException {
    // Сценарий «Результат операции совпал с истечением срока»: детерминированный интерливинг —
    // операция удержана воротами, остановка с минимальным сроком истекает, затем операция
    // отпускается
    newDatabaseAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var states = record();

    assertThat(get(service.shutdownAsync(Duration.ofMillis(50))))
        .isEqualTo(ShutdownResult.TIMED_OUT);
    storage.openGate.release();

    // Результат запоздавшей операции не публикуется: вызывающему возвращается отклонение
    assertThat(get(open))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> {
              // Запоздавший итог покинутой операции — отклонение остановленного сервиса
              assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED);
              assertThat(rejected.message()).isEqualTo("Сервис БД остановлен.");
            });
    // Полученная БД закрывается тихо
    assertThat(
            Eventually.holds(() -> storage.opened.size() == 1 && isClosed(storage.opened.get(0))))
        .isTrue();
    // Самолечение остановки: итоговое состояние — «закрыта», и у подписчиков тоже
    assertThat(Eventually.holds(() -> service.state().equals(DbState.CLOSED))).isTrue();
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(Eventually.holds(() -> states.size() == 2)).isTrue();
    assertThat(states)
        .containsExactly(new DbState.Opening(DbState.Kind.OPEN, file()), DbState.CLOSED);
  }

  @Test
  void shutdownDoesNotDuplicateClosedStateWhenItIsAlreadyClosed() {
    var states = record();
    // Открытие отсутствующего файла публикует «закрыта» с причиной: последнее состояние закрыто
    var failure = (OpenResult.Failed) get(service.open(file(), false));
    assertThat(service.state()).isEqualTo(new DbState.Closed(failure.reason(), failure.message()));

    assertThat(service.shutdown(Duration.ofSeconds(30))).isEqualTo(ShutdownResult.STOPPED);

    // Самолечение не добавляет дубликат «закрыта»: подписчики видят ровно то, что опубликовано
    assertThat(states)
        .containsExactly(
            DbState.CLOSED,
            new DbState.Opening(DbState.Kind.OPEN, file()),
            new DbState.Closed(failure.reason(), failure.message()));
  }

  @Test
  void shutdownIsRefusedDuringMigrationAndAllowedAfterIt()
      throws SQLException, InterruptedException {
    TestDatabases.outdatedAt(file());
    get(service.open(file(), false));
    storage.migrateGate.hold();
    var migrate = service.migrate();
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    assertThat(service.shutdown(Duration.ofSeconds(1)))
        .isEqualTo(new ShutdownResult.Refused(ShutdownRefusal.MIGRATING));
    assertThat(migrate).isNotDone();

    storage.migrateGate.release();
    assertThat(get(migrate)).isEqualTo(new OpenResult.Opened(file()));
    assertThat(service.shutdown(Duration.ofSeconds(30))).isEqualTo(ShutdownResult.STOPPED);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  @Test
  void shutdownIsRefusedWhileAwaitingMigrationDecision() throws SQLException {
    TestDatabases.outdatedAt(file());
    get(service.open(file(), false));

    assertThat(service.shutdown(Duration.ofSeconds(1)))
        .isEqualTo(new ShutdownResult.Refused(ShutdownRefusal.DECISION_PENDING));

    assertThat(service.state()).isEqualTo(new DbState.AwaitingMigration(file()));
    assertThat(get(service.declineMigration())).isEqualTo(new CloseResult.Closed(null));
    assertThat(service.shutdown(Duration.ofSeconds(30))).isEqualTo(ShutdownResult.STOPPED);
  }

  @Test
  void shutdownTurnedIntoRefusalWhileQueuedKeepsServiceWorking()
      throws SQLException, InterruptedException {
    TestDatabases.outdatedAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var releaser = new Thread(() -> release(storage.openGate));
    releaser.start();

    // Открытие закончится ожиданием решения о миграции: поставленная в очередь остановка отказывает
    var result = service.shutdown(Duration.ofSeconds(30));
    releaser.join();

    assertThat(result).isEqualTo(new ShutdownResult.Refused(ShutdownRefusal.DECISION_PENDING));
    assertThat(get(open)).isEqualTo(new OpenResult.NeedsMigration(file()));
    assertThat(get(service.migrate())).isEqualTo(new OpenResult.Opened(file()));
  }

  @Test
  void serviceIsFullyFunctionalAfterShutdownRefusal() throws Exception {
    // Сценарий дельты «Возврат к работе после отказа за истечением срока»: отказ остановки
    // возвращает сервис к работе целиком — «покинутость» попытки не переживает отказ.
    // Гонку «истечение срока против отказа» прямо воспроизвести нельзя, поэтому проверяется
    // её следствие по спецификации: после отказа публикуются и результаты, и ошибки,
    // повторная остановка выполняется штатно
    TestDatabases.outdatedAt(file());
    assertThat(get(service.open(file(), false))).isEqualTo(new OpenResult.NeedsMigration(file()));
    assertThat(get(service.shutdownAsync(Duration.ofSeconds(1))))
        .isEqualTo(new ShutdownResult.Refused(ShutdownRefusal.DECISION_PENDING));

    // Отказ от миграции публикует закрытие с причиной — публикация не подавлена
    assertThat(get(service.declineMigration())).isEqualTo(new CloseResult.Closed(null));
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);

    // Ошибка последующей операции видима вызывающему, а не молча проглочена
    assertThat(get(service.open(Path.of("несуществующий-каталог/базы/нет.db"), false)))
        .isInstanceOf(OpenResult.Failed.class);
    assertThat(service.state()).isInstanceOf(DbState.Closed.class);

    // Повторная остановка выполняется штатно
    assertThat(service.shutdown(Duration.ofSeconds(30))).isEqualTo(ShutdownResult.STOPPED);
  }

  @Test
  void operationRacingShutdownNeverSucceedsAfterStopCompletes() throws Exception {
    // Стресс: постановка операции и начало остановки конкурентны; ни одна операция не
    // возвращает успех после завершения остановки STOPPED
    for (int attempt = 0; attempt < 50; attempt++) {
      GatedStorage racingStorage = new GatedStorage();
      DatabaseService racing = new DatabaseService(racingStorage);
      try {
        var open = new AtomicReference<CompletableFuture<OpenResult>>();
        var publishedBeforeStop = new AtomicBoolean();
        var opener =
            new Thread(
                () -> {
                  var future = racing.open(file(), false);
                  open.set(future);
                });
        opener.start();
        var shutdown = racing.shutdownAsync(Duration.ofSeconds(30));
        // Момент публикации STOPPED: результат принятой операции обязан быть опубликован
        // раньше. Порядок выполнения внешних слушателей ненадёжен — проверяем саму
        // будущность операции. Ссылки ещё нет — операция не была принята до остановки.
        shutdown.whenComplete(
            (result, error) -> {
              CompletableFuture<OpenResult> future = open.get();
              publishedBeforeStop.set(future == null || future.isDone());
            });
        opener.join();

        assertThat(get(shutdown)).isEqualTo(ShutdownResult.STOPPED);
        var result = get(open.get());
        // Успех (в том числе «файл не найден») допустим, только если результат операции
        // опубликован до STOPPED; после STOPPED — исключительно отклонение
        if (!(result instanceof OpenResult.Rejected)) {
          assertThat(publishedBeforeStop.get())
              .as("попытка %d: успех опубликован после STOPPED", attempt)
              .isTrue();
        }
      } finally {
        racing.shutdown(Duration.ofSeconds(30));
      }
    }
  }

  @Test
  void lateNeedsMigrationAfterStopTimeoutIsNotPublishedAndOpensNothing()
      throws SQLException, InterruptedException {
    TestDatabases.outdatedAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var states = record();

    assertThat(get(service.shutdownAsync(Duration.ofMillis(50))))
        .isEqualTo(ShutdownResult.TIMED_OUT);
    storage.openGate.release();

    assertThat(get(open))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> {
              // Запоздавший итог покинутой операции — отклонение остановленного сервиса
              assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED);
              assertThat(rejected.message()).isEqualTo("Сервис БД остановлен.");
            });
    // «Ожидает решения о миграции» не публикуется, БД не открывается
    assertThat(storage.opened).isEmpty();
    // Состояния согласованы после остановки: самолечение публикует «закрыта»
    assertThat(Eventually.holds(() -> service.state().equals(DbState.CLOSED))).isTrue();
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(Eventually.holds(() -> states.size() == 2)).isTrue();
    assertThat(states)
        .containsExactly(new DbState.Opening(DbState.Kind.OPEN, file()), DbState.CLOSED);
  }

  @Test
  void lateFailureAfterStopTimeoutIsNotPublishedAndOpensNothing()
      throws SQLException, InterruptedException {
    TestDatabases.tooNewAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var states = record();

    assertThat(get(service.shutdownAsync(Duration.ofMillis(50))))
        .isEqualTo(ShutdownResult.TIMED_OUT);
    storage.openGate.release();

    assertThat(get(open))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> {
              // Запоздавший итог покинутой операции — отклонение остановленного сервиса
              assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED);
              assertThat(rejected.message()).isEqualTo("Сервис БД остановлен.");
            });
    // Состояние по ошибке не публикуется, БД не открывается
    assertThat(storage.opened).isEmpty();
    // Состояния согласованы после остановки: самолечение публикует «закрыта»
    assertThat(Eventually.holds(() -> service.state().equals(DbState.CLOSED))).isTrue();
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(Eventually.holds(() -> states.size() == 2)).isTrue();
    assertThat(states)
        .containsExactly(new DbState.Opening(DbState.Kind.OPEN, file()), DbState.CLOSED);
  }

  @Test
  void lateUnexpectedErrorAfterStopTimeoutIsNotPublished() throws Exception {
    newDatabaseAt(file());
    storage.openFailure = new IllegalStateException("сбой");
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var states = record();

    assertThat(get(service.shutdownAsync(Duration.ofMillis(50))))
        .isEqualTo(ShutdownResult.TIMED_OUT);
    storage.openGate.release();

    assertThat(get(open))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> {
              // Запоздавший итог покинутой операции — отклонение остановленного сервиса
              assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED);
              assertThat(rejected.message()).isEqualTo("Сервис БД остановлен.");
            });
    // Состояние «внутренняя ошибка» от операции не публикуется: сервис уже остановлен
    assertThat(Eventually.holds(() -> service.state().equals(DbState.CLOSED))).isTrue();
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(Eventually.holds(() -> states.size() == 2)).isTrue();
    assertThat(states)
        .containsExactly(new DbState.Opening(DbState.Kind.OPEN, file()), DbState.CLOSED);
  }

  @Test
  void repeatedShutdownWithShorterTimeoutDoesNotAbandonOperation()
      throws SQLException, InterruptedException {
    newDatabaseAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var states = record();

    var first = service.shutdownAsync(Duration.ofSeconds(30));
    // Повторная остановка с меньшим сроком истекает раньше, но не помечает операцию покинутой
    assertThat(service.shutdown(Duration.ofMillis(50))).isEqualTo(ShutdownResult.TIMED_OUT);

    storage.openGate.release();

    // Операция завершается штатно в пределах срока первой остановки, состояние публикуется
    assertThat(get(open)).isEqualTo(new OpenResult.Opened(file()));
    assertThat(get(first)).isEqualTo(ShutdownResult.STOPPED);
    assertThat(states)
        .containsExactly(
            new DbState.Opening(DbState.Kind.OPEN, file()),
            new DbState.Open(file()),
            DbState.CLOSED);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  @Test
  void interruptedBeforeCompletionReturnsTimedOut() throws Exception {
    // Сценарий «Прерывание ожидания до завершения остановки»: остановка ещё не завершилась,
    // поэтому прерванное ожидание даёт честное «не дождались», а не выдуманный итог
    newDatabaseAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();

    var outcome = new AtomicReference<ShutdownResult>();
    var failure = new AtomicReference<Throwable>();
    var waiter =
        new Thread(
            () -> {
              try {
                outcome.set(service.shutdown(Duration.ofSeconds(30)));
              } catch (Throwable thrown) {
                failure.set(thrown);
              }
            });
    waiter.start();
    // Прерывание гарантированно попадает в ожидание результата: остановка стоит за операцией
    assertThat(Eventually.holds(() -> waiter.getState() == Thread.State.TIMED_WAITING)).isTrue();
    waiter.interrupt();
    waiter.join();

    assertThat(failure).hasNullValue();
    assertThat(outcome).hasValue(ShutdownResult.TIMED_OUT);
    // Флаг прерывания восстановлен, как и прежде
    assertThat(waiter.isInterrupted()).isTrue();

    // Прерывание ожидания не покидает операцию: она завершается штатно, остановка завершается
    storage.openGate.release();
    assertThat(Eventually.holds(open::isDone)).isTrue();
    assertThat(get(open)).isEqualTo(new OpenResult.Opened(file()));
  }

  @Test
  void interruptionAfterCompletionReturnsActualResult() throws Exception {
    // Сценарий «Прерывание ожидания после завершения остановки»: прерывание не отменяет
    // наступивший факт — ожидание возвращает результат завершившейся остановки «остановлена»,
    // а не подменяет его наблюдением «не дождались»
    newDatabaseAt(file());
    storage.openGate.hold();
    var open = service.open(file(), false);
    assertThat(storage.openGate.awaitEntered()).isTrue();
    var stopping = service.shutdownAsync(Duration.ofSeconds(30));
    storage.openGate.release();
    assertThat(get(stopping)).isEqualTo(ShutdownResult.STOPPED);
    assertThat(get(open)).isEqualTo(new OpenResult.Opened(file()));

    var outcome = new AtomicReference<ShutdownResult>();
    var stillInterrupted = new AtomicBoolean();
    var waiter =
        new Thread(
            () -> {
              outcome.set(service.shutdown(Duration.ofSeconds(30)));
              stillInterrupted.set(Thread.currentThread().isInterrupted());
            });
    // Флаг выставлен до старта: асинхронная остановка уже завершилась, прерывание не может
    // прервать ожидание, но и не подменяет его результат
    waiter.interrupt();
    waiter.start();
    waiter.join();

    assertThat(outcome).hasValue(ShutdownResult.STOPPED);
    assertThat(stillInterrupted).isTrue();
  }

  @Test
  void shutdownFailureCompletesAsyncExceptionallyAndSyncShutdownRethrowsCause() {
    // Сценарий «Сбой самой остановки»: ошибка подписчика при финальной публикации доходит до
    // остановки: асинхронная остановка завершается ошибкой, а не «остановлена», синхронная
    // пробрасывает тот же сбой вызывающему
    var failingStorage = new FailingCloseStorage();
    var failing = new DatabaseService(failingStorage);
    assertThat(get(failing.create(file()))).isEqualTo(new OpenResult.Opened(file()));
    var failure = new AssertionError("сбой подписчика при остановке");
    failing.states().subscribe(failingOnClosedState(failure));

    var stopping = failing.shutdownAsync(Duration.ofSeconds(30));

    assertThatThrownBy(() -> stopping.get(30, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCause(failure);
    // Ожидание уже подтвердил факт: асинхронная остановка завершается ошибкой, а не «остановлена»
    assertThat(stopping).isCompletedExceptionally();
    assertThatThrownBy(() -> failing.shutdown(Duration.ofSeconds(30))).isSameAs(failure);

    // БД закрыта до сбоя публикации, состояние приведено к согласованному «закрыта»
    assertThat(isClosed(failingStorage.opened.get(0))).isTrue();
    assertThat(failing.state()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void repeatedShutdownAfterFailureReturnsSameErrorWithoutReclosingDatabase() {
    // Сценарий «Повторная остановка после сбоя»: повторный вызов возвращает ту же ошибку без
    // повторных попыток закрытия БД: поток жизненного цикла погиб, сервис неработоспособен
    var countingStorage = new CountingCloseStorage();
    var failing = new DatabaseService(countingStorage);
    assertThat(get(failing.create(file()))).isEqualTo(new OpenResult.Opened(file()));
    var failure = new AssertionError("сбой подписчика при остановке");
    failing.states().subscribe(failingOnClosedState(failure));

    var first = failing.shutdownAsync(Duration.ofSeconds(30));
    assertThatThrownBy(() -> first.get(30, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCause(failure);

    // И асинхронная, и синхронная повторная остановка возвращает ту же ошибку
    assertThatThrownBy(() -> get(failing.shutdownAsync(Duration.ofSeconds(30))))
        .hasRootCause(failure);
    assertThatThrownBy(() -> failing.shutdown(Duration.ofSeconds(30))).isSameAs(failure);

    // Повторных попыток закрытия нет: хранилище получило ровно один вызов close
    assertThat(countingStorage.closeCalls).hasValue(1);
    assertThat(isClosed(countingStorage.opened.get(0))).isTrue();
    assertThat(failing.state()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void operationsAfterShutdownFailureAreRejectedAsStopped() {
    // После сбоя остановки сервис неработоспособен: новые операции отклоняются с причиной STOPPED
    var failingStorage = new FailingCloseStorage();
    var failing = new DatabaseService(failingStorage);
    assertThat(get(failing.create(file()))).isEqualTo(new OpenResult.Opened(file()));
    var failure = new AssertionError("сбой подписчика при остановке");
    failing.states().subscribe(failingOnClosedState(failure));

    var stopping = failing.shutdownAsync(Duration.ofSeconds(30));
    assertThatThrownBy(() -> stopping.get(30, TimeUnit.SECONDS))
        .isInstanceOf(ExecutionException.class)
        .hasCause(failure);

    assertThat(get(failing.open(file(), false)))
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected ->
                // После сбоя остановки операции отклоняются с причиной STOPPED
                assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED));
    assertThat(get(failing.close()))
        .isInstanceOfSatisfying(
            CloseResult.Rejected.class,
            rejected ->
                // После сбоя остановки операции отклоняются с причиной STOPPED
                assertThat(rejected.cause()).isEqualTo(RejectionCause.STOPPED));
  }

  /**
   * Хранилище поверх настоящих файлов БД: первый вызов {@code close} бросает сбой, не закрывая БД и
   * не помечая её закрытой заранее; последующие вызовы закрывают по-настоящему.
   */
  private static final class FailingCloseStorage implements DatabaseService.Storage {

    /** Открытые через это хранилище БД. */
    final List<Database> opened = new CopyOnWriteArrayList<>();

    /** Число вызовов {@code close}: тихий повтор сервиса проходит вторым вызовом. */
    final AtomicInteger closeAttempts = new AtomicInteger();

    /** Если задано, первый вызов {@code close} бросает сбой без закрытия БД. */
    volatile boolean failFirstClose;

    @Override
    public Database.State inspect(Path file) {
      return Database.inspect(file);
    }

    @Override
    public Database open(Path file) {
      return track(Database.open(file));
    }

    @Override
    public Database create(Path file) {
      return track(Database.create(file));
    }

    @Override
    public Database initialize(Path file) {
      return track(Database.initialize(file));
    }

    @Override
    public void migrateWithBackup(Path file) {
      Database.migrateWithBackup(file);
    }

    @Override
    public void close(Database database) {
      if (failFirstClose && closeAttempts.incrementAndGet() == 1) {
        throw new IllegalStateException("сбой закрытия без пометки закрытой");
      }
      database.close();
    }

    private Database track(Database database) {
      opened.add(database);
      return database;
    }
  }

  /**
   * Хранилище поверх настоящих файлов БД со счётчиком всех вызовов {@code close}: проверяет, что
   * повторная остановка после сбоя не предпринимает повторных попыток закрытия.
   */
  private static final class CountingCloseStorage implements DatabaseService.Storage {

    /** Открытые через это хранилище БД. */
    final List<Database> opened = new CopyOnWriteArrayList<>();

    /** Общее число вызовов {@code close}: учитывается каждый вызов. */
    final AtomicInteger closeCalls = new AtomicInteger();

    @Override
    public Database.State inspect(Path file) {
      return Database.inspect(file);
    }

    @Override
    public Database open(Path file) {
      return track(Database.open(file));
    }

    @Override
    public Database create(Path file) {
      return track(Database.create(file));
    }

    @Override
    public Database initialize(Path file) {
      return track(Database.initialize(file));
    }

    @Override
    public void migrateWithBackup(Path file) {
      Database.migrateWithBackup(file);
    }

    @Override
    public void close(Database database) {
      closeCalls.incrementAndGet();
      database.close();
    }

    private Database track(Database database) {
      opened.add(database);
      return database;
    }
  }
}
