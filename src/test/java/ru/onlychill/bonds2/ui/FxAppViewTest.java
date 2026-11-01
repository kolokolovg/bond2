// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.onlychill.bonds2.dbservice.Eventually.holds;
import static ru.onlychill.bonds2.ui.FxToolkit.runOnFxThread;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.app.DbPresenter;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.GatedStorage;

/**
 * Клей входа интерфейса без {@code Application}: {@code Platform.startup}, строитель главного окна
 * и обработчик закрытия. Работает на поднятом тулките (Monocle в headless-режиме); {@code
 * FxAppView.shutdown} не вызывается — он гасит общий тулкит тестов.
 */
class FxAppViewTest {

  @TempDir Path dir;

  @BeforeAll
  static void startToolkit() throws InterruptedException {
    FxToolkit.start();
    // Окна в тестах не управляют временем жизни тулкита: гасит его только цепочка ядра
    Platform.setImplicitExit(false);
  }

  @Test
  void startToolkitReturnsExecutorOfFxThread() throws Exception {
    var view = new FxAppView();

    Executor ui = view.startToolkit();

    var fxThread = new AtomicReference<Thread>();
    var done = new CompletableFuture<Void>();
    ui.execute(
        () -> {
          fxThread.set(Thread.currentThread());
          done.complete(null);
        });
    done.get(10, TimeUnit.SECONDS);
    assertThat(fxThread.get().getName()).isEqualTo("JavaFX Application Thread");
  }

  @Test
  void showMainWindowShowsWindowWithFileMenuAndCloseRequestExits() throws Exception {
    // Сервис без открытия: окно показывается, закрытие запускает выход, БД не трогается
    var storage = new GatedStorage();
    storage.databaseFile = dir.resolve("data/bonds.db");
    var service = new DatabaseService(storage);
    var exits = new AtomicInteger();
    var view = new FxAppView();
    view.startToolkit();
    var presenter = new DbPresenter(service, view, exits::incrementAndGet, Platform::runLater);
    var window = new AtomicReference<Stage>();
    try {
      view.showMainWindow(service, presenter, Platform::runLater);
      runOnFxThread(() -> window.set(windowTitled("Bonds")));

      runOnFxThread(
          () -> {
            Stage stage = window.get();
            assertThat(stage.isShowing()).isTrue();
            var menu =
                (MenuBar) ((javafx.scene.layout.BorderPane) stage.getScene().getRoot()).getTop();
            assertThat(menu.getMenus().get(0).getItems())
                .extracting(MenuItem::getText)
                .containsExactly("Сделать резервную копию…", "Повторить открытие", null, "Выход");
            // Закрытие окна кнопкой закрытия — то же, что «Файл → Выход»: событие поглощено
            stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
          });

      assertThat(holds(() -> exits.get() == 1)).isTrue();
      runOnFxThread(() -> assertThat(window.get().isShowing()).isTrue());
    } finally {
      runOnFxThread(() -> window.get().hide());
      storage.openGate.release();
      storage.migrateGate.release();
      storage.backupGate.release();
      service.shutdown(Duration.ofSeconds(30));
    }
  }

  /** Единственное показанное окно с заданным заголовком. */
  private static Stage windowTitled(String title) {
    List<? extends Window> found =
        Window.getWindows().stream()
            .filter(
                w ->
                    w instanceof Stage stage && title.equals(stage.getTitle()) && stage.isShowing())
            .toList();
    org.junit.jupiter.api.Assertions.assertEquals(
        1, found.size(), "ожидалось одно окно «" + title + "»");
    return (Stage) found.get(0);
  }
}
