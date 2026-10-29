package ru.onlychill.bonds2.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SettingsStoreTest {

  @TempDir Path dir;

  private static final java.time.Clock FIXED =
      java.time.Clock.fixed(
          java.time.LocalDateTime.of(2026, 1, 2, 3, 4, 5)
              .atZone(java.time.ZoneId.systemDefault())
              .toInstant(),
          java.time.ZoneId.systemDefault());

  private SettingsStore storeAt(java.time.Clock clock) {
    return new SettingsStore(settingsFile(), clock);
  }

  private Path settingsFile() {
    return dir.resolve("bonds2/settings.properties");
  }

  private Settings loadFromSettingsFile() {
    return new SettingsStore(settingsFile()).load();
  }

  private void writeSettingsFile(String content) throws IOException {
    Files.createDirectories(dir.resolve("bonds2"));
    Files.writeString(settingsFile(), content, StandardCharsets.UTF_8);
  }

  private static List<String> namesIn(Path directory) throws IOException {
    try (var entries = Files.list(directory)) {
      return entries.map(p -> directory.relativize(p).toString()).sorted().toList();
    }
  }

  @Test
  void missingFileGivesDefaults() {
    assertThat(loadFromSettingsFile()).isEqualTo(Settings.defaults());
  }

  @Test
  void savedValuesAreRestoredOnNextLoad() {
    var custom = new Settings(dir.resolve("Документы/bonds 2 = #test !.db"), false);

    new SettingsStore(settingsFile()).save(custom);

    assertThat(loadFromSettingsFile()).isEqualTo(custom);
  }

  @Test
  void defaultsSurviveRoundTrip() {
    new SettingsStore(settingsFile()).save(Settings.defaults());

    assertThat(loadFromSettingsFile()).isEqualTo(Settings.defaults());
  }

  @Test
  void emptyPathIsStoredAsEmptyValueAndLoadedAsDefault() throws IOException {
    new SettingsStore(settingsFile()).save(new Settings(null, false));

    assertThat(Files.readAllLines(settingsFile(), StandardCharsets.UTF_8))
        .contains("database.path=", "database.autoOpen=false");
    assertThat(loadFromSettingsFile()).isEqualTo(new Settings(null, false));
  }

  @Test
  void blankPathInFileMeansDefaultPath() throws IOException {
    writeSettingsFile("database.path=   \ndatabase.autoOpen=true\n");

    assertThat(loadFromSettingsFile()).isEqualTo(Settings.defaults());
  }

  @Test
  void corruptedBytesGiveDefaults() throws IOException {
    Files.createDirectories(dir.resolve("bonds2"));
    Files.write(settingsFile(), new byte[] {(byte) 0xC3, 0x28, (byte) 0xFF, 0x00, (byte) 0x80});

    assertThat(loadFromSettingsFile()).isEqualTo(Settings.defaults());
  }

  @Test
  void malformedEscapeGivesDefaults() throws IOException {
    writeSettingsFile("database.path=\\uZZZZ\ndatabase.autoOpen=false\n");

    assertThat(loadFromSettingsFile()).isEqualTo(Settings.defaults());
  }

  @Test
  void unreadableFileGivesDefaults() throws IOException {
    // Каталог на месте файла: прочитать его как файл нельзя
    Files.createDirectories(settingsFile());

    assertThat(loadFromSettingsFile()).isEqualTo(Settings.defaults());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "database.path=relative/bonds.db\ndatabase.autoOpen=false\n",
        "database.autoOpen=maybe\n",
        "database.autoOpen=\n",
        "database.path=\\u0000x.db\ndatabase.autoOpen=FALSE\n",
        "database.path=\\uZZZZ\n"
      })
  void corruptedContentIsBackedUpAndReset(String content) throws IOException {
    writeSettingsFile(content);

    assertThat(storeAt(FIXED).load()).isEqualTo(Settings.defaults());

    Path backup = dir.resolve("bonds2/settings.properties.bad-20260102-030405");
    assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(content);
    assertThat(Files.readAllLines(settingsFile(), StandardCharsets.UTF_8))
        .contains("database.path=", "database.autoOpen=true");
    // Повторная загрузка файл уже не считает испорченным
    assertThat(storeAt(FIXED).load()).isEqualTo(Settings.defaults());
    assertThat(namesIn(dir.resolve("bonds2")))
        .containsExactly("settings.properties", "settings.properties.bad-20260102-030405");
  }

  @Test
  void missingFileAndMissingKeysAreNotCorruption() throws IOException {
    assertThat(storeAt(FIXED).load()).isEqualTo(Settings.defaults());
    assertThat(dir.resolve("bonds2")).doesNotExist();

    writeSettingsFile("database.autoOpen=false\n");
    assertThat(storeAt(FIXED).load()).isEqualTo(new Settings(null, false));
    writeSettingsFile("# только комментарий\n");
    assertThat(storeAt(FIXED).load()).isEqualTo(Settings.defaults());
    assertThat(namesIn(dir.resolve("bonds2"))).containsExactly("settings.properties");
  }

  @Test
  void backupNameCollisionGetsNumericSuffix() throws IOException {
    writeSettingsFile("database.autoOpen=maybe\n");
    storeAt(FIXED).load();
    writeSettingsFile("database.autoOpen=nope\n");
    storeAt(FIXED).load();

    assertThat(namesIn(dir.resolve("bonds2")))
        .containsExactly(
            "settings.properties",
            "settings.properties.bad-20260102-030405",
            "settings.properties.bad-20260102-030405-1");
  }

  @Test
  void failedBackupKeepsOriginalAndStillReturnsDefaults() throws IOException {
    writeSettingsFile("database.autoOpen=maybe\n");
    // Каталог только для чтения: копию создать нельзя
    Files.setPosixFilePermissions(
        dir.resolve("bonds2"),
        java.nio.file.attribute.PosixFilePermissions.fromString("r-xr-xr-x"));
    try {
      assertThat(storeAt(FIXED).load()).isEqualTo(Settings.defaults());
      assertThat(Files.readString(settingsFile())).isEqualTo("database.autoOpen=maybe\n");
    } finally {
      Files.setPosixFilePermissions(
          dir.resolve("bonds2"),
          java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
    }
  }

  @Test
  void flagIsReadCaseInsensitively() throws IOException {
    writeSettingsFile("database.autoOpen= False \n");
    assertThat(loadFromSettingsFile().autoOpen()).isFalse();

    writeSettingsFile("database.autoOpen=TRUE\n");
    assertThat(loadFromSettingsFile().autoOpen()).isTrue();
  }

  @Test
  void saveCreatesMissingDirectories() {
    Path file = dir.resolve("a/b/c/settings.properties");

    new SettingsStore(file).save(Settings.defaults());

    assertThat(file).exists();
  }

  @Test
  void saveReplacesPreviousValuesAndLeavesNoTempFiles() throws IOException {
    var store = new SettingsStore(settingsFile());
    store.save(new Settings(dir.resolve("first.db"), true));

    var second = new Settings(dir.resolve("second.db"), false);
    store.save(second);

    assertThat(loadFromSettingsFile()).isEqualTo(second);
    assertThat(namesIn(dir.resolve("bonds2"))).containsExactly("settings.properties");
  }

  @Test
  void saveFailsWhenDirectoryCannotBeCreated() throws IOException {
    Files.writeString(dir.resolve("blocker"), "файл на месте каталога");
    var store = new SettingsStore(dir.resolve("blocker/settings.properties"));

    assertThatThrownBy(() -> store.save(Settings.defaults()))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Не удалось сохранить настройки");
    assertThat(namesIn(dir)).containsExactly("blocker");
  }

  @Test
  void failedReplaceRemovesTempFileAndKeepsExistingTarget() throws IOException {
    // Непустой каталог на месте файла: переименование поверх него невозможно
    Files.createDirectories(settingsFile().resolve("inner"));

    assertThatThrownBy(() -> new SettingsStore(settingsFile()).save(Settings.defaults()))
        .isInstanceOf(UncheckedIOException.class);
    assertThat(namesIn(dir.resolve("bonds2"))).containsExactly("settings.properties");
    assertThat(settingsFile().resolve("inner")).isDirectory();
  }
}
