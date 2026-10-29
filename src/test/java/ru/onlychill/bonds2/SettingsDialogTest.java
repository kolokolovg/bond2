package ru.onlychill.bonds2;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.onlychill.bonds2.FxToolkit.runOnFxThread;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.settings.Settings;

class SettingsDialogTest {

  @TempDir Path dir;

  private final List<Settings> saved = new ArrayList<>();

  @BeforeAll
  static void startToolkit() throws InterruptedException {
    FxToolkit.start();
  }

  /** Окно создаётся в потоке JavaFX, как и в приложении. */
  private SettingsDialog dialog(Settings current) {
    return new SettingsDialog(null, current, dir.resolve("default/bonds2.db"), saved::add);
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
}
