// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.onlychill.bonds2.dbservice.Eventually.holds;
import static ru.onlychill.bonds2.ui.FxToolkit.runOnFxThread;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.GatedStorage;
import ru.onlychill.bonds2.dbservice.OpenResult;
import ru.onlychill.bonds2.dbservice.TestDatabases;
import ru.onlychill.bonds2.persistence.InstanceLock;

class MainWindowAppTest {

  // Индексы пунктов меню «Файл»: разделитель стоит между «Повторить открытие» и «Выход»
  private static final int BACKUP = 0;
  private static final int RETRY_OPEN = 1;
  private static final int EXIT = 3;

  private static final java.nio.file.Path FILE = java.nio.file.Path.of("bonds.db");
  private static final DbState OPEN_STATE = new DbState.Open(FILE);

  @BeforeAll
  static void startToolkit() throws InterruptedException {
    FxToolkit.start();
    // Тесты закрывают окна: без этой строки Monocle в headless-режиме завершает тулкит,
    // когда закрыто последнее окно, и следующие runLater не выполняются
    Platform.setImplicitExit(false);
  }

  @Test
  void fileMenuContainsBackupRetryAndExit() {
    var calls = new ArrayList<String>();
    MenuBar bar =
        MainWindowApp.createMenuBar(
            recording(calls), new SimpleObjectProperty<DbState>(DbState.CLOSED));

    assertThat(bar.getMenus()).extracting(Menu::getText).containsExactly("Файл");
    var items = bar.getMenus().get(0).getItems();
    assertThat(items)
        .extracting(MenuItem::getText)
        .containsExactly("Сделать резервную копию…", "Повторить открытие", null, "Выход");

    items.get(BACKUP).fire();
    items.get(RETRY_OPEN).fire();
    items.get(EXIT).fire();
    assertThat(calls).containsExactly("backup", "retryOpen", "exit");
  }

  @Test
  void stopDoesNotBlockCallerAndLaunchesExitChainOnce() {
    var chains = new ArrayList<Runnable>();
    var externalStops = new AtomicInteger();
    var app = new MainWindowApp();
    app.launchExitChainWith(chains::add);
    MainWindowApp.setExternalStop(externalStops::incrementAndGet);
    try {
      app.stop();
      app.stop();

      // Запуск возможен дважды, выполнение цепочки — один раз (машина выхода)
      assertThat(chains).hasSize(2);
      MainWindowApp.stopDatabaseOnExternalShutdown();
      assertThat(externalStops.get()).isEqualTo(1);

      chains.get(0).run();
      // Снятие действия хука — последний шаг цепочки: остановка больше не вызывается
      MainWindowApp.stopDatabaseOnExternalShutdown();
      assertThat(externalStops.get()).isEqualTo(1);
    } finally {
      MainWindowApp.setExternalStop(null);
    }
  }

  @Test
  void exitChainStopsDatabaseThenReleasesLock() {
    var file = dir.resolve("data/bonds.db");
    var lockFile = dir.resolve("bonds2.lock");
    TestDatabases.newDatabaseAt(file);
    var storage = new GatedStorage();
    storage.databaseFile = file;
    var service = new DatabaseService(storage);
    var exits = new AtomicInteger();
    var db =
        new DatabaseController(
            service,
            new DatabaseControllerTest.RecordingDialogs(),
            exits::incrementAndGet,
            Platform::runLater);
    Optional<InstanceLock> lock = Optional.empty();
    try {
      service.open().join();
      lock = InstanceLock.tryAcquire(lockFile);
      assertThat(lock).isPresent();

      var done = CompletableFuture.runAsync(MainWindowApp.exitChain(db, lock.orElse(null)));
      assertThat(done).succeedsWithin(Duration.ofSeconds(10));

      // Блокировка освобождена после остановки БД: её берёт новый экземпляр
      var reacquired = InstanceLock.tryAcquire(lockFile);
      assertThat(reacquired).isPresent();
      reacquired.ifPresent(InstanceLock::close);

      assertThat(service.open().join())
          .isInstanceOfSatisfying(
              OpenResult.Rejected.class,
              rejected -> assertThat(rejected.message()).contains("остановлен"));
    } finally {
      storage.openGate.release();
      storage.migrateGate.release();
      storage.backupGate.release();
      lock.ifPresent(InstanceLock::close);
      service.shutdown(Duration.ofSeconds(30));
    }
  }

  @Test
  void menuItemsFollowDatabaseState() {
    var state = new SimpleObjectProperty<DbState>(DbState.CLOSED);
    var items =
        MainWindowApp.createMenuBar(recording(new ArrayList<>()), state)
            .getMenus()
            .get(0)
            .getItems();

    // Закрыта: копирование недоступно, повторное открытие доступно
    assertThat(disabledFlags(items)).containsExactly(true, false, false, false);

    state.set(OPEN_STATE);
    // Открыта: копирование доступно, повторное открытие недоступно
    assertThat(disabledFlags(items)).containsExactly(false, true, false, false);

    state.set(new DbState.Opening(FILE));
    assertThat(disabledFlags(items)).containsExactly(true, true, false, false);

    state.set(new DbState.AwaitingMigration(FILE));
    assertThat(disabledFlags(items)).containsExactly(true, true, false, false);

    state.set(new DbState.Migrating(FILE));
    assertThat(disabledFlags(items)).containsExactly(true, true, false, false);

    state.set(new DbState.BackingUp(dir.resolve("bonds2-copy.db")));
    assertThat(disabledFlags(items)).containsExactly(true, true, false, false);
  }

  private static List<Boolean> disabledFlags(List<MenuItem> items) {
    return items.stream().map(MenuItem::isDisable).toList();
  }

  private static MainWindowApp.MenuActions recording(List<String> calls) {
    return new MainWindowApp.MenuActions(
        () -> calls.add("backup"), () -> calls.add("retryOpen"), () -> calls.add("exit"));
  }

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;

  @Test
  void acquireInstanceLockReportsRunningInstance() {
    var reports = new ArrayList<String>();
    var file = dir.resolve("bonds2.lock");

    var first = MainWindowApp.acquireInstanceLock(file, reports::add);
    var second = MainWindowApp.acquireInstanceLock(file, reports::add);

    assertThat(first).isNotNull();
    assertThat(second).isNull();
    assertThat(reports).containsExactly("Приложение уже запущено.");
    first.close();
  }

  @Test
  void acquireInstanceLockReportsFailure() throws java.io.IOException {
    java.nio.file.Files.writeString(dir.resolve("blocker"), "файл");
    var reports = new ArrayList<String>();

    var lock = MainWindowApp.acquireInstanceLock(dir.resolve("blocker/bonds2.lock"), reports::add);

    assertThat(lock).isNull();
    assertThat(reports).singleElement().asString().startsWith("Не удалось запустить приложение");
  }

  @Test
  void newDatabaseNameWithoutExtensionGetsDbAppended() {
    assertThat(MainWindowApp.withDbExtension(dir.resolve("база")).getFileName().toString())
        .isEqualTo("база.db");

    // Имя с расширением не меняется: чувствительность к регистру не требуется
    assertThat(MainWindowApp.withDbExtension(dir.resolve("база.db")))
        .isEqualTo(dir.resolve("база.db"));
    assertThat(MainWindowApp.withDbExtension(dir.resolve("база.DB")))
        .isEqualTo(dir.resolve("база.DB"));
    assertThat(MainWindowApp.withDbExtension(dir.resolve("архив.sqlite")))
        .isEqualTo(dir.resolve("архив.sqlite"));
    // Скрытый файл без расширения тоже получает .db
    assertThat(MainWindowApp.withDbExtension(dir.resolve(".bonds")))
        .isEqualTo(dir.resolve(".bonds.db"));
  }

  @Test
  void failedStopOnExitLeavesWindowOpenAndProcessesRepeatedExit() {
    var stageBox = new Stage[1];
    runOnFxThread(
        () -> {
          var stage = new Stage();
          stage.setTitle("Bonds");
          stage.setScene(new Scene(new Group()));
          stage.show();
          stageBox[0] = stage;
        });

    var file = dir.resolve("data/bonds.db");
    TestDatabases.newDatabaseAt(file);
    var storage = new GatedStorage();
    storage.databaseFile = file;
    var service = new DatabaseService(storage);
    var exits = new AtomicInteger();
    var failure = new AssertionError("сбой остановки");
    var db =
        new DatabaseController(
            service, new FxDialogs(stageBox[0]), exits::incrementAndGet, Platform::runLater);
    try {
      service.open().join();
      // Сбой остановки до известного итога: подписчик бросает Error при доставке «закрыта»,
      // publish(CLOSED) в runShutdown даёт exceptional future — ошибка, окно живо.
      // После exceptional-остановки сервис одноразов: повторный «Выход» сообщит ту же ошибку.
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
                  if (item instanceof DbState.Closed) {
                    throw failure;
                  }
                }

                @Override
                public void onError(Throwable throwable) {}

                @Override
                public void onComplete() {}
              });

      var exitItem =
          MainWindowApp.createMenuBar(
                  new MainWindowApp.MenuActions(() -> {}, () -> {}, db::requestExit),
                  db.stateProperty())
              .getMenus()
              .get(0)
              .getItems()
              .get(EXIT);

      Thread.setDefaultUncaughtExceptionHandler(
          (t, e) -> System.err.println("UNCAUGHT on " + t + ": " + e));
      runOnFxThread(exitItem::fire);
      assertThat(holds(MainWindowAppTest::hasErrorAlert)).isTrue();
      runOnFxThread(
          () -> {
            assertThat(stageBox[0].isShowing()).isTrue();
            assertThat(exits.get()).isZero();
          });

      // Повторный «Выход»: сервис мёртв — снова error-диалог, окно живо, выхода нет
      runOnFxThread(exitItem::fire);
      assertThat(
              holds(
                  () ->
                      Window.getWindows().stream().filter(MainWindowAppTest::isErrorAlert).count()
                          == 2))
          .isTrue();
      runOnFxThread(
          () -> {
            assertThat(stageBox[0].isShowing()).isTrue();
            assertThat(exits.get()).isZero();
          });
    } finally {
      storage.openGate.release();
      storage.migrateGate.release();
      storage.backupGate.release();
      // Сервис одноразов после сбоя остановки: повторная остановка возвращает ту же ошибку
      assertThatThrownBy(() -> service.shutdown(Duration.ofSeconds(30))).isSameAs(failure);
    }
  }

  private static boolean hasErrorAlert() {
    return Window.getWindows().stream().anyMatch(MainWindowAppTest::isErrorAlert);
  }

  /** Окно является открытым error-диалогом «Ошибка». */
  private static boolean isErrorAlert(Window window) {
    return window instanceof Stage stage && "Ошибка".equals(stage.getTitle());
  }
}
