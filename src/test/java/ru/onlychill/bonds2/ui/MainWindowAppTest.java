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
import java.util.concurrent.atomic.AtomicBoolean;
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
import ru.onlychill.bonds2.settings.MemorySettingsBackend;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsStore;

class MainWindowAppTest {

  // Индексы пунктов меню «Файл»: разделитель стоит между «Закрыть базу» и «Выход»
  private static final int SETTINGS = 0;
  private static final int OPEN = 1;
  private static final int CREATE = 2;
  private static final int CLOSE = 3;
  private static final int EXIT = 5;

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
  void fileMenuContainsDatabaseItemsAndExit() {
    var calls = new ArrayList<String>();
    MenuBar bar =
        MainWindowApp.createMenuBar(
            recording(calls), new SimpleObjectProperty<DbState>(DbState.CLOSED));

    assertThat(bar.getMenus()).extracting(Menu::getText).containsExactly("Файл");
    var items = bar.getMenus().get(0).getItems();
    assertThat(items)
        .extracting(MenuItem::getText)
        .containsExactly(
            "Настройки…", "Открыть базу", "Создать базу…", "Закрыть базу", null, "Выход");

    items.get(SETTINGS).fire();
    items.get(OPEN).fire();
    items.get(CREATE).fire();
    items.get(EXIT).fire();
    assertThat(calls).containsExactly("settings", "open", "create", "exit");
  }

  @Test
  void shutdownStopsDatabaseBeforeReleasingLock() {
    var calls = new ArrayList<String>();

    MainWindowApp.shutDownInOrder(() -> calls.add("database"), () -> calls.add("lock"));

    assertThat(calls).containsExactly("database", "lock");
  }

  @Test
  void lockIsReleasedEvenWhenDatabaseStopFails() {
    var calls = new ArrayList<String>();

    assertThatThrownBy(
            () ->
                MainWindowApp.shutDownInOrder(
                    () -> {
                      throw new IllegalStateException("сбой");
                    },
                    () -> calls.add("lock")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(calls).containsExactly("lock");
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

      // Цепочка запущена один раз и ещё не выполнена: stop() вернулся сразу
      assertThat(chains).hasSize(1);
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
  void exitChainStopsDatabaseThenReleasesLock() throws Exception {
    var file = dir.resolve("data/bonds.db");
    var lockFile = dir.resolve("bonds2.lock");
    TestDatabases.newDatabaseAt(file);
    var storage = new GatedStorage();
    var service = new DatabaseService(storage);
    var exits = new AtomicInteger();
    var store = new SettingsStore(new MemorySettingsBackend());
    var db =
        new DatabaseController(
            store,
            store.load(),
            dir.resolve("default/bonds2.db"),
            service,
            new DatabaseControllerTest.RecordingDialogs(),
            exits::incrementAndGet,
            Platform::runLater);
    Optional<InstanceLock> lock = Optional.empty();
    try {
      service.open(file, false).join();
      lock = InstanceLock.tryAcquire(lockFile);
      assertThat(lock).isPresent();

      // Настройки сохранены синхронно до цепочки: они переживут завершение
      db.saveSettings(new Settings(file, false));

      var done = CompletableFuture.runAsync(MainWindowApp.exitChain(db, lock.orElse(null)));
      assertThat(done).succeedsWithin(Duration.ofSeconds(10));

      // Блокировка освобождена после остановки БД: её берёт новый экземпляр
      var reacquired = InstanceLock.tryAcquire(lockFile);
      assertThat(reacquired).isPresent();
      reacquired.ifPresent(InstanceLock::close);

      assertThat(service.open(file, false).join())
          .isInstanceOfSatisfying(
              OpenResult.Rejected.class,
              rejected -> assertThat(rejected.message()).contains("остановлен"));
      assertThat(store.load()).isEqualTo(new Settings(file, false));
    } finally {
      storage.openGate.release();
      storage.migrateGate.release();
      lock.ifPresent(InstanceLock::close);
      service.shutdown(Duration.ofSeconds(30));
    }
  }

  @Test
  void shutDownInOrderRunsStopBeforeLockAndReleasesLockEvenOnStopFailure() {
    var calls = new ArrayList<String>();

    // Сбой остановки пробрасывается, но не лишает блокировку освобождения
    assertThatThrownBy(
            () ->
                MainWindowApp.shutDownInOrder(
                    () -> {
                      calls.add("stop");
                      throw new IllegalStateException("сбой остановки");
                    },
                    () -> calls.add("lock")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("сбой остановки");

    assertThat(calls).containsExactly("stop", "lock");
  }

  @Test
  void closeItemRunsCloseActionWhenDatabaseIsOpen() {
    var calls = new ArrayList<String>();
    var items =
        MainWindowApp.createMenuBar(recording(calls), new SimpleObjectProperty<DbState>(OPEN_STATE))
            .getMenus()
            .get(0)
            .getItems();

    items.get(CLOSE).fire();

    assertThat(calls).containsExactly("close");
  }

  @Test
  void menuItemsFollowDatabaseState() {
    var state = new SimpleObjectProperty<DbState>(DbState.CLOSED);
    var items =
        MainWindowApp.createMenuBar(recording(new ArrayList<>()), state)
            .getMenus()
            .get(0)
            .getItems();

    assertThat(disabledFlags(items)).containsExactly(false, false, false, true, false, false);

    state.set(OPEN_STATE);
    assertThat(disabledFlags(items)).containsExactly(false, true, true, false, false, false);

    state.set(new DbState.Opening(DbState.Kind.OPEN, FILE));
    assertThat(disabledFlags(items)).containsExactly(true, true, true, true, false, false);

    state.set(new DbState.AwaitingMigration(FILE));
    assertThat(disabledFlags(items)).containsExactly(true, true, true, true, false, false);

    state.set(new DbState.Migrating(FILE));
    assertThat(disabledFlags(items)).containsExactly(true, true, true, true, false, false);
  }

  private static List<Boolean> disabledFlags(List<MenuItem> items) {
    return items.stream().map(MenuItem::isDisable).toList();
  }

  private static MainWindowApp.MenuActions recording(List<String> calls) {
    return new MainWindowApp.MenuActions(
        () -> calls.add("settings"),
        () -> calls.add("open"),
        () -> calls.add("create"),
        () -> calls.add("close"),
        () -> calls.add("exit"));
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

    var failedOnce = new AtomicBoolean();
    var exits = new AtomicInteger();
    var store = new SettingsStore(new MemorySettingsBackend());
    var db =
        new DatabaseController(
            store,
            store.load(),
            dir.resolve("default/bonds2.db"),
            new DatabaseService(),
            new FxDialogs(stageBox[0]),
            () -> {
              // Первый выход сбоит: моделируем отказ цепочки остановки (аудит №3)
              if (failedOnce.compareAndSet(false, true)) {
                throw new IllegalStateException("сбой остановки");
              }
              exits.incrementAndGet();
            },
            Platform::runLater);

    var exitItem =
        MainWindowApp.createMenuBar(
                new MainWindowApp.MenuActions(
                    () -> {}, () -> {}, () -> {}, () -> {}, db::requestExit),
                db.stateProperty())
            .getMenus()
            .get(0)
            .getItems()
            .get(EXIT);

    // Первый «Выход»: цепочка сбоит, процесс не завершается, окно остаётся, ошибка показана
    runOnFxThread(exitItem::fire);
    assertThat(holds(MainWindowAppTest::hasErrorAlert)).isTrue();
    runOnFxThread(
        () -> {
          assertThat(stageBox[0].isShowing()).isTrue();
          assertThat(exits.get()).isZero();
        });

    // Повторный «Выход» обрабатывается: цепочка запускается заново и завершает приложение
    runOnFxThread(exitItem::fire);
    assertThat(holds(() -> exits.get() == 1)).isTrue();

    // Закрытие через close(): вызов hide() в headless-режиме подвешивает поток JavaFX
    runOnFxThread(
        () -> {
          for (Window window : List.copyOf(Window.getWindows())) {
            if (window instanceof Stage stage) {
              stage.close();
            }
          }
        });
  }

  private static boolean hasErrorAlert() {
    return Window.getWindows().stream()
        .anyMatch(window -> window instanceof Stage stage && "Ошибка".equals(stage.getTitle()));
  }
}
