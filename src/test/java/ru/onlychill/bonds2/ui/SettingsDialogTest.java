// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.onlychill.bonds2.ui.FxToolkit.runOnFxThread;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.settings.MemorySettingsBackend;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsStore;

/** Тесты окна настроек с синхронным сохранением: валидация, запись, ошибка записи, отмена. */
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

  /** Окно с записывающим обработчиком: настройки запоминаются. */
  private SettingsDialog dialog(Settings current) {
    return new SettingsDialog(
        null,
        current,
        dir.resolve("default/bonds2.db"),
        settings -> {
          saved.add(settings);
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
  void validPathIsSavedWithCheckboxStateAndClosesWindow() {
    var dialogBox = new SettingsDialog[1];
    runOnFxThread(
        () -> {
          var dialog = dialog(Settings.defaults());
          dialog.pathField().setText(dir.resolve("b.db").toString());
          dialog.autoOpenBox().setSelected(false);
          dialog.show();
          dialogBox[0] = dialog;

          dialog.save();

          assertThat(dialog.errorMessage()).isEmpty();
        });

    assertThat(saved).containsExactly(new Settings(dir.resolve("b.db"), false));
    runOnFxThread(() -> assertThat(dialogBox[0].isShowing()).isFalse());
    hideAllWindows();
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
    var dialogBox = new SettingsDialog[1];
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
          dialog.show();
          dialogBox[0] = dialog;

          dialog.save();

          assertThat(dialogBox[0].isShowing()).isTrue();
          assertThat(dialog.errorMessage())
              .contains("Не удалось сохранить настройки")
              .contains("Permission denied");
        });
    hideAllWindows();
  }

  @Test
  void saveAfterWriteFailureSucceedsAndClosesWindow() {
    // Первая запись падает, вторая проходит: после ошибки сохранение можно повторить
    var calls = new AtomicInteger();
    var dialogBox = new SettingsDialog[1];
    runOnFxThread(
        () -> {
          var dialog =
              new SettingsDialog(
                  null,
                  Settings.defaults(),
                  dir.resolve("default/bonds2.db"),
                  settings -> {
                    if (calls.incrementAndGet() == 1) {
                      throw new UncheckedIOException(
                          "Не удалось сохранить настройки: x", new IOException("нет места"));
                    }
                    saved.add(settings);
                  });
          dialog.pathField().setText(dir.resolve("f.db").toString());
          dialog.show();
          dialogBox[0] = dialog;
          dialog.save();
          assertThat(dialogBox[0].isShowing()).isTrue();

          dialog.save();
        });

    assertThat(calls.get()).isEqualTo(2);
    assertThat(saved).containsExactly(new Settings(dir.resolve("f.db"), true));
    runOnFxThread(() -> assertThat(dialogBox[0].isShowing()).isFalse());
    hideAllWindows();
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
  void saveThroughControllerPersistsSettingsAndClosesWindow() {
    var backend = new MemorySettingsBackend();
    var store = new SettingsStore(backend);
    var db =
        new DatabaseController(
            store,
            store.load(),
            dir.resolve("default/bonds2.db"),
            new DatabaseService(),
            new FxDialogs(null),
            () -> {},
            Platform::runLater);
    var dialogBox = new SettingsDialog[1];
    runOnFxThread(
        () -> {
          var dialog =
              new SettingsDialog(
                  null, Settings.defaults(), dir.resolve("default/bonds2.db"), db::saveSettings);
          dialog.pathField().setText(dir.resolve("g.db").toString());
          dialog.show();
          dialogBox[0] = dialog;

          dialog.save();
        });

    runOnFxThread(
        () -> {
          assertThat(dialogBox[0].isShowing()).isFalse();
          assertThat(dialogBox[0].errorMessage()).isEmpty();
          assertThat(db.settings()).isEqualTo(new Settings(dir.resolve("g.db"), true));
        });
    assertThat(store.load()).isEqualTo(new Settings(dir.resolve("g.db"), true));
    hideAllWindows();
  }
}
