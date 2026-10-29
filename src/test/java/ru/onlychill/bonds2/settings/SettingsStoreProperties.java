package ru.onlychill.bonds2.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.Size;
import net.jqwik.api.constraints.StringLength;

class SettingsStoreProperties {

  @Property(tries = 100)
  void loadNeverThrowsAndReturnsValidSettings(@ForAll @Size(max = 200) byte[] bytes)
      throws IOException {
    Path dir = Files.createTempDirectory(Path.of("build"), "settings-prop");
    try {
      Path file = dir.resolve("settings.properties");
      Files.write(file, bytes);

      Settings loaded = new SettingsStore(file).load();

      Path path = loaded.databasePath();
      assertThat(path == null || path.isAbsolute()).isTrue();
    } finally {
      deleteTree(dir);
    }
  }

  @Property(tries = 100)
  void loadAfterRecoveryIsStable(@ForAll @StringLength(max = 60) String text) throws IOException {
    Path dir = Files.createTempDirectory(Path.of("build"), "settings-prop");
    try {
      Path file = dir.resolve("settings.properties");
      Files.writeString(file, "database.autoOpen=" + text.replace('\n', ' ') + "\n");
      var store = new SettingsStore(file);

      Settings first = store.load();

      assertThat(store.load()).isEqualTo(first);
    } finally {
      deleteTree(dir);
    }
  }

  private static void deleteTree(Path dir) throws IOException {
    try (var walk = Files.walk(dir)) {
      for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
