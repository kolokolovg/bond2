package ru.onlychill.bonds2.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.persistence.DataDirectory;

class SettingsTest {

  @TempDir Path dir;

  @Test
  void defaultsUseDefaultPathAndEnableAutoOpen() {
    Settings defaults = Settings.defaults();

    assertThat(defaults.databasePath()).isNull();
    assertThat(defaults.autoOpen()).isTrue();
  }

  @Test
  void missingPathResolvesToDefaultDatabaseFile() {
    assertThat(Settings.defaults().resolvedDatabasePath()).isEqualTo(DataDirectory.databaseFile());
  }

  @Test
  void configuredPathResolvesToItself() {
    Path custom = dir.resolve("custom/bonds.db");

    assertThat(new Settings(custom, false).resolvedDatabasePath()).isEqualTo(custom);
  }

  @Test
  void parsesAbsolutePathIgnoringSurroundingSpaces() {
    Path custom = dir.resolve("bonds.db");

    assertThat(Settings.parseDatabasePath("  " + custom + " \t")).isEqualTo(custom);
  }

  @Test
  void blankTextMeansDefaultPath() {
    assertThat(Settings.parseDatabasePath(null)).isNull();
    assertThat(Settings.parseDatabasePath("")).isNull();
    assertThat(Settings.parseDatabasePath(" \t ")).isNull();
  }

  @Test
  void relativeAndInvalidPathsAreNotAccepted() {
    assertThat(Settings.parseDatabasePath("relative/bonds.db")).isNull();
    assertThat(Settings.parseDatabasePath(dir + "/bo\0nds.db")).isNull();
  }
}
