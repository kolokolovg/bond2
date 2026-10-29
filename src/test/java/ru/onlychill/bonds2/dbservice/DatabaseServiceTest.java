// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static ru.onlychill.bonds2.dbservice.TestDatabases.backupsIn;
import static ru.onlychill.bonds2.dbservice.TestDatabases.isClosed;
import static ru.onlychill.bonds2.dbservice.TestDatabases.newDatabaseAt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.persistence.Database;

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
    try {
      return future.get(30, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    } catch (ExecutionException | TimeoutException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<DbState> record() {
    var states = new CopyOnWriteArrayList<DbState>();
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
                states.add(item);
              }

              @Override
              public void onError(Throwable error) {}

              @Override
              public void onComplete() {}
            });
    return states;
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
  void operationsRunOnOneDaemonThreadNamedBondsDb() {
    get(service.create(file()));
    get(service.close());
    get(service.open(file(), false));
    get(service.close());

    // Поток жизненного цикла — демон: зависшая операция не удерживает процесс
    assertThat(storage.threads).isNotEmpty();
    Thread worker = storage.threads.get(0);
    assertThat(worker.getName()).isEqualTo("bonds2-db");
    assertThat(worker.isDaemon()).isTrue();
    assertThat(storage.threads).containsOnly(worker);
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
    assertThat(get(service.close())).isInstanceOf(CloseResult.Rejected.class);
    assertThat(get(service.migrate())).isInstanceOf(OpenResult.Rejected.class);
    assertThat(get(service.declineMigration())).isInstanceOf(CloseResult.Rejected.class);
    assertThat(service.state()).isEqualTo(DbState.CLOSED);

    get(service.create(file()));
    byte[] before = Files.readAllBytes(file());
    assertThat(get(service.open(file(), false))).isInstanceOf(OpenResult.Rejected.class);
    assertThat(get(service.create(dir.resolve("other.db"))))
        .isInstanceOf(OpenResult.Rejected.class);
    assertThat(get(service.migrate())).isInstanceOf(OpenResult.Rejected.class);

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

    assertThat(result).isInstanceOf(OpenResult.Rejected.class);
    assertThat(((OpenResult.Rejected) result).message()).contains("уже существует");
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
    byte[] before = Files.readAllBytes(file());
    get(service.open(file(), false));

    var result = get(service.migrate());

    assertThat(result).isInstanceOf(OpenResult.Failed.class);
    var failure = (OpenResult.Failed) result;
    assertThat(failure.reason()).isEqualTo("ошибка миграции");
    assertThat(failure.message()).contains("восстановлена").contains(".bak-");
    assertThat(service.state()).isEqualTo(new DbState.Closed(failure.reason(), failure.message()));
    assertThat(Files.readAllBytes(file())).isEqualTo(before);
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
  void closeFailureIsReportedAsWarningAndDatabaseIsClosed() throws SQLException {
    newDatabaseAt(file());
    get(service.open(file(), false));
    var results = new CopyOnWriteArrayList<CloseResult>();

    TestDatabases.whileReaderLagsBehind(file(), () -> results.add(get(service.close())));

    assertThat(results).hasSize(1);
    assertThat(results.get(0)).isInstanceOf(CloseResult.Closed.class);
    assertThat(((CloseResult.Closed) results.get(0)).warning())
        .contains("Не удалось полностью закрыть БД");
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void shutdownClosesOpenDatabaseAndRejectsNewOperations() {
    get(service.create(file()));
    var database = storage.opened.get(0);

    assertThat(service.shutdown(Duration.ofSeconds(30))).isEqualTo(ShutdownResult.STOPPED);

    assertThat(isClosed(database)).isTrue();
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(get(service.open(file(), false))).isInstanceOf(OpenResult.Rejected.class);
    assertThat(get(service.close())).isInstanceOf(CloseResult.Rejected.class);
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
    assertThat(
            Eventually.holds(() -> storage.opened.size() == 1 && isClosed(storage.opened.get(0))))
        .isTrue();
    assertThat(get(open)).isInstanceOf(OpenResult.Rejected.class);
    assertThat(states).containsExactly(new DbState.Opening(DbState.Kind.OPEN, file()));
    assertThat(service.state()).isEqualTo(new DbState.Opening(DbState.Kind.OPEN, file()));
  }

  @Test
  void shutdownIsRefusedDuringMigrationAndAllowedAfterIt()
      throws SQLException, InterruptedException {
    TestDatabases.outdatedAt(file());
    get(service.open(file(), false));
    storage.migrateGate.hold();
    var migrate = service.migrate();
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    assertThat(service.shutdown(Duration.ofSeconds(1))).isEqualTo(ShutdownResult.REFUSED);
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

    assertThat(service.shutdown(Duration.ofSeconds(1))).isEqualTo(ShutdownResult.REFUSED);

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

    assertThat(result).isEqualTo(ShutdownResult.REFUSED);
    assertThat(get(open)).isEqualTo(new OpenResult.NeedsMigration(file()));
    assertThat(get(service.migrate())).isEqualTo(new OpenResult.Opened(file()));
  }
}
