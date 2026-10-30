// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.onlychill.bonds2.ui.FxToolkit.runOnFxThread;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.Eventually;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsStore;

class SettingsDialogTest {

  @TempDir Path dir;

  private final List<Settings> saved = new ArrayList<>();

  @BeforeAll
  static void startToolkit() throws InterruptedException {
    FxToolkit.start();
    // Тесты закрывают окна: без этой строки Monocle в headless-режиме завершает тулкит,
    // когда закрыто последнее окно, и следующие runLater не выполняются
    Platform.setImplicitExit(false);
  }

  /** Окно с записывающим обработчиком: настройки запоминаются, future завершён сразу. */
  private SettingsDialog dialog(Settings current) {
    return new SettingsDialog(
        null,
        current,
        dir.resolve("default/bonds2.db"),
        settings -> {
          saved.add(settings);
          return CompletableFuture.completedFuture(null);
        });
  }

  /** Закрывает окна тулкита: показанные в тесте окна не мешают следующим тестам. */
  private void hideAllWindows() {
    runOnFxThread(
        () -> {
          // close(), а не hide(): вызов hide() в headless-режиме подвешивает поток JavaFX
          for (javafx.stage.Window window : List.copyOf(javafx.stage.Window.getWindows())) {
            if (window instanceof javafx.stage.Stage stage) {
              stage.close();
            }
          }
        });
  }

  @Test
  void showsCurrentValues() {
    runOnFxThread(
        () -> {
          var dialog = dialog(new Settings(dir.resolve("a.db"), false));

          assertThat(dialog.pathField().getText()).isEqualTo(dir.resolve("a.db").toString());
          assertThat(dialog.autoOpenBox().isSelected()).isFalse();
          assertThat(dialog.errorMessage()).isEmpty();
        });
  }

  @Test
  void defaultSettingsShowEmptyPathAndCheckedFlag() {
    runOnFxThread(
        () -> {
          var dialog = dialog(Settings.defaults());

          assertThat(dialog.pathField().getText()).isEmpty();
          assertThat(dialog.autoOpenBox().isSelected()).isTrue();
        });
  }

  @Test
  void validPathIsSavedWithCheckboxStateAndNoError() {
    runOnFxThread(
        () -> {
          var dialog = dialog(Settings.defaults());
          dialog.pathField().setText(dir.resolve("b.db").toString());
          dialog.autoOpenBox().setSelected(false);

          dialog.save();

          assertThat(saved).containsExactly(new Settings(dir.resolve("b.db"), false));
          assertThat(dialog.errorMessage()).isEmpty();
        });
  }

  @Test
  void blankPathIsSavedAsDefaultPath() {
    runOnFxThread(
        () -> {
          var dialog = dialog(new Settings(dir.resolve("a.db"), true));
          dialog.pathField().setText("   ");

          dialog.save();

          assertThat(saved).containsExactly(Settings.defaults());
        });
  }

  @Test
  void relativePathIsRejectedWithMessageAndNothingSaved() {
    runOnFxThread(
        () -> {
          var dialog = dialog(Settings.defaults());
          dialog.pathField().setText("relative/bonds.db");

          dialog.save();

          assertThat(dialog.errorMessage()).contains("полный путь");
          assertThat(saved).isEmpty();
        });
  }

  @Test
  void existingDirectoryIsRejectedWithMessageAndNothingSaved() {
    runOnFxThread(
        () -> {
          var dialog = dialog(Settings.defaults());
          dialog.pathField().setText(dir.toString());

          dialog.save();

          assertThat(dialog.errorMessage()).contains("каталог");
          assertThat(saved).isEmpty();
        });
  }

  @Test
  void writeFailureKeepsDialogOpenAndShowsReason() {
    runOnFxThread(
        () -> {
          var dialog =
              new SettingsDialog(
                  null,
                  Settings.defaults(),
                  dir.resolve("default/bonds2.db"),
                  settings -> {
                    throw new UncheckedIOException(
                        "Не удалось сохранить настройки: x", new IOException("Permission denied"));
                  });
          dialog.pathField().setText(dir.resolve("c.db").toString());

          dialog.save();

          assertThat(dialog.errorMessage())
              .contains("Не удалось сохранить настройки")
              .contains("Permission denied");
        });
  }

  @Test
  void cancelSavesNothing() {
    runOnFxThread(
        () -> {
          var dialog = dialog(Settings.defaults());
          dialog.pathField().setText(dir.resolve("d.db").toString());

          dialog.cancel();

          assertThat(saved).isEmpty();
        });
  }

  @Test
  void saveClosesWindowOnlyAfterWriteCompletes() {
    var pending = new CompletableFuture<Void>();
    var recorded = new ArrayList<Settings>();
    var dialogBox = new SettingsDialog[1];
    runOnFxThread(
        () -> {
          var dialog =
              new SettingsDialog(
                  null,
                  Settings.defaults(),
                  dir.resolve("default/bonds2.db"),
                  settings -> {
                    recorded.add(settings);
                    return pending;
                  });
          dialog.pathField().setText(dir.resolve("e.db").toString());
          dialog.show();

          dialog.save();

          // Запись не завершилась: окно открыто, ошибок нет, настройки переданы обработчику
          assertThat(dialog.errorMessage()).isEmpty();
          assertThat(dialog.isShowing()).isTrue();
          assertThat(recorded).containsExactly(new Settings(dir.resolve("e.db"), true));
          dialogBox[0] = dialog;
        });

    pending.complete(null);

    runOnFxThread(() -> assertThat(dialogBox[0].isShowing()).isFalse());
    hideAllWindows();
  }

  @Test
  void failedWriteCompletionKeepsWindowOpenAndShowsReason() {
    var failing = new CompletableFuture<Void>();
    var dialogBox = new SettingsDialog[1];
    runOnFxThread(
        () -> {
          var dialog =
              new SettingsDialog(
                  null, Settings.defaults(), dir.resolve("default/bonds2.db"), settings -> failing);
          dialog.pathField().setText(dir.resolve("f.db").toString());
          dialog.show();
          dialog.save();
          dialogBox[0] = dialog;
        });

    failing.completeExceptionally(
        new UncheckedIOException(
            "Не удалось сохранить настройки: x", new IOException("Permission denied")));

    runOnFxThread(
        () -> {
          assertThat(dialogBox[0].isShowing()).isTrue();
          assertThat(dialogBox[0].errorMessage())
              .contains("Не удалось сохранить настройки")
              .contains("Permission denied");
        });
    hideAllWindows();
  }

  @Test
  void twoQuickSavesAreSequentialOnOneSettingsThread() throws InterruptedException {
    var writes =
        Executors.newSingleThreadExecutor(
            runnable -> {
              var thread = new Thread(runnable, DatabaseController.SETTINGS_THREAD_NAME);
              thread.setDaemon(true);
              return thread;
            });
    var firstEntered = new CountDownLatch(1);
    var releaseFirst = new CountDownLatch(1);
    var secondEntered = new CountDownLatch(1);
    var writeThreads = new CopyOnWriteArrayList<Thread>();
    var store = new SettingsStore(dir.resolve("cfg/settings.properties"));
    Executor queued =
        task ->
            writes.execute(
                () -> {
                  writeThreads.add(Thread.currentThread());
                  if (firstEntered.getCount() > 0) {
                    firstEntered.countDown();
                    await(releaseFirst);
                  } else {
                    secondEntered.countDown();
                  }
                  task.run();
                });
    var db =
        new DatabaseController(
            store,
            dir.resolve("default/bonds2.db"),
            new DatabaseService(),
            new FxDialogs(null),
            () -> {},
            Platform::runLater,
            queued);
    var dialogBox = new SettingsDialog[1];
    runOnFxThread(
        () -> {
          var dialog =
              new SettingsDialog(
                  null, Settings.defaults(), dir.resolve("default/bonds2.db"), db::saveSettings);
          dialog.pathField().setText(dir.resolve("g.db").toString());
          dialog.show();
          dialogBox[0] = dialog;
          // Два быстрых «Сохранить»: первая запись удержана внутри исполнителя
          dialog.save();
          dialog.save();
        });

    assertThat(firstEntered.await(10, TimeUnit.SECONDS)).isTrue();
    // Первая запись удержана: вторая не начинается — записи не идут параллельно
    assertThat(secondEntered.await(300, TimeUnit.MILLISECONDS)).isFalse();

    releaseFirst.countDown();
    assertThat(secondEntered.await(10, TimeUnit.SECONDS)).isTrue();

    // Окно закрыто по завершении записи, ошибки нет
    assertThat(
            Eventually.holds(
                () -> {
                  var showing = new AtomicBoolean(true);
                  runOnFxThread(() -> showing.set(dialogBox[0].isShowing()));
                  return !showing.get();
                }))
        .isTrue();
    runOnFxThread(() -> assertThat(dialogBox[0].errorMessage()).isEmpty());

    // Обе записи прошли последовательно на одном потоке-демоне исполнителя
    assertThat(writeThreads).hasSize(2);
    assertThat(writeThreads.get(0)).isSameAs(writeThreads.get(1));
    assertThat(writeThreads.get(0).getName()).isEqualTo(DatabaseController.SETTINGS_THREAD_NAME);
    assertThat(store.load().settings()).isEqualTo(new Settings(dir.resolve("g.db"), true));

    writes.shutdownNow();
    hideAllWindows();
  }

  /** Ждёт защёлку; прерывание — сбой теста. */
  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }
}
