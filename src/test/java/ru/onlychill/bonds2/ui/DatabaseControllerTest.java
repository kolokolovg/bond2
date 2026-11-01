// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static ru.onlychill.bonds2.dbservice.TestDatabases.isClosed;
import static ru.onlychill.bonds2.dbservice.TestDatabases.newDatabaseAt;
import static ru.onlychill.bonds2.dbservice.TestDatabases.preMigrationCopiesIn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
import ru.onlychill.bonds2.app.ExitCoordinator;
import ru.onlychill.bonds2.dbservice.BackupResult;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.GatedStorage;
import ru.onlychill.bonds2.dbservice.OpenResult;
import ru.onlychill.bonds2.dbservice.RejectionCause;
import ru.onlychill.bonds2.dbservice.ShutdownRefusal;
import ru.onlychill.bonds2.dbservice.ShutdownResult;
import ru.onlychill.bonds2.dbservice.TestDatabases;
import ru.onlychill.bonds2.persistence.Database;

class DatabaseControllerTest {

  private static final String CLOSED_STATUS = "БД не открыта";
  private static final String OPEN_STATUS = "БД открыта";

  @TempDir Path dir;

  private final RecordingDialogs dialogs = new RecordingDialogs();
  private final AtomicInteger exits = new AtomicInteger();
  private final GatedStorage storage = new GatedStorage();
  private final DatabaseService service = new DatabaseService(storage);
  private final UiQueue ui = new UiQueue();

  /** Очередь задач «потока JavaFX»: задачи выполняются на потоке теста в {@link #pumpUntil}. */
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

  @AfterEach
  void stopService() {
    storage.openGate.release();
    storage.migrateGate.release();
    storage.backupGate.release();
    service.shutdown(Duration.ofSeconds(30));
  }

  private Path dataDir() {
    return dir.resolve("data");
  }

  private Path userFile() {
    return dataDir().resolve("bonds.db");
  }

  private Path backupTarget() {
    return dir.resolve("bonds2-copy.db");
  }

  private DatabaseController controller() {
    storage.databaseFile = userFile();
    return new DatabaseController(service, dialogs, exits::incrementAndGet, ui);
  }

  private static boolean isOpen(DatabaseController db) {
    return db.stateProperty().get() instanceof DbState.Open;
  }

  private static boolean isMigrating(DatabaseController db) {
    return db.stateProperty().get() instanceof DbState.Migrating;
  }

  /** Ждёт, пока открытие придёт к итогу, и доставляет все диалоги. */
  private void settleAfterOperation(DatabaseController db) {
    ui.pumpUntil(
        () ->
            db.stateProperty().get() instanceof DbState.Open
                || (db.stateProperty().get() instanceof DbState.Closed closed
                    && closed.reason() != null));
    ui.settle();
  }

  private DatabaseController opened() {
    newDatabaseAt(userFile());
    var db = controller();
    db.openOnStartup();
    ui.pumpUntil(() -> isOpen(db));
    // Дожидается сброса признака идущей операции после доставки результата открытия
    ui.settle();
    return db;
  }

  @Test
  void startsClosedWithClosedStatus() {
    var db = controller();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
  }

  @Test
  void openOnStartupOpensExistingDatabase() {
    var db = opened();

    assertThat(db.statusProperty().get()).isEqualTo(OPEN_STATUS);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void openOnStartupIsUnconditionalWhenDatabaseWasNeverConfigured() {
    // Настроек больше нет: открытие при запуске выполняется всегда, даже для нового контроллера
    var db = controller();

    db.openOnStartup();
    ui.pumpUntil(() -> isOpen(db));

    assertThat(userFile()).isRegularFile();
    assertThat(db.statusProperty().get()).isEqualTo(OPEN_STATUS);
  }

  @Test
  void missingDatabaseFileIsCreatedAutomatically() {
    var db = controller();

    db.openOnStartup();
    ui.pumpUntil(() -> isOpen(db));

    assertThat(userFile()).isRegularFile();
    assertThat(db.statusProperty().get()).isEqualTo(OPEN_STATUS);
  }

  @Test
  void emptyFileIsInitializedWithoutMigrationQuestion() throws IOException {
    Files.createDirectories(dataDir());
    Files.createFile(userFile());
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(isOpen(db)).isTrue();
    assertThat(dialogs.questions).isEmpty();
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void foreignDatabaseIsRejectedWithoutChangesAndWithoutMigrationOffer()
      throws SQLException, IOException {
    TestDatabases.foreignAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: не база bonds2");
    assertThat(dialogs.questions).isEmpty();
    assertThat(dialogs.errors).hasSize(1);
    assertThat(dialogs.errors.get(0))
        .contains("не является базой данных bonds2")
        .contains("Файл не изменён");
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void newerSchemaIsRejectedWithoutTouchingFile() throws SQLException, IOException {
    TestDatabases.tooNewAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: схема новее приложения");
    assertThat(dialogs.errors).hasSize(1);
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
  }

  @Test
  void unexpectedFailureIsReportedAndDatabaseStaysClosed() {
    newDatabaseAt(userFile());
    storage.openFailure = new IllegalStateException("сбой");
    var db = controller();

    db.openOnStartup();
    settleAfterOperation(db);

    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: внутренняя ошибка");
    assertThat(dialogs.errors).singleElement().asString().contains("сбой");
  }

  @Test
  void openIsIgnoredWhileOpeningAndWhileOpen() throws InterruptedException {
    storage.openGate.hold();
    var db = controller();

    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Opening);
    db.retryOpen();

    storage.openGate.release();
    ui.pumpUntil(() -> isOpen(db));
    db.retryOpen();
    ui.settle();

    assertThat(storage.threads).hasSize(1);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void repeatedOpenBeforeResultIsDeliveredSendsOnlyOneCommand()
      throws SQLException, IOException, InterruptedException {
    TestDatabases.foreignAt(userFile());
    storage.openGate.hold();
    var db = controller();

    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    // Результат первого открытия ещё не доставлен: состояние в интерфейсе всё ещё «закрыта»
    db.retryOpen();

    storage.openGate.release();
    ui.pumpUntil(() -> !dialogs.errors.isEmpty());
    ui.settle();

    // Признак идущей операции: сервис получил ровно одну команду, повторный запрос проигнорирован
    assertThat(storage.threads).hasSize(1);
    assertThat(dialogs.errors).hasSize(1);
    assertThat(db.stateProperty().get())
        .isEqualTo(new DbState.Closed("не база bonds2", dialogs.errors.get(0)));

    // Признак сброшен в обработчике результата: новое открытие доходит до сервиса
    db.retryOpen();
    ui.pumpUntil(() -> dialogs.errors.size() == 2);
    assertThat(storage.threads).hasSize(2);
  }

  @Test
  void statusFollowsOpeningAndMigrating() throws SQLException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    storage.migrateGate.hold();
    var db = controller();

    db.openOnStartup();
    ui.pumpUntil(() -> db.statusProperty().get().equals("Открытие базы…"));
    ui.pumpUntil(() -> isMigrating(db));

    assertThat(db.statusProperty().get()).isEqualTo("Открытие базы…");
    storage.migrateGate.release();
    ui.pumpUntil(() -> isOpen(db));
    assertThat(db.statusProperty().get()).isEqualTo(OPEN_STATUS);
  }

  @Test
  void retryOpenWhenClosedOpensDatabase() {
    var db = controller();

    db.retryOpen();
    ui.pumpUntil(() -> isOpen(db));

    assertThat(db.statusProperty().get()).isEqualTo(OPEN_STATUS);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void retryOpenAfterFailureOpensDatabaseAgain() throws SQLException, IOException {
    TestDatabases.foreignAt(userFile());
    var db = controller();
    db.openOnStartup();
    settleAfterOperation(db);
    assertThat(dialogs.errors).hasSize(1);

    // Файл исправлен: повторное открытие после сбоя доходит до сервиса
    Files.delete(userFile());
    newDatabaseAt(userFile());
    db.retryOpen();
    ui.pumpUntil(() -> isOpen(db));
    ui.settle();

    assertThat(storage.threads).hasSize(3);
  }

  @Test
  void backupSuccessShowsInfoDialogWithTargetPath() throws IOException {
    var db = opened();
    Path target = backupTarget();

    db.backupTo(target);
    ui.pumpUntil(() -> !dialogs.infos.isEmpty());

    assertThat(target).isRegularFile();
    assertThat(dialogs.infos).containsExactly("Резервная копия создана: " + target);
    assertThat(dialogs.errors).isEmpty();
    assertThat(isOpen(db)).isTrue();
  }

  @Test
  void backupFailureShowsErrorAndKeepsDatabaseOpen() {
    var db = opened();
    Path target = backupTarget();
    storage.backupFailure = new IllegalStateException("сбой копирования");

    db.backupTo(target);
    ui.pumpUntil(() -> !dialogs.errors.isEmpty());

    assertThat(dialogs.errors).singleElement().asString().contains("сбой копирования");
    assertThat(dialogs.infos).isEmpty();
    // БД остаётся открытой при любом исходе копирования
    assertThat(isOpen(db)).isTrue();
    assertThat(db.stateProperty().get()).isEqualTo(new DbState.Open(userFile()));
  }

  @Test
  void backupToIsIgnoredWhenClosedOrBusy() throws InterruptedException {
    var db = controller();
    db.backupTo(backupTarget());
    ui.settle();
    assertThat(storage.threads).isEmpty();

    // Открытие с БД, идущей через ворота: повторное копирование до доставки результата игнорируется
    storage.openGate.hold();
    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    db.backupTo(backupTarget());
    storage.openGate.release();
    ui.pumpUntil(() -> isOpen(db));
    ui.settle();

    assertThat(storage.threads).hasSize(1);
    assertThat(dialogs.infos).isEmpty();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void repeatedBackupBeforeResultIsDeliveredSendsOnlyOneCommand() throws InterruptedException {
    var db = opened();
    Path target = backupTarget();
    storage.backupGate.hold();

    db.backupTo(target);
    assertThat(storage.backupGate.awaitEntered()).isTrue();
    db.backupTo(target);

    storage.backupGate.release();
    ui.pumpUntil(() -> !dialogs.infos.isEmpty());

    assertThat(storage.threads).hasSize(3);
    assertThat(dialogs.infos).hasSize(1);
  }

  // Сильная ссылка на logger контроллера: изменения уровня и хендлеров не теряются при сборке
  // мусора
  private static final Logger CONTROLLER_LOG = Logger.getLogger(DatabaseController.class.getName());

  // Логи машины выхода пишет координатор
  private static final Logger COORDINATOR_LOG =
      Logger.getLogger(ru.onlychill.bonds2.app.ExitCoordinator.class.getName());

  @Test
  void backupRejectedByStateRaceIsQuietAndLogged() {
    var db = controller();

    var capture = new LogCapture();
    CONTROLLER_LOG.addHandler(capture);
    CONTROLLER_LOG.setLevel(Level.FINE);
    try {
      deliverBackup(db, new BackupResult.Rejected(RejectionCause.STATE, "неизвестный текст"));
      ui.settle();
    } finally {
      CONTROLLER_LOG.removeHandler(capture);
      CONTROLLER_LOG.setLevel(null);
    }

    // Гонка состояния — не ошибка файла: диалога нет, отклонение зафиксировано в журнале
    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.infos).isEmpty();
    assertThat(capture.messages).anySatisfy(m -> assertThat(m).contains("Копирование отклонено"));
  }

  /** Доставляет собранный результат копирования контроллеру через очередь потока интерфейса. */
  private void deliverBackup(DatabaseController db, BackupResult result) {
    ui.execute(() -> db.onBackupResult(result));
    ui.settle();
  }

  @Test
  void backupRejectedByStoppedCauseIsQuietRegardlessOfMessageText() {
    var db = controller();

    // Начатая остановка остаётся тихой, каким бы ни был текст отклонения
    deliverBackup(db, new BackupResult.Rejected(RejectionCause.STOPPED, "не текст остановки"));

    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.infos).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void openRejectedByStateCauseIsQuietRegardlessOfMessageText() {
    var db = controller();

    // Текст не совпадает с производственными формулировками: классификация идёт по причине
    ui.execute(() -> db.onOpenResult(new OpenResult.Rejected(RejectionCause.STATE, "иной текст")));
    ui.settle();

    // Гонка состояния — не ошибка файла: диалога нет при любом тексте
    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void openRejectedByStoppedCauseIsQuietRegardlessOfMessageText() {
    var db = controller();

    // Начатая остановка остаётся тихой, каким бы ни был текст отклонения
    ui.execute(
        () -> db.onOpenResult(new OpenResult.Rejected(RejectionCause.STOPPED, "совсем не текст")));
    ui.settle();

    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void lateBackupResultAfterExitIsIgnoredWithoutDialogs() {
    var db = opened();
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    // Приложение уже завершилось: запоздавший итог копирования не показывает диалогов
    ui.execute(() -> db.onBackupResult(new BackupResult.Copied(backupTarget())));
    ui.execute(() -> db.onBackupResult(new BackupResult.Failed("поздний сбой")));
    ui.settle();

    assertThat(dialogs.infos).isEmpty();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void exitRunsImmediatelyWhenClosed() {
    var db = controller();

    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void repeatedExitRequestIsIgnoredWhileStopping() throws InterruptedException {
    storage.openGate.hold();
    var db = controller();
    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();

    db.requestExit();
    db.requestExit();
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
  void exitTimeoutShowsWarningAndExitsAfterConfirmation() throws InterruptedException {
    storage.openGate.hold();
    var db = controller();
    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    // Выход начат как обычно: флаг exiting выставлен, остановка ждёт открытия
    db.requestExit();
    dialogs.pendingExitWarning = new CompletableFuture<>();

    // Истечение срока остановки доставляется как обычный итог остановки
    ui.execute(() -> db.onExitResult(ShutdownResult.TIMED_OUT));
    ui.pumpUntil(() -> dialogs.exitWarnings.size() == 1);

    assertThat(dialogs.exitWarnings.get(0)).contains("не полностью").contains("восстановятся");
    assertThat(exits.get()).isZero();

    // Повторный запрос игнорируется: диалог не накапливается, выход не начинается заново
    db.requestExit();
    ui.settle();
    assertThat(dialogs.exitWarnings).hasSize(1);

    dialogs.pendingExitWarning.complete(null);
    ui.pumpUntil(() -> exits.get() == 1);
  }

  @Test
  void exitTimeoutOutsideMenuIsLoggedWithoutDialog() throws InterruptedException {
    storage.openGate.hold();
    var db = controller();
    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Opening);

    var capture = new LogCapture();
    COORDINATOR_LOG.addHandler(capture);
    ShutdownResult result;
    try {
      result = db.shutdown(Duration.ofMillis(50));
    } finally {
      COORDINATOR_LOG.removeHandler(capture);
    }

    assertThat(result).isEqualTo(ShutdownResult.TIMED_OUT);
    assertThat(capture.messages)
        .anySatisfy(m -> assertThat(m).contains("не завершилась в срок").contains("WAL"));
    // Диалог в пути без меню невозможен: ни предупреждения, ни диалога выхода
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.exitWarnings).isEmpty();
  }

  @Test
  void menuExitDuringOngoingStopRunsSingleShutdownAndExitsOnce() throws InterruptedException {
    storage.openGate.hold();
    var db = controller();
    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();

    // Меню-путь начал асинхронную остановку: она ждёт открытия
    db.requestExit();
    var chain = CompletableFuture.runAsync(db::shutdown);
    // Поток «интерфейса» свободен: цепочка завершения ждёт ту же остановку сервиса
    assertThat(chain).isNotDone();

    storage.openGate.release();
    assertThat(chain).succeedsWithin(Duration.ofSeconds(10));
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    // Вторая остановка не потребовалась: сервис получил одну команду, БД закрыта, выход один
    assertThat(storage.opened).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(exits.get()).isEqualTo(1);
    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.exitWarnings).isEmpty();
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
    var db = new DatabaseController(failing, dialogs, exits::incrementAndGet, ui);

    db.openOnStartup();
    ui.pumpUntil(() -> isOpen(db));
    failing.states().subscribe(failingOnClosedState(new AssertionError("сбой остановки")));

    db.requestExit();
    ui.settle();

    // Диалог показан, приложение живо, выход не начат заново сам
    assertThat(dialogs.errors).singleElement().asString().contains("сбой остановки");
    assertThat(dialogs.warnings).isEmpty();
    assertThat(exits.get()).isZero();

    // Повторный «Выход»: сервис мёртв, цепочка снова завершается той же ошибкой
    db.requestExit();
    ui.settle();
    assertThat(dialogs.errors).hasSize(2);
    assertThat(dialogs.errors)
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
    var db = new DatabaseController(failing, dialogs, exits::incrementAndGet, ui);

    db.openOnStartup();
    ui.pumpUntil(() -> isOpen(db));
    failing.states().subscribe(failingOnClosedState(new AssertionError("сбой остановки")));

    db.requestExit();
    ui.settle();
    db.requestExit();
    ui.settle();
    db.requestExit();
    ui.settle();

    // Фаза выхода сбрасывается при каждом сбое: каждый запрос запускает цепочку заново
    assertThat(dialogs.errors).hasSize(3);
    assertThat(exits.get()).isZero();
  }

  @Test
  void exitDuringOpeningWaitsForOperationWithoutQuestionAndClosesDatabase()
      throws InterruptedException {
    storage.openGate.hold();
    var db = controller();
    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();

    db.requestExit();
    ui.settle();
    assertThat(exits.get()).isZero();
    storage.openGate.release();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(storage.opened).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void exitIsDelayedWithMessageWhileMigratingAndAllowedAfterwards()
      throws SQLException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    storage.migrateGate.hold();
    var db = controller();
    db.openOnStartup();
    ui.pumpUntil(() -> isMigrating(db));
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    db.requestExit();

    assertThat(dialogs.warnings).singleElement().asString().contains("Идёт миграция");
    assertThat(exits.get()).isZero();
    assertThat(isMigrating(db)).isTrue();

    storage.migrateGate.release();
    ui.pumpUntil(() -> isOpen(db));
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
  }

  @Test
  void exitRefusedByMigrationStartedBeforeUiUpdateKeepsWarningAndExitsAfterwards()
      throws SQLException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    storage.migrateGate.hold();
    var db = controller();
    // Интерфейс ничего не применяет до pumpUntil: он отстаёт от сервиса
    db.openOnStartup();
    assertThat(Eventually(() -> service.state() instanceof DbState.AwaitingMigration)).isTrue();
    var migration = service.migrate();
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    db.requestExit();
    ui.pumpUntil(() -> !dialogs.warnings.isEmpty());

    assertThat(dialogs.warnings).singleElement().asString().contains("Идёт миграция");
    assertThat(dialogs.questions).isEmpty();
    assertThat(exits.get()).isZero();

    storage.migrateGate.release();
    assertThat(migration.join()).isEqualTo(new OpenResult.Opened(userFile()));
    ui.pumpUntil(() -> isOpen(db));
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(dialogs.warnings).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
  }

  @Test
  void exitWhileMigrationDialogIsOpenCancelsDialogDeclinesAndExits()
      throws SQLException, IOException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    var db = controller();
    dialogs.pending = new CompletableFuture<>();

    db.openOnStartup();
    ui.pumpUntil(() -> dialogs.questions.size() == 1);
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.AwaitingMigration);

    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(dialogs.pending).isCancelled();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.errors).isEmpty();
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
  }

  @Test
  void exitWhileAwaitingMigrationDecisionDeclinesAndExits() throws SQLException {
    TestDatabases.outdatedAt(userFile());
    var db = controller();
    // Диалога нет, решение не отправлено: сервис ждёт команду
    service.open().join();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.AwaitingMigration);

    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.questions).isEmpty();
  }

  @Test
  void migrationResultArrivingAfterExitRequestOpensNoDialogAndRunsNoMigration()
      throws SQLException, IOException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    storage.openGate.hold();
    var db = controller();
    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Opening);

    // Выход запрошен, пока открытие ещё идёт; результат «нужна миграция» приходит после запроса
    db.requestExit();
    storage.openGate.release();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(dialogs.questions).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.errors).isEmpty();
    assertThat(exits.get()).isEqualTo(1);
    assertThat(service.state()).isEqualTo(new DbState.Closed("схема устарела", null));
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
  }

  @Test
  void exitRequestedBeforeUiAppliesAwaitingMigrationExitsWithoutDialogOrWarning()
      throws SQLException, IOException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    storage.openGate.hold();
    var db = controller();
    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Opening);

    // Сервис уже ждёт решения, а интерфейс ещё не применил ни состояние, ни результат открытия
    storage.openGate.release();
    assertThat(Eventually(() -> service.state() instanceof DbState.AwaitingMigration)).isTrue();
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(dialogs.questions).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(dialogs.errors).isEmpty();
    assertThat(exits.get()).isEqualTo(1);
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
  }

  @Test
  void repeatedExitRequestWhileAwaitingMigrationDecisionIsIgnored() throws SQLException {
    TestDatabases.outdatedAt(userFile());
    var db = controller();
    // Диалога нет, решение не отправлено: сервис ждёт команду
    service.open().join();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.AwaitingMigration);

    db.requestExit();
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);
    ui.settle();

    assertThat(exits.get()).isEqualTo(1);
    assertThat(dialogs.questions).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
  }

  @Test
  void shutdownClosesOpenDatabaseOnlyOnce() {
    var db = opened();

    db.shutdown();
    db.shutdown();
    ui.settle();

    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void lateResultAfterShutdownIsIgnoredAndItsDatabaseClosed() throws InterruptedException {
    storage.openGate.hold();
    var db = controller();
    db.openOnStartup();
    assertThat(storage.openGate.awaitEntered()).isTrue();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Opening);

    assertThat(db.shutdown(Duration.ofMillis(50))).isEqualTo(ShutdownResult.TIMED_OUT);
    storage.openGate.release();
    ui.settle();

    assertThat(Eventually(() -> storage.opened.size() == 1 && isClosed(storage.opened.get(0))))
        .isTrue();
    // Самолечение остановки: интерфейс приходит к согласованному финалу «закрыта»,
    // промежуточное «открывается» не публикуется и диалогов запоздавший итог не порождает
    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
  }

  private static boolean Eventually(BooleanSupplier condition) {
    return ru.onlychill.bonds2.dbservice.Eventually.holds(condition);
  }

  @Test
  void shutdownTimeoutsMatchSpecification() {
    assertThat(ExitCoordinator.STOP_TIMEOUT).isEqualTo(Duration.ofSeconds(5));
    assertThat(ExitCoordinator.SHUTDOWN_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
    assertThat(ExitCoordinator.MIGRATION_TIMEOUT).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void shutdownAfterExitThroughMenuIsIdempotent() {
    var db = opened();
    db.requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThatCode(db::shutdown).doesNotThrowAnyException();
    assertThat(exits.get()).isEqualTo(1);
  }

  @Test
  void externalShutdownHookStopsRunningService() {
    var db = opened();
    MainWindowApp.setExternalStop(() -> db.shutdown(ExitCoordinator.STOP_TIMEOUT));
    try {
      MainWindowApp.stopDatabaseOnExternalShutdown();

      // Сервис остановлен в пределах короткого срока: БД закрыта, новые операции отклоняются
      assertThat(isClosed(storage.opened.get(0))).isTrue();
      ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Closed);
      assertThat(service.open().join()).isInstanceOf(OpenResult.Rejected.class);
    } finally {
      MainWindowApp.setExternalStop(null);
    }
  }

  @Test
  void externalShutdownHookDoesNothingAfterNormalExit() {
    var db = opened();
    db.shutdown();
    // Штатный выход снял действие остановки: хук не делает ничего и завершению не мешает
    MainWindowApp.setExternalStop(null);

    assertThatCode(MainWindowApp::stopDatabaseOnExternalShutdown).doesNotThrowAnyException();

    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(service.open().join())
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> assertThat(rejected.message()).contains("остановлен"));
  }

  @Test
  void externalShutdownHookSwallowsStopFailure() {
    MainWindowApp.setExternalStop(
        () -> {
          throw new IllegalStateException("сбой остановки");
        });
    try {
      assertThatCode(MainWindowApp::stopDatabaseOnExternalShutdown).doesNotThrowAnyException();
    } finally {
      MainWindowApp.setExternalStop(null);
    }
  }

  @Test
  void shutdownWhileClosedDoesNothing() {
    var db = controller();

    assertThatCode(db::shutdown).doesNotThrowAnyException();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
  }

  @Test
  void shutdownRefusedDuringMigrationDoesNotStopController() throws SQLException {
    TestDatabases.outdatedAt(userFile());
    dialogs.migrationAnswer = true;
    storage.migrateGate.hold();
    var db = controller();
    db.openOnStartup();
    ui.pumpUntil(() -> isMigrating(db));

    assertThat(db.shutdown(Duration.ofMillis(50)))
        .isEqualTo(new ShutdownResult.Refused(ShutdownRefusal.MIGRATING));

    storage.migrateGate.release();
    ui.pumpUntil(() -> isOpen(db));
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void shutdownWhileAwaitingMigrationDeclinesItAndStopsService() throws SQLException, IOException {
    TestDatabases.outdatedAt(userFile());
    byte[] before = Files.readAllBytes(userFile());
    var db = controller();
    // Сервис ждёт решения о миграции; диалога и команды ещё нет
    service.open().join();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.AwaitingMigration);

    db.shutdown();
    ui.settle();

    assertThat(db.stateProperty().get()).isEqualTo(new DbState.Closed("схема устарела", null));
    assertThat(db.statusProperty().get()).isEqualTo("БД не открыта: схема устарела");
    assertThat(dialogs.questions).isEmpty();
    assertThat(dialogs.errors).isEmpty();
    assertThat(Files.readAllBytes(userFile())).isEqualTo(before);
    assertThat(preMigrationCopiesIn(dataDir())).isEmpty();
    // Сервис остановлен: новые операции отклоняются
    assertThat(service.open().join())
        .isInstanceOfSatisfying(
            OpenResult.Rejected.class,
            rejected -> assertThat(rejected.message()).contains("остановлен"));
  }

  @Test
  void shutdownWhileMigratingWaitsForMigrationAndThenStops()
      throws SQLException, IOException, InterruptedException {
    TestDatabases.outdatedAt(userFile());
    storage.migrateGate.hold();
    var db = controller();
    db.openOnStartup();
    ui.pumpUntil(() -> isMigrating(db));
    assertThat(storage.migrateGate.awaitEntered()).isTrue();

    var stopping = CompletableFuture.runAsync(db::shutdown);
    // Миграция не завершена: остановка ждёт её окончания
    assertThat(stopping).isNotDone();

    storage.migrateGate.release();
    // Реальный drain при остановке во время миграции: бюджет ниже дедлайнов pitest-миньона
    assertThat(stopping).succeedsWithin(Duration.ofSeconds(10));
    ui.settle();

    assertThat(Database.inspect(userFile())).isEqualTo(Database.State.UP_TO_DATE);
    assertThat(preMigrationCopiesIn(dataDir())).hasSize(1);
    assertThat(storage.opened).hasSize(1);
    assertThat(isClosed(storage.opened.get(0))).isTrue();
    assertThat(service.state()).isEqualTo(DbState.CLOSED);
    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void shutdownFailureIsLoggedAndExternalTerminationContinues() {
    // Сценарий «Сбой остановки при внешнем завершении»: подписчик бросает ошибку при финальной
    // публикации «закрыта», поэтому синхронная остановка сервиса пробрасывает её вызывающему
    var failingStorage = new GatedStorage();
    failingStorage.databaseFile = userFile();
    var failing = new DatabaseService(failingStorage);
    newDatabaseAt(userFile());
    var db = new DatabaseController(failing, dialogs, exits::incrementAndGet, ui);

    db.openOnStartup();
    ui.pumpUntil(() -> isOpen(db));
    // Подписка после открытия: при подписке доставляется текущее состояние, и «закрыта» пришла бы
    // сразу; сбой нужен только на финальной публикации остановки
    var failure = new AssertionError("сбой остановки");
    failing.states().subscribe(failingOnClosedState(failure));

    var capture = new LogCapture();
    COORDINATOR_LOG.addHandler(capture);
    CONTROLLER_LOG.setLevel(Level.FINE);
    try {
      // Завершение продолжается: сбой остановки не пробрасывается вызывающему
      assertThatCode(db::shutdown).doesNotThrowAnyException();
    } finally {
      COORDINATOR_LOG.removeHandler(capture);
      CONTROLLER_LOG.setLevel(null);
    }
    ui.settle();

    // Сбой остановки журналируется как ошибка с самой причиной, без заворачивания
    assertThat(capture.records)
        .anySatisfy(
            record -> {
              assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
              assertThat(record.getMessage())
                  .contains("Сбой остановки сервиса БД при внешнем завершении");
              assertThat(record.getThrown()).isSameAs(failure);
            });
    // Диалог в пути stop() невозможен: ошибок и предупреждений нет, выход не начат
    assertThat(dialogs.errors).isEmpty();
    assertThat(dialogs.warnings).isEmpty();
    assertThat(exits.get()).isZero();
    // Завершение продолжается: БД закрыта до сбоя публикации
    assertThat(failingStorage.opened).hasSize(1);
    assertThat(isClosed(failingStorage.opened.get(0))).isTrue();
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

  /**
   * Сборщик записей журнала контроллера: проверка тихого отклонения копирования и журнала сбоя
   * остановки при внешнем завершении.
   */
  private static final class LogCapture extends Handler {

    final List<LogRecord> records = new ArrayList<>();
    final List<String> messages = new ArrayList<>();

    @Override
    public void publish(LogRecord record) {
      records.add(record);
      messages.add(record.getMessage());
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }

  static final class RecordingDialogs implements Dialogs {

    final List<String> questions = new ArrayList<>();
    final List<String> warnings = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<String> exitWarnings = new ArrayList<>();
    boolean migrationAnswer = true;

    /** Если задан, диалог остаётся открытым, пока тест не завершит этот future. */
    @Nullable CompletableFuture<Boolean> pending;

    /** Если задан, диалог выхода остаётся открытым, пока тест не завершит этот future. */
    @Nullable CompletableFuture<Void> pendingExitWarning;

    @Override
    public CompletableFuture<Boolean> confirmMigration(String question) {
      questions.add(question);
      return pending != null ? pending : CompletableFuture.completedFuture(migrationAnswer);
    }

    @Override
    public CompletableFuture<Void> warnBeforeExit(String message) {
      exitWarnings.add(message);
      return pendingExitWarning != null
          ? pendingExitWarning
          : CompletableFuture.completedFuture(null);
    }

    @Override
    public void warn(String message) {
      warnings.add(message);
    }

    @Override
    public void info(String message) {
      infos.add(message);
    }

    @Override
    public void error(String message) {
      errors.add(message);
    }
  }
}
