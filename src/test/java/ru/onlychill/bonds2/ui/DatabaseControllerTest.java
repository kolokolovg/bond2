// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.onlychill.bonds2.dbservice.Eventually.holds;
import static ru.onlychill.bonds2.dbservice.TestDatabases.newDatabaseAt;
import static ru.onlychill.bonds2.dbservice.TestDatabases.outdatedAt;
import static ru.onlychill.bonds2.ui.FxToolkit.runOnFxThread;

import java.io.IOException;
import java.nio.file.Path;
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
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.app.DbPresenter;
import ru.onlychill.bonds2.app.ExitView;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.GatedStorage;

/**
 * FX-адаптер презентера: доставка состояния в свойства интерфейса, строка состояния и адаптация
 * порта отображения итогов операций к {@link Dialogs}. Логика гейтинга и маршрутизации — в {@code
 * DbPresenterTest} (без тулкита), машина выхода — в {@code ExitCoordinatorTest} и {@code
 * AppCoreTest}.
 */
class DatabaseControllerTest {

  private static final String CLOSED_STATUS = "БД не открыта";
  private static final String OPEN_STATUS = "БД открыта";

  // Индекс пункта «Выход» в меню «Файл»: перед ним разделитель
  private static final int EXIT = 3;

  @TempDir Path dir;

  private final RecordingDialogs dialogs = new RecordingDialogs();
  private final AtomicInteger exits = new AtomicInteger();
  private final GatedStorage storage = new GatedStorage();
  private final DatabaseService service = new DatabaseService(storage);
  private final UiQueue ui = new UiQueue();

  @BeforeAll
  static void startToolkit() throws InterruptedException {
    FxToolkit.start();
    // Диалоговые тесты держат окна открытыми: тулкит не должен гаснуть между тестами
    Platform.setImplicitExit(false);
  }

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

  private Path userFile() {
    Path file = dir.resolve("data/bonds.db");
    storage.databaseFile = file;
    return file;
  }

  private Path backupTarget() {
    return dir.resolve("bonds2-copy.db");
  }

  /** Презентер и его FX-адаптер, собранные вместе; контроллер назначает порта и слушателя. */
  private record Wiring(DbPresenter presenter, DatabaseController controller) {}

  private Wiring wiring() {
    return wiring(service, storage, dialogs, ui, exits);
  }

  private static Wiring wiring(
      DatabaseService service,
      GatedStorage storage,
      Dialogs dialogs,
      Executor uiExecutor,
      AtomicInteger exits) {
    var presenter =
        new DbPresenter(service, exitViewOf(dialogs), exits::incrementAndGet, uiExecutor);
    var controller = new DatabaseController(presenter, service, dialogs, uiExecutor);
    return new Wiring(presenter, controller);
  }

  /** Адаптер диалогов к порту вида выхода, как в приложении это делает {@link FxAppView}. */
  private static ExitView exitViewOf(Dialogs dialogs) {
    return new ExitView() {
      @Override
      public CompletableFuture<Void> warnTimedOutBeforeExit(String message) {
        return dialogs.warnBeforeExit(message);
      }

      @Override
      public void warnMigrationInProgress(String message) {
        dialogs.warn(message);
      }

      @Override
      public void showError(String message) {
        dialogs.error(message);
      }
    };
  }

  @Test
  void startsClosedWithClosedStatus() {
    var db = wiring().controller();

    assertThat(db.stateProperty().get()).isEqualTo(DbState.CLOSED);
    assertThat(db.statusProperty().get()).isEqualTo(CLOSED_STATUS);
  }

  @Test
  void openDeliversStateAndStatusProperties() {
    newDatabaseAt(userFile());
    var wired = wiring();

    wired.presenter().openOnStartup();
    ui.pumpUntil(() -> wired.controller().stateProperty().get() instanceof DbState.Open);

    assertThat(wired.controller().stateProperty().get()).isEqualTo(new DbState.Open(userFile()));
    assertThat(wired.controller().statusProperty().get()).isEqualTo(OPEN_STATUS);
    assertThat(dialogs.errors).isEmpty();
  }

  @Test
  void statusFollowsOpeningAndMigrating() throws InterruptedException, java.sql.SQLException {
    outdatedAt(userFile());
    storage.migrateGate.hold();
    var wired = wiring();
    var db = wired.controller();

    wired.presenter().openOnStartup();
    ui.pumpUntil(() -> db.statusProperty().get().equals("Открытие базы…"));
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Migrating);

    assertThat(db.statusProperty().get()).isEqualTo("Открытие базы…");
    storage.migrateGate.release();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Open);
    assertThat(db.statusProperty().get()).isEqualTo(OPEN_STATUS);
  }

  @Test
  void backupSuccessShowsInfoDialogWithTargetPath() throws IOException {
    newDatabaseAt(userFile());
    var wired = wiring();
    var db = wired.controller();
    wired.presenter().openOnStartup();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Open);
    ui.settle();
    Path target = backupTarget();

    db.backupTo(target);
    ui.pumpUntil(() -> !dialogs.infos.isEmpty());

    assertThat(target).isRegularFile();
    assertThat(dialogs.infos).containsExactly("Резервная копия создана: " + target);
    assertThat(dialogs.errors).isEmpty();
    assertThat(db.stateProperty().get()).isEqualTo(new DbState.Open(userFile()));
  }

  @Test
  void backupFailureShowsErrorAndKeepsDatabaseOpen() {
    newDatabaseAt(userFile());
    var wired = wiring();
    var db = wired.controller();
    wired.presenter().openOnStartup();
    ui.pumpUntil(() -> db.stateProperty().get() instanceof DbState.Open);
    ui.settle();
    Path target = backupTarget();
    storage.backupFailure = new IllegalStateException("сбой копирования");

    db.backupTo(target);
    ui.pumpUntil(() -> !dialogs.errors.isEmpty());

    assertThat(dialogs.errors).singleElement().asString().contains("сбой копирования");
    assertThat(dialogs.infos).isEmpty();
    // БД остаётся открытой при любом исходе копирования
    assertThat(db.stateProperty().get()).isEqualTo(new DbState.Open(userFile()));
  }

  @Test
  void requestExitDelegatesToExitMachine() {
    var wired = wiring();

    wired.controller().requestExit();
    ui.pumpUntil(() -> exits.get() == 1);

    assertThat(dialogs.warnings).isEmpty();
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

    Path file = dir.resolve("data/bonds.db");
    var failingStorage = new GatedStorage();
    failingStorage.databaseFile = file;
    var failing = new DatabaseService(failingStorage);
    newDatabaseAt(file);
    var failure = new AssertionError("сбой остановки");
    var wired =
        wiring(failing, failingStorage, new FxDialogs(stageBox[0]), Platform::runLater, exits);
    var db = wired.controller();
    try {
      wired.presenter().openOnStartup();
      // Свойство читается с потока теста: доставка идёт в поток JavaFX, дожидаемся её
      assertThat(holds(() -> db.stateProperty().get() instanceof DbState.Open)).isTrue();
      // Сбой остановки до известного итога: подписчик бросает Error при доставке «закрыта»,
      // publish(CLOSED) в runShutdown даёт exceptional future — ошибка, окно живо.
      // После exceptional-остановки сервис одноразов: повторный «Выход» сообщит ту же ошибку.
      failing
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

      MenuItem exitItem =
          MainWindow.createMenuBar(
                  new MainWindow.MenuActions(() -> {}, () -> {}, db::requestExit),
                  db.stateProperty())
              .getMenus()
              .get(0)
              .getItems()
              .get(EXIT);

      Thread.setDefaultUncaughtExceptionHandler(
          (t, e) -> System.err.println("UNCAUGHT on " + t + ": " + e));
      runOnFxThread(exitItem::fire);
      assertThat(holds(DatabaseControllerTest::hasErrorAlert)).isTrue();
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
                      Window.getWindows().stream()
                              .filter(DatabaseControllerTest::isErrorAlert)
                              .count()
                          == 2))
          .isTrue();
      runOnFxThread(
          () -> {
            assertThat(stageBox[0].isShowing()).isTrue();
            assertThat(exits.get()).isZero();
          });
    } finally {
      failingStorage.openGate.release();
      failingStorage.migrateGate.release();
      failingStorage.backupGate.release();
      // Сервис одноразов после сбоя остановки: повторная остановка возвращает ту же ошибку
      assertThatThrownBy(() -> failing.shutdown(Duration.ofSeconds(30))).isSameAs(failure);
      runOnFxThread(() -> stageBox[0].hide());
    }
  }

  private static boolean hasErrorAlert() {
    return Window.getWindows().stream().anyMatch(DatabaseControllerTest::isErrorAlert);
  }

  /** Окно является открытым error-диалогом «Ошибка». */
  private static boolean isErrorAlert(Window window) {
    return window instanceof Stage stage && "Ошибка".equals(stage.getTitle());
  }

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

  /** Записывающие диалоги: сообщения операций и предупреждения фиксируются списками. */
  static final class RecordingDialogs implements Dialogs {

    final List<String> warnings = new ArrayList<>();
    final List<String> infos = new ArrayList<>();
    final List<String> errors = new ArrayList<>();

    @Override
    public CompletableFuture<Boolean> confirmMigration(String question) {
      return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Void> warnBeforeExit(String message) {
      return CompletableFuture.completedFuture(null);
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
