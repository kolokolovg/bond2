// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class DataDirectoryTest {

  private static final Path HOME = Path.of("home/u");
  private static final String ABS = Path.of("abs").toAbsolutePath().toString();

  @Test
  void linuxUsesXdgWhenSet() {
    assertThat(DataDirectory.databaseFile("Linux", Map.of("XDG_DATA_HOME", ABS)::get, HOME))
        .isEqualTo(Path.of(ABS, "bonds2/bonds2.db"));
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
  void windowsUsesLocalAppDataOrFallback() {
    assertThat(DataDirectory.databaseFile("Windows 11", k -> ABS, HOME))
        .isEqualTo(Path.of(ABS, "bonds2/bonds2.db"));
    assertThat(DataDirectory.databaseFile("Windows 11", k -> " ", HOME))
        .isEqualTo(Path.of("home/u/AppData/Local/bonds2/bonds2.db"));
    assertThat(DataDirectory.databaseFile("Windows 11", k -> null, HOME))
        .isEqualTo(Path.of("home/u/AppData/Local/bonds2/bonds2.db"));
    assertThat(DataDirectory.databaseFile("Windows 11", k -> "relative", HOME))
        .isEqualTo(Path.of("home/u/AppData/Local/bonds2/bonds2.db"));
  }

  @Test
  void windowsIgnoresRoamingAppData() {
    UnaryOperator<String> env = k -> k.equals("APPDATA") ? ABS : null;

    assertThat(DataDirectory.databaseFile("Windows 11", env, HOME))
        .isEqualTo(Path.of("home/u/AppData/Local/bonds2/bonds2.db"));
  }

  @Test
  void linuxIgnoresRelativeBlankAndInvalidXdg() {
    for (String value : new String[] {"x", "", "   ", "bad\0path"}) {
      assertThat(DataDirectory.databaseFile("Linux", k -> value, HOME))
          .isEqualTo(Path.of("home/u/.local/share/bonds2/bonds2.db"));
    }
  }

  @Test
  void macIgnoresEnvironment() {
    assertThat(DataDirectory.databaseFile("Mac OS X", k -> ABS, HOME))
        .isEqualTo(Path.of("home/u/Library/Application Support/bonds2/bonds2.db"));
  }

  @Test
  void lockFileIsNextToDatabaseFile() {
    assertThat(DataDirectory.lockFile("Linux", k -> null, HOME))
        .isEqualTo(Path.of("home/u/.local/share/bonds2/bonds2.lock"));
    assertThat(DataDirectory.lockFile("Windows 11", k -> ABS, HOME))
        .isEqualTo(Path.of(ABS, "bonds2/bonds2.lock"));
    assertThat(DataDirectory.lockFile("Mac OS X", k -> null, HOME))
        .isEqualTo(Path.of("home/u/Library/Application Support/bonds2/bonds2.lock"));
  }
}
