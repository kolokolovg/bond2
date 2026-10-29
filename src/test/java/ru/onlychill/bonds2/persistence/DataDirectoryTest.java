// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataDirectoryTest {

  private static final Path HOME = Path.of("home/u");

  @Test
  void linuxUsesXdgWhenSet() {
    assertThat(DataDirectory.databaseFile("Linux", Map.of("XDG_DATA_HOME", "x")::get, HOME))
        .isEqualTo(Path.of("x/bonds2/bonds2.db"));
  }

  @Test
  void linuxFallsBackToHome() {
    assertThat(DataDirectory.databaseFile("Linux", k -> null, HOME))
        .isEqualTo(Path.of("home/u/.local/share/bonds2/bonds2.db"));
  }

  @Test
  void macUsesApplicationSupport() {
    assertThat(DataDirectory.databaseFile("Mac OS X", k -> null, HOME))
        .isEqualTo(Path.of("home/u/Library/Application Support/bonds2/bonds2.db"));
  }

  @Test
  void windowsUsesAppDataOrFallback() {
    assertThat(DataDirectory.databaseFile("Windows 11", k -> "AD", HOME))
        .isEqualTo(Path.of("AD/bonds2/bonds2.db"));
    assertThat(DataDirectory.databaseFile("Windows 11", k -> " ", HOME))
        .isEqualTo(Path.of("home/u/AppData/Roaming/bonds2/bonds2.db"));
  }

  @Test
  void linuxSettingsFileUsesXdgWhenSet() {
    assertThat(DataDirectory.settingsFile("Linux", Map.of("XDG_DATA_HOME", "x")::get, HOME))
        .isEqualTo(Path.of("x/bonds2/settings.properties"));
  }

  @Test
  void linuxSettingsFileFallsBackToHome() {
    assertThat(DataDirectory.settingsFile("Linux", k -> null, HOME))
        .isEqualTo(Path.of("home/u/.local/share/bonds2/settings.properties"));
  }

  @Test
  void macSettingsFileUsesApplicationSupport() {
    assertThat(DataDirectory.settingsFile("Mac OS X", k -> null, HOME))
        .isEqualTo(Path.of("home/u/Library/Application Support/bonds2/settings.properties"));
  }

  @Test
  void windowsSettingsFileUsesAppDataOrFallback() {
    assertThat(DataDirectory.settingsFile("Windows 11", k -> "AD", HOME))
        .isEqualTo(Path.of("AD/bonds2/settings.properties"));
    assertThat(DataDirectory.settingsFile("Windows 11", k -> " ", HOME))
        .isEqualTo(Path.of("home/u/AppData/Roaming/bonds2/settings.properties"));
  }

  @Test
  void publicSettingsFileSitsNextToDatabaseFile() {
    Path settings = DataDirectory.settingsFile();

    assertThat(settings.getFileName()).hasToString("settings.properties");
    assertThat(settings.getParent()).isEqualTo(DataDirectory.databaseFile().getParent());
  }

  @Test
  void lockFileIsNextToDatabaseFile() {
    assertThat(DataDirectory.lockFile("Linux", k -> null, HOME))
        .isEqualTo(Path.of("home/u/.local/share/bonds2/bonds2.lock"));
    assertThat(DataDirectory.lockFile("Windows 11", k -> "AD", HOME))
        .isEqualTo(Path.of("AD/bonds2/bonds2.lock"));
    assertThat(DataDirectory.lockFile("Mac OS X", k -> null, HOME))
        .isEqualTo(Path.of("home/u/Library/Application Support/bonds2/bonds2.lock"));
  }
}
