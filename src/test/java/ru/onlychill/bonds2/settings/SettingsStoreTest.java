// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
    return new SettingsStore(settingsFile()).load().settings();
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
  void fileWithBomAndValidKeysIsReadWithoutRecovery() throws IOException {
    // BOM добавляют Windows-редакторы: PowerShell 5.1, старый Блокнот
    Path db = dir.resolve("Документы/база.db");
    writeSettingsFile("\uFEFFdatabase.path=" + db + "\ndatabase.autoOpen=false\n");

    var result = storeAt(FIXED).load();

    assertThat(result.settings()).isEqualTo(new Settings(db, false));
    assertThat(result.recovered()).isFalse();
    assertThat(result.badCopy()).isNull();
    // Файл пользователя остался как был: сброс к значениям по умолчанию не выполнялся
    assertThat(Files.readString(settingsFile(), StandardCharsets.UTF_8)).startsWith("\uFEFF");
    assertThat(namesIn(dir.resolve("bonds2"))).containsExactly("settings.properties");
  }

  @Test
  void fileWithBomAndGarbageIsStillCorrupted() throws IOException {
    String content = "\uFEFFdatabase.autoOpen=maybe\n";
    writeSettingsFile(content);

    var result = storeAt(FIXED).load();

    assertThat(result.settings()).isEqualTo(Settings.defaults());
    assertThat(result.recovered()).isTrue();
    Path backup = dir.resolve("bonds2/settings.properties.bad-20260102-030405");
    assertThat(result.badCopy()).isEqualTo(backup);
    assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(content);
  }

  @Test
  void fileWithoutBomIsReadAsBefore() throws IOException {
    var custom = new Settings(dir.resolve("база.db"), true);
    writeSettingsFile("database.path=" + custom.databasePath() + "\ndatabase.autoOpen=true\n");

    var result = storeAt(FIXED).load();

    assertThat(result).isEqualTo(new SettingsStore.LoadResult(custom, null, false));
  }

  @Test
  void bomNotInFirstPositionIsNotStripped() throws IOException {
    // Метка стоит не первым символом файла, поэтому не срезается: ключ остаётся нераспознанным
    writeSettingsFile("# комментарий\n\uFEFFdatabase.autoOpen=false\n");

    var result = storeAt(FIXED).load();

    assertThat(result).isEqualTo(new SettingsStore.LoadResult(Settings.defaults(), null, false));
    assertThat(Files.readString(settingsFile(), StandardCharsets.UTF_8)).contains("\uFEFF");
  }

  @Test
  void bomOnlyFileIsReadAsEmptyAndNotCorruption() throws IOException {
    writeSettingsFile("\uFEFF");

    var result = storeAt(FIXED).load();

    // После среза метки остаётся пустое содержимое: повреждением это не считается
    assertThat(result).isEqualTo(new SettingsStore.LoadResult(Settings.defaults(), null, false));
    assertThat(Files.readString(settingsFile(), StandardCharsets.UTF_8)).isEqualTo("\uFEFF");
    assertThat(namesIn(dir.resolve("bonds2"))).containsExactly("settings.properties");
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

    var result = storeAt(FIXED).load();
    assertThat(result.settings()).isEqualTo(Settings.defaults());
    assertThat(result.recovered()).isTrue();

    Path backup = dir.resolve("bonds2/settings.properties.bad-20260102-030405");
    assertThat(result.badCopy()).isEqualTo(backup);
    assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(content);
    assertThat(Files.readAllLines(settingsFile(), StandardCharsets.UTF_8))
        .contains("database.path=", "database.autoOpen=true");
    // Повторная загрузка файл уже не считает испорченным
    var again = storeAt(FIXED).load();
    assertThat(again.settings()).isEqualTo(Settings.defaults());
    assertThat(again.recovered()).isFalse();
    assertThat(again.badCopy()).isNull();
    assertThat(namesIn(dir.resolve("bonds2")))
        .containsExactly("settings.properties", "settings.properties.bad-20260102-030405");
  }

  @Test
  void missingFileAndMissingKeysAreNotCorruption() throws IOException {
    var missing = storeAt(FIXED).load();
    assertThat(missing.settings()).isEqualTo(Settings.defaults());
    assertThat(missing.recovered()).isFalse();
    assertThat(missing.badCopy()).isNull();
    assertThat(dir.resolve("bonds2")).doesNotExist();

    writeSettingsFile("database.autoOpen=false\n");
    assertThat(storeAt(FIXED).load().settings()).isEqualTo(new Settings(null, false));
    writeSettingsFile("# только комментарий\n");
    assertThat(storeAt(FIXED).load().settings()).isEqualTo(Settings.defaults());
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
      var result = storeAt(FIXED).load();
      assertThat(result.settings()).isEqualTo(Settings.defaults());
      assertThat(result.recovered()).isTrue();
      assertThat(result.badCopy()).isNull();
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

  @Test
  void savedFileIsUtf8WithNonAsciiValues() throws IOException {
    var custom = new Settings(dir.resolve("Каталог/база ё.db"), true);

    new SettingsStore(settingsFile()).save(custom);

    assertThat(Files.readString(settingsFile(), StandardCharsets.UTF_8)).contains("Каталог");
    assertThat(loadFromSettingsFile()).isEqualTo(custom);
    assertThat(namesIn(dir.resolve("bonds2"))).containsExactly("settings.properties");
  }

  @Test
  void failedWriteKeepsPreviousFileAndLeavesNoTempFile() throws IOException {
    var store = new SettingsStore(settingsFile());
    store.save(new Settings(dir.resolve("first.db"), true));
    byte[] before = Files.readAllBytes(settingsFile());
    Path settingsDir = dir.resolve("bonds2");
    assumeTrue(isPosix());
    // Каталог только для чтения: временный файл записи создать нельзя
    makeReadOnly(settingsDir);
    try {
      assumeFalse(Files.isWritable(settingsDir), "под администратором права не ограничиваются");
      assertThatThrownBy(() -> store.save(new Settings(dir.resolve("second.db"), false)))
          .isInstanceOf(UncheckedIOException.class);
    } finally {
      makeWritable(settingsDir);
    }
    assertThat(Files.readAllBytes(settingsFile())).isEqualTo(before);
    assertThat(namesIn(settingsDir)).containsExactly("settings.properties");
  }

  @Test
  void leftoverTempFileIsRemovedOnLoadAndSettingsAreStillRead() throws IOException {
    writeSettingsFile("database.autoOpen=false\n");
    Path orphan = Files.writeString(dir.resolve("bonds2/settings-x.tmp"), "обрывок записи");

    var result = storeAt(FIXED).load();

    assertThat(orphan).doesNotExist();
    assertThat(result.settings()).isEqualTo(new Settings(null, false));
    assertThat(result.recovered()).isFalse();
    assertThat(namesIn(dir.resolve("bonds2"))).containsExactly("settings.properties");
  }

  @Test
  void leftoverTempFileWithoutSettingsFileIsRemovedAndDefaultsGiven() throws IOException {
    Files.createDirectories(dir.resolve("bonds2"));
    Path orphan = Files.writeString(dir.resolve("bonds2/settings-y.tmp"), "обрывок записи");

    var result = storeAt(FIXED).load();

    assertThat(orphan).doesNotExist();
    assertThat(result).isEqualTo(new SettingsStore.LoadResult(Settings.defaults(), null, false));
    assertThat(namesIn(dir.resolve("bonds2"))).isEmpty();
  }

  @Test
  void orphanTempFileThatCannotBeDeletedDoesNotStopReading() throws IOException {
    writeSettingsFile("database.autoOpen=false\n");
    Path settingsDir = dir.resolve("bonds2");
    Path orphan = Files.writeString(settingsDir.resolve("settings-z.tmp"), "обрывок записи");
    assumeTrue(isPosix());
    makeReadOnly(settingsDir);
    try {
      assumeFalse(Files.isWritable(settingsDir), "под администратором права не ограничиваются");

      var result = storeAt(FIXED).load();

      assertThat(result.settings()).isEqualTo(new Settings(null, false));
      assertThat(result.recovered()).isFalse();
      assertThat(orphan).exists();
    } finally {
      makeWritable(settingsDir);
    }
  }

  @Test
  void emptyFileIsResetToDefaultsWithoutCopyAndReportedAsRecovered() throws IOException {
    writeSettingsFile("");

    var result = storeAt(FIXED).load();

    assertThat(result).isEqualTo(new SettingsStore.LoadResult(Settings.defaults(), null, true));
    assertThat(namesIn(dir.resolve("bonds2"))).containsExactly("settings.properties");
    assertThat(Files.readAllLines(settingsFile(), StandardCharsets.UTF_8))
        .contains("database.path=", "database.autoOpen=true");
    // Файл уже содержит значения по умолчанию: повторная загрузка не считает его испорченным
    assertThat(storeAt(FIXED).load().recovered()).isFalse();
  }

  @Test
  void emptyFileIsStillReportedAsResetWhenItCannotBeRewritten() throws IOException {
    writeSettingsFile("");
    Path settingsDir = dir.resolve("bonds2");
    assumeTrue(isPosix());
    makeReadOnly(settingsDir);
    try {
      assumeFalse(Files.isWritable(settingsDir), "под администратором права не ограничиваются");

      var result = storeAt(FIXED).load();

      assertThat(result).isEqualTo(new SettingsStore.LoadResult(Settings.defaults(), null, true));
    } finally {
      makeWritable(settingsDir);
    }
    assertThat(settingsFile()).isEmptyFile();
  }

  private static boolean isPosix() {
    return FileSystems.getDefault().supportedFileAttributeViews().contains("posix");
  }

  /** Каталог только для чтения и просмотра: создать или удалить в нём файл нельзя. */
  private static void makeReadOnly(Path directory) throws IOException {
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
  }

  private static void makeWritable(Path directory) throws IOException {
    Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxr-xr-x"));
  }
}
