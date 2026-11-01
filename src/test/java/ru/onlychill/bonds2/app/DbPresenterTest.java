// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static ru.onlychill.bonds2.dbservice.TestDatabases.isClosed;
import static ru.onlychill.bonds2.dbservice.TestDatabases.newDatabaseAt;
import static ru.onlychill.bonds2.dbservice.TestDatabases.outdatedAt;
import static ru.onlychill.bonds2.dbservice.TestDatabases.preMigrationCopiesIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.dbservice.BackupResult;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.GatedStorage;
import ru.onlychill.bonds2.dbservice.OpenResult;
import ru.onlychill.bonds2.dbservice.RejectionCause;
import ru.onlychill.bonds2.dbservice.TestDatabases;

/**
 * Презентер операций БД без интерфейсного тулкита: busy-гейтинг, маршрутизация итогов в порты,
 * применение состояния и делегирование машине выхода. Диалоги заменены записывающими подставами
 * портов; «поток интерфейса» — очередь задач на потоке теста.
 */
class DbPresenterTest {

  @TempDir Path dir;

  private final RecordingExitView exitView = new RecordingExitView();
  private final RecordingOperations operations = new RecordingOperations();
  private final AtomicInteger exits = new AtomicInteger();
  private final GatedStorage storage = new GatedStorage();
  private final DatabaseService service = new DatabaseService(storage);
  private final UiQueue ui = new UiQueue();

  /** Взгляд на сценарий миграции последней сборки; назначается в {@link #presenter()}. */
  @Nullable private RecordingMigrationView migration;

  @AfterEach
  void stopService() {
    storage.openGate.release();
    storage.migrateGate.release();
    storage.backupGate.release();
    try {
      service.shutdown(Duration.ofSeconds(30));
    } catch (RuntimeException ignored) {
      // Поток жизненного цикла мог погибнуть от подменённого сбоя — teardown не важен
    }
  }

  private Path dataDir() {
    return dir.resolve("data");
  }

  private Path userFile() {
    Path file = dataDir().resolve("bonds.db");
    storage.databaseFile = file;
    return file;
  }

  private Path backupTarget() {
    return dir.resolve("bonds2-copy.db");
  }

  private DbPresenter presenter() {
    storage.databaseFile = userFile();
    var presenter = new DbPresenter(service, exitView, exits::incrementAndGet, ui);
    presenter.attachOperations(operations);
    var scenario = new RecordingMigrationView(service, presenter::exiting, presenter::concluded);
    presenter.attachMigration(scenario);
    migration = scenario;
    return presenter;
  }

  private static boolean isOpen(DbPresenter presenter) {
    return presenter.state() instanceof DbState.Open;
  }

  private static boolean isMigrating(DbPresenter presenter) {
    return presenter.state() instanceof DbState.Migrating;
  }

  /** Ждёт, пока открытие придёт к итогу, и доставляет все сообщения портов. */
  private void settleAfterOperation(DbPresenter presenter) {
    ui.pumpUntil(
        () ->
            presenter.state() instanceof DbState.Open
                || (presenter.state() instanceof DbState.Closed closed && closed.reason() != null));
    ui.settle();
  }

  private DbPresenter opened() {
    newDatabaseAt(userFile());
    var presenter = presenter();
    presenter.openOnStartup();
    ui.pumpUntil(() -> isOpen(presenter));
    // Дожидается сброса признака идущей операции после доставки результата открытия
    ui.settle();
    return presenter;
  }

  @Test
  void startsClosedWithoutMessages() {
    var presenter = presenter();

    assertThat(presenter.state()).isEqualTo(DbState.CLOSED);
    assertThat(operations.errors).isEmpty();
    assertThat(operations.infos).isEmpty();
  }

  @Test
  void openOnStartupOpensExistingDatabase() {
    var presenter = opened();

    assertThat(presenter.state()).isEqualTo(new DbState.Open(userFile()));
    assertThat(operations.errors).isEmpty();
  }

  @Test
  void missingDatabaseFileIsCreatedAutomatically() {
    var presenter = presenter();

    presenter.openOnStartup();
    ui.pumpUntil(() -> isOpen(presenter));

    assertThat(userFile()).isRegularFile();
    assertThat(presenter.state()).isEqualTo(new DbState.Open(userFile()));
  }

  @Test
  void emptyFileIsInitializedWithoutMigrationQuestion() throws IOException {
    Files.createDirectories(dataDir());
    Files.createFile(userFile());
    var presenter = presenter();

    presenter.openOnStartup();
    settleAfterOperation(presenter);

    assertThat(isOpen(presenter)).isTrue();
    assertThat(migration.asked).isEmpty();
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
    assertThat(operations.errors).isEmpty();
  }

  @Test
  void foreignDatabaseIsRejectedWithoutMigrationOfferAndWithoutChanges()
      throws SQLException, IOException {
    TestDatabases.foreignAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    var presenter = presenter();

    presenter.openOnStartup();
    settleAfterOperation(presenter);

    assertThat(presenter.state())
        .isEqualTo(new DbState.Closed("не база bonds2", operations.errors.get(0)));
    assertThat(migration.asked).isEmpty();
    assertThat(operations.errors).hasSize(1);
    assertThat(operations.errors.get(0))
        .contains("не является базой данных bonds2")
        .contains("Файл не изменён");
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void newerSchemaIsRejectedWithoutTouchingFile() throws SQLException, IOException {
    TestDatabases.tooNewAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    var presenter = presenter();

    presenter.openOnStartup();
    settleAfterOperation(presenter);

    assertThat(presenter.state())
        .isEqualTo(new DbState.Closed("схема новее приложения", operations.errors.get(0)));
    assertThat(operations.errors).hasSize(1);
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void unexpectedFailureIsReportedAndDatabaseStaysClosed() {
    newDatabaseAt(userFile());
    storage.openFailure = new IllegalStateException("сбой");
    var presenter = presenter();

    presenter.openOnStartup();
    settleAfterOperation(presenter);

    assertThat(presenter.state())
        .isEqualTo(new DbState.Closed("внутренняя ошибка", operations.errors.get(0)));
    assertThat(operations.errors).singleElement().asString().contains("сбой");
  }

  @Test
  void openIsIgnoredWhileOpeningAndWhileOpen() throws InterruptedException {
    storage.openGate.hold();
    var presenter = presenter();

    presenter.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> presenter.state() instanceof DbState.Opening);
    presenter.retryOpen();

    storage.openGate.release();
    ui.pumpUntil(() -> isOpen(presenter));
    presenter.retryOpen();
    ui.settle();

    assertThat(storage.threads).hasSize(1);
    assertThat(operations.errors).isEmpty();
  }

  @Test
  void repeatedOpenBeforeResultIsDeliveredSendsOnlyOneCommand()
      throws SQLException, IOException, InterruptedException {
    TestDatabases.foreignAt(userFile());
    storage.openGate.hold();
    var presenter = presenter();

    presenter.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    // Результат первого открытия ещё не доставлен: состояние в интерфейсе всё ещё «закрыта»
    presenter.retryOpen();

    storage.openGate.release();
    ui.pumpUntil(() -> !operations.errors.isEmpty());
    ui.settle();

    // Признак идущей операции: сервис получил ровно одну команду, повторный запрос проигнорирован
    assertThat(storage.threads).hasSize(1);
    assertThat(operations.errors).hasSize(1);
    assertThat(presenter.state())
        .isEqualTo(new DbState.Closed("не база bonds2", operations.errors.get(0)));

    // Признак сброшен в обработчике результата: новое открытие доходит до сервиса
    presenter.retryOpen();
    ui.pumpUntil(() -> operations.errors.size() == 2);
    assertThat(storage.threads).hasSize(2);
  }

  @Test
  void stateFollowsOpeningAndMigrating() throws SQLException, InterruptedException {
    outdatedAt(userFile());
    storage.migrateGate.hold();
    var presenter = presenter();

    presenter.openOnStartup();
    ui.pumpUntil(() -> presenter.state() instanceof DbState.Opening);
    ui.pumpUntil(() -> isMigrating(presenter));

    assertThat(presenter.state()).isInstanceOf(DbState.Migrating.class);
    storage.migrateGate.release();
    ui.pumpUntil(() -> isOpen(presenter));
    assertThat(presenter.state()).isEqualTo(new DbState.Open(userFile()));
  }

  @Test
  void retryOpenWhenClosedOpensDatabase() {
    var presenter = presenter();

    presenter.retryOpen();
    ui.pumpUntil(() -> isOpen(presenter));

    assertThat(presenter.state()).isEqualTo(new DbState.Open(userFile()));
    assertThat(operations.errors).isEmpty();
  }

  @Test
  void retryOpenAfterFailureOpensDatabaseAgain() throws SQLException, IOException {
    TestDatabases.foreignAt(userFile());
    var presenter = presenter();
    presenter.openOnStartup();
    settleAfterOperation(presenter);
    assertThat(operations.errors).hasSize(1);

    // Файл исправлен: повторное открытие после сбоя доходит до сервиса
    Files.delete(userFile());
    newDatabaseAt(userFile());
    presenter.retryOpen();
    ui.pumpUntil(() -> isOpen(presenter));
    ui.settle();

    assertThat(storage.threads).hasSize(3);
  }

  @Test
  void backupSuccessReportsTargetThroughOperations() throws IOException {
    var presenter = opened();
    Path target = backupTarget();

    presenter.backupTo(target);
    ui.pumpUntil(() -> !operations.infos.isEmpty());

    assertThat(target).isRegularFile();
    assertThat(operations.infos).containsExactly("Резервная копия создана: " + target);
    assertThat(operations.errors).isEmpty();
    assertThat(isOpen(presenter)).isTrue();
  }

  @Test
  void backupFailureShowsErrorAndKeepsDatabaseOpen() {
    var presenter = opened();
    Path target = backupTarget();
    storage.backupFailure = new IllegalStateException("сбой копирования");

    presenter.backupTo(target);
    ui.pumpUntil(() -> !operations.errors.isEmpty());

    assertThat(operations.errors).singleElement().asString().contains("сбой копирования");
    assertThat(operations.infos).isEmpty();
    // БД остаётся открытой при любом исходе копирования
    assertThat(presenter.state()).isEqualTo(new DbState.Open(userFile()));
  }

  @Test
  void backupToIsIgnoredWhenClosedOrBusy() throws InterruptedException {
    var presenter = presenter();
    presenter.backupTo(backupTarget());
    ui.settle();
    assertThat(storage.threads).isEmpty();

    // Открытие с БД, идущей через ворота: повторное копирование до доставки результата игнорируется
    storage.openGate.hold();
    presenter.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    presenter.backupTo(backupTarget());
    storage.openGate.release();
    ui.pumpUntil(() -> isOpen(presenter));
    ui.settle();

    assertThat(storage.threads).hasSize(1);
    assertThat(operations.infos).isEmpty();
    assertThat(operations.errors).isEmpty();
  }

  @Test
  void repeatedBackupBeforeResultIsDeliveredSendsOnlyOneCommand() throws InterruptedException {
    var presenter = opened();
    Path target = backupTarget();
    storage.backupGate.hold();

    presenter.backupTo(target);
    assertThat(storage.backupGate.awaitEntered()).isTrue();
    presenter.backupTo(target);

    storage.backupGate.release();
    ui.pumpUntil(() -> !operations.infos.isEmpty());

    assertThat(storage.threads).hasSize(3);
    assertThat(operations.infos).hasSize(1);
  }

  // Сильная ссылка на logger презентера: изменения уровня и хендлеров не теряются при сборке
  // мусора
  private static final Logger PRESENTER_LOG = Logger.getLogger(DbPresenter.class.getName());

  @Test
  void backupRejectedByStateRaceIsQuietAndLogged() {
    var presenter = presenter();

    var capture = new LogCapture();
    PRESENTER_LOG.addHandler(capture);
    PRESENTER_LOG.setLevel(Level.FINE);
    try {
      deliverBackup(
          presenter, new BackupResult.Rejected(RejectionCause.STATE, "неизвестный текст"));
      ui.settle();
    } finally {
      PRESENTER_LOG.removeHandler(capture);
      PRESENTER_LOG.setLevel(null);
    }

    // Гонка состояния — не ошибка файла: диалога нет, отклонение зафиксировано в журнале
    assertThat(operations.errors).isEmpty();
    assertThat(operations.infos).isEmpty();
    assertThat(capture.messages).anySatisfy(m -> assertThat(m).contains("Копирование отклонено"));
  }

  /** Доставляет собранный результат копирования презентеру через очередь потока интерфейса. */
  private void deliverBackup(DbPresenter presenter, BackupResult result) {
    ui.execute(() -> presenter.onBackupResult(result));
    ui.settle();
  }

  @Test
  void backupRejectedByStoppedCauseIsQuietRegardlessOfMessageText() {
    var presenter = presenter();

    // Начатая остановка остаётся тихой, каким бы ни был текст отклонения
    deliverBackup(
        presenter, new BackupResult.Rejected(RejectionCause.STOPPED, "не текст остановки"));

    assertThat(operations.errors).isEmpty();
    assertThat(operations.infos).isEmpty();
    assertThat(exitView.migrationWarnings).isEmpty();
  }

  @Test
  void openRejectedByStateCauseIsQuietRegardlessOfMessageText() {
    var presenter = presenter();

    // Текст не совпадает с производственными формулировками: классификация идёт по причине
    ui.execute(
        () -> presenter.onOpenResult(new OpenResult.Rejected(RejectionCause.STATE, "иной текст")));
    ui.settle();

    // Гонка состояния — не ошибка файла: диалога нет при любом тексте
    assertThat(operations.errors).isEmpty();
    assertThat(exitView.migrationWarnings).isEmpty();
  }

  @Test
  void openRejectedByStoppedCauseIsQuietRegardlessOfMessageText() {
    var presenter = presenter();

    // Начатая остановка остаётся тихой, каким бы ни был текст отклонения
    ui.execute(
        () ->
            presenter.onOpenResult(
                new OpenResult.Rejected(RejectionCause.STOPPED, "совсем не текст")));
    ui.settle();

    assertThat(operations.errors).isEmpty();
    assertThat(exitView.migrationWarnings).isEmpty();
  }

  @Test
  void lateBackupResultAfterExitIsIgnoredWithoutMessages() {
    var presenter = opened();
    presenter.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    // Приложение уже завершилось: запоздавший итог копирования не показывает диалогов
    ui.execute(() -> presenter.onBackupResult(new BackupResult.Copied(backupTarget())));
    ui.execute(() -> presenter.onBackupResult(new BackupResult.Failed("поздний сбой")));
    ui.settle();

    assertThat(operations.infos).isEmpty();
    assertThat(operations.errors).isEmpty();
  }

  @Test
  void exitRunsImmediatelyWhenClosed() {
    var presenter = presenter();

    presenter.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(exitView.migrationWarnings).isEmpty();
  }

  @Test
  void repeatedExitRequestIsIgnoredWhileStopping() throws InterruptedException {
    storage.openGate.hold();
    var presenter = presenter();
    presenter.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();

    presenter.requestExit();
    presenter.requestExit();
    // Поток «интерфейса» не заблокирован: остановка ждёт открытия, выхода ещё нет
    ui.settle();
    assertThat(exits.get()).isZero();

    storage.openGate.release();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(exits.get()).isEqualTo(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  @Test
  void menuExitDuringOngoingStopRunsSingleShutdownAndExitsOnce() throws InterruptedException {
    storage.openGate.hold();
    var presenter = presenter();
    presenter.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();

    // Меню-путь начал асинхронную остановку: она ждёт открытия
    presenter.requestExit();
    var chain = CompletableFuture.runAsync(presenter::shutdownForExternalTermination);
    // Поток «интерфейса» свободен: остановка цепочки ждёт ту же остановку сервиса
    assertThat(chain).isNotDone();

    storage.openGate.release();
    assertThat(chain).succeedsWithin(Duration.ofSeconds(10));
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    // Вторая остановка не потребовалась: сервис получил одну команду, БД закрыта, выход один
    assertThat(storage.opened).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(exits.get()).isEqualTo(1);
    assertThat(operations.errors).isEmpty();
    assertThat(exitView.migrationWarnings).isEmpty();
  }

  @Test
  void exitFailureBeforeOutcomeShowsErrorAndRepeatedExitReportsSameError() {
    // Сбой остановки до известного итога: подписчик бросает Error при доставке «закрыта»,
    // publish(CLOSED) в runShutdown даёт exceptional future — ошибка, приложение живо.
    // После exceptional-остановки сервис одноразов (инвариант shutdownAsync):
    // повторный «Выход» снова запускает цепочку, но сообщает ту же ошибку.
    var failingStorage = new GatedStorage();
    failingStorage.databaseFile = userFile();
    var failing = new DatabaseService(failingStorage);
    newDatabaseAt(userFile());
    var presenter = new DbPresenter(failing, exitView, exits::incrementAndGet, ui);
    presenter.attachOperations(operations);
    presenter.attachMigration(migration);

    presenter.openOnStartup();
    ui.pumpUntil(() -> isOpen(presenter));
    failing.states().subscribe(failingOnClosedState(new AssertionError("сбой остановки")));

    presenter.requestExit();
    ui.settle();

    // Диалог показан, приложение живо, выход не начат заново сам
    assertThat(exitView.errors).singleElement().asString().contains("сбой остановки");
    assertThat(exits.get()).isZero();

    // Повторный «Выход»: сервис мёртв, цепочка снова завершается той же ошибкой
    presenter.requestExit();
    ui.settle();
    assertThat(exitView.errors).hasSize(2);
    assertThat(exitView.errors)
        .allSatisfy(message -> assertThat(message).contains("сбой остановки"));
    assertThat(exits.get()).isZero();
  }

  @Test
  void repeatedExitChainFailureResetsExitingAgain() {
    // Сбой остановки до известного итога: подписчик падает всегда, каждая попытка — та же ошибка
    var failingStorage = new GatedStorage();
    failingStorage.databaseFile = userFile();
    var failing = new DatabaseService(failingStorage);
    newDatabaseAt(userFile());
    var presenter = new DbPresenter(failing, exitView, exits::incrementAndGet, ui);
    presenter.attachOperations(operations);
    presenter.attachMigration(migration);

    presenter.openOnStartup();
    ui.pumpUntil(() -> isOpen(presenter));
    failing.states().subscribe(failingOnClosedState(new AssertionError("сбой остановки")));

    presenter.requestExit();
    ui.settle();
    presenter.requestExit();
    ui.settle();
    presenter.requestExit();
    ui.settle();

    // Фаза выхода сбрасывается при каждом сбое: каждый запрос запускает цепочку заново
    assertThat(exitView.errors).hasSize(3);
    assertThat(exits.get()).isZero();
  }

  @Test
  void exitDuringOpeningWaitsForOperationWithoutQuestionAndClosesDatabase()
      throws InterruptedException {
    storage.openGate.hold();
    var presenter = presenter();
    presenter.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();

    presenter.requestExit();
    ui.settle();
    assertThat(exits.get()).isZero();
    storage.openGate.release();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(storage.opened).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(exitView.migrationWarnings).isEmpty();
    assertThat(operations.errors).isEmpty();
  }

  @Test
  void exitIsDelayedWithMessageWhileMigratingAndAllowedAfterwards()
      throws SQLException, InterruptedException {
    outdatedAt(userFile());
    storage.migrateGate.hold();
    var presenter = presenter();
    presenter.openOnStartup();
    ui.pumpUntil(() -> isMigrating(presenter));
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    presenter.requestExit();

    assertThat(exitView.migrationWarnings).singleElement().asString().contains("Идёт миграция");
    assertThat(exits.get()).isZero();
    assertThat(isMigrating(presenter)).isTrue();

    storage.migrateGate.release();
    ui.pumpUntil(() -> isOpen(presenter));
    presenter.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
  }

  @Test
  void exitRefusedByMigrationStartedBeforeUiUpdateKeepsWarningAndExitsAfterwards()
      throws SQLException, InterruptedException {
    outdatedAt(userFile());
    storage.migrateGate.hold();
    var presenter = presenter();
    // Интерфейс ничего не применяет до pumpUntil: он отстаёт от сервиса
    presenter.openOnStartup();
    assertThat(Eventually(() -> service.state() instanceof DbState.AwaitingMigration)).isTrue();
    var migrationResult = service.migrate();
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    presenter.requestExit();
    ui.pumpUntil(() -> !exitView.migrationWarnings.isEmpty());

    assertThat(exitView.migrationWarnings).singleElement().asString().contains("Идёт миграция");
    assertThat(migration.asked).isEmpty();
    assertThat(exits.get()).isZero();

    storage.migrateGate.release();
    assertThat(migrationResult.join()).isEqualTo(new OpenResult.Opened(userFile()));
    ui.pumpUntil(() -> isOpen(presenter));
    presenter.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(exitView.migrationWarnings).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  @Test
  void exitWhileMigrationDialogIsOpenCancelsDialogDeclinesAndExits()
      throws SQLException, IOException {
    outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    var presenter = presenter();
    migration.dialog = new CompletableFuture<>();

    presenter.openOnStartup();
    ui.pumpUntil(() -> migration.asked.size() == 1);
    ui.pumpUntil(() -> presenter.state() instanceof DbState.AwaitingMigration);

    presenter.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(migration.cancelled.get()).isEqualTo(1);
    assertThat(migration.dialog).isCancelled();
    assertThat(exitView.migrationWarnings).isEmpty();
    assertThat(operations.errors).isEmpty();
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
  }

  @Test
  void exitWhileAwaitingMigrationDecisionDeclinesAndExits() throws SQLException {
    outdatedAt(userFile());
    var presenter = presenter();
    // Диалога нет, решение не отправлено: сервис ждёт команду
    service.open().join();
    ui.pumpUntil(() -> presenter.state() instanceof DbState.AwaitingMigration);

    presenter.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(exitView.migrationWarnings).isEmpty();
    assertThat(migration.asked).isEmpty();
  }

  @Test
  void migrationResultArrivingAfterExitRequestOpensNoDialogAndRunsNoMigration()
      throws SQLException, IOException, InterruptedException {
    outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    storage.openGate.hold();
    var presenter = presenter();
    presenter.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> presenter.state() instanceof DbState.Opening);

    // Выход запрошен, пока открытие ещё идёт; результат «нужна миграция» приходит после запроса
    presenter.requestExit();
    storage.openGate.release();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(migration.asked).isEmpty();
    assertThat(exitView.migrationWarnings).isEmpty();
    assertThat(operations.errors).isEmpty();
    assertThat(exits.get()).isEqualTo(1);
    assertThat(service.state()).isEqualTo(new DbState.Closed("схема устарела", null));
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
  }

  @Test
  void exitRequestedBeforeUiAppliesAwaitingMigrationExitsWithoutDialogOrWarning()
      throws SQLException, IOException, InterruptedException {
    outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    storage.openGate.hold();
    var presenter = presenter();
    presenter.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> presenter.state() instanceof DbState.Opening);

    // Сервис уже ждёт решения, а интерфейс ещё не применил ни состояние, ни результат открытия
    storage.openGate.release();
    assertThat(Eventually(() -> service.state() instanceof DbState.AwaitingMigration)).isTrue();
    presenter.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(migration.asked).isEmpty();
    assertThat(exitView.migrationWarnings).isEmpty();
    assertThat(operations.errors).isEmpty();
    assertThat(exits.get()).isEqualTo(1);
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
  }

  @Test
  void repeatedExitRequestWhileAwaitingMigrationDecisionIsIgnored() throws SQLException {
    outdatedAt(userFile());
    var presenter = presenter();
    // Диалога нет, решение не отправлено: сервис ждёт команду
    service.open().join();
    ui.pumpUntil(() -> presenter.state() instanceof DbState.AwaitingMigration);

    presenter.requestExit();
    presenter.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(exits.get()).isEqualTo(1);
    assertThat(migration.asked).isEmpty();
    assertThat(exitView.migrationWarnings).isEmpty();
  }

  @Test
  void externalStopClosesOpenDatabaseOnlyOnce() {
    var presenter = opened();

    presenter.shutdownForExternalTermination();
    presenter.shutdownForExternalTermination();
    ui.pumpUntil(() -> presenter.state() instanceof DbState.Closed);

    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(presenter.state()).isEqualTo(DbState.CLOSED);
    assertThat(operations.errors).isEmpty();
  }

  @Test
  void externalStopAfterExitThroughMenuIsIdempotent() {
    var presenter = opened();
    presenter.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThatCode(presenter::shutdownForExternalTermination).doesNotThrowAnyException();
    assertThat(exits.get()).isEqualTo(1);
  }

  @Test
  void externalStopWhileAwaitingMigrationDeclinesItAndStopsService()
      throws SQLException, IOException {
    outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    var presenter = presenter();
    // Сервис ждёт решения о миграции; вопроса ещё нет
    service.open().join();
    ui.pumpUntil(() -> presenter.state() instanceof DbState.AwaitingMigration);

    presenter.shutdownForExternalTermination();
    ui.pumpUntil(() -> presenter.state() instanceof DbState.Closed);

    assertThat(presenter.state()).isEqualTo(new DbState.Closed("схема устарела", null));
    assertThat(migration.asked).isEmpty();
    assertThat(operations.errors).isEmpty();
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
    // Сервис остановлен: новые операции отклоняются
    assertThat(service.open().join())
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> assertThat(rejected.message()).contains("остановлен"));
  }

  @Test
  void externalStopWhileMigratingWaitsForMigrationAndThenStops()
      throws SQLException, IOException, InterruptedException {
    outdatedAt(userFile());
    storage.migrateGate.hold();
    var presenter = presenter();
    presenter.openOnStartup();
    ui.pumpUntil(() -> isMigrating(presenter));
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    var stopping = CompletableFuture.runAsync(presenter::shutdownForExternalTermination);
    // Миграция не завершена: остановка ждёт её окончания
    assertThat(stopping).isNotDone();

    storage.migrateGate.release();
    // Реальный drain при остановке во время миграции: бюджет ниже дедлайнов pitest-миньона
    assertThat(stopping).succeedsWithin(Duration.ofSeconds(10));
    ui.pumpUntil(() -> presenter.state() instanceof DbState.Closed);

    assertThat(presenter.state()).isEqualTo(DbState.CLOSED);
    assertThat(operations.errors).isEmpty();
  }

  private static boolean Eventually(BooleanSupplier condition) {
    return ru.onlychill.bonds2.dbservice.Eventually.holds(condition);
  }

  /** Подписчик, бросающий заданную ошибку только при доставке состояния «закрыта». */
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

  /** Подстава порта машины выхода: предупреждения и ошибки записываются. */
  private static final class RecordingExitView implements ExitView {

    final List<String> migrationWarnings = new CopyOnWriteArrayList<>();
    final List<String> errors = new CopyOnWriteArrayList<>();

    @Override
    public CompletableFuture<Void> warnTimedOutBeforeExit(String message) {
      throw new UnsupportedOperationException("Не используется в тестах презентера");
    }

    @Override
    public void warnMigrationInProgress(String message) {
      migrationWarnings.add(message);
    }

    @Override
    public void showError(String message) {
      errors.add(message);
    }
  }

  /** Подстава порта отображения итогов операций: сообщения записываются. */
  private static final class RecordingOperations implements OperationsView {

    final List<String> infos = new CopyOnWriteArrayList<>();
    final List<String> errors = new CopyOnWriteArrayList<>();

    @Override
    public void showInfo(String message) {
      infos.add(message);
    }

    @Override
    public void showError(String message) {
      errors.add(message);
    }
  }

  /**
   * Подстава взгляда на сценарий миграции: как настоящий сценарий, проверяет фазу выхода перед
   * вопросом, спрашивает «пользователя» (ответ {@code true}, если диалог не удержан тестом) и
   * запускает миграцию; вопрос и отмена диалога записываются.
   */
  private static final class RecordingMigrationView implements MigrationView {

    final List<String> asked = new CopyOnWriteArrayList<>();
    final AtomicInteger cancelled = new AtomicInteger();
    private final DatabaseService service;
    private final BooleanSupplier exitStarted;
    private final BooleanSupplier stopping;

    /** Если задан, «диалог» остаётся открытым, пока тест его не отменит или не завершит. */
    @Nullable CompletableFuture<Boolean> dialog;

    @Nullable private CompletableFuture<OpenResult> migrationFuture;

    RecordingMigrationView(
        DatabaseService service, BooleanSupplier exitStarted, BooleanSupplier stopping) {
      this.service = service;
      this.exitStarted = exitStarted;
      this.stopping = stopping;
    }

    @Override
    public void askAboutMigration() {
      if (exitStarted.getAsBoolean() || stopping.getAsBoolean()) {
        return;
      }
      asked.add("ask");
      CompletableFuture<Boolean> pending = dialog;
      if (pending == null) {
        onAnswer(true);
        return;
      }
      pending.thenAccept(
          confirmed -> {
            if (confirmed) {
              onAnswer(true);
            } else {
              service.declineMigration();
            }
          });
    }

    private void onAnswer(boolean confirmed) {
      if (confirmed) {
        migrationFuture = service.migrate();
      } else {
        service.declineMigration();
      }
    }

    @Override
    public void cancelDialog() {
      cancelled.incrementAndGet();
      CompletableFuture<Boolean> pending = dialog;
      if (pending != null) {
        pending.cancel(true);
      }
    }

    @Override
    public @Nullable CompletableFuture<OpenResult> currentMigration() {
      // Копия: получатель не может завершить или изменить саму миграцию
      return migrationFuture == null ? null : migrationFuture.copy();
    }
  }

  /** Очередь задач «потока интерфейса»: задачи выполняются на потоке теста в {@code pumpUntil}. */
  private static final class UiQueue implements Executor {

    private final LinkedBlockingQueue<Runnable> tasks = new LinkedBlockingQueue<>();

    @Override
    public void execute(Runnable task) {
      tasks.add(task);
    }

    /** Выполняет задачи, пока условие не выполнится; ошибка, если оно не выполнилось за 20 с. */
    void pumpUntil(BooleanSupplier condition) {
      long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
      while (!condition.getAsBoolean()) {
        if (System.nanoTime() > deadline) {
          throw new AssertionError("Условие не выполнилось вовремя");
        }
        try {
          Runnable task = tasks.poll(10, TimeUnit.MILLISECONDS);
          if (task != null) {
            task.run();
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new AssertionError(e);
        }
      }
    }

    /** Выполняет всё, что успело появиться за короткий простой. */
    void settle() {
      try {
        Runnable task;
        while ((task = tasks.poll(150, TimeUnit.MILLISECONDS)) != null) {
          task.run();
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
  }

  /** Сборщик записей журнала презентера: проверка тихого отклонения копирования. */
  private static final class LogCapture extends Handler {

    final List<String> messages = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      messages.add(record.getMessage());
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
