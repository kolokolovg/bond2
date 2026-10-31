// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StampedFilesTest {

  @TempDir Path dir;

  @Test
  void reserveCreatesEmptyFileWithExactBaseName() throws IOException {
    Path anchor = dir.resolve("settings.properties");
    String base = "settings.properties.bad-20260102-030405";

    Path created = StampedFiles.reserve(anchor, base);

    assertThat(created).isEqualTo(dir.resolve(base));
    assertThat(created).isRegularFile();
    assertThat(created).isEmptyFile();
  }

  @Test
  void reserveSkipsOccupiedNamesWithoutOverwriting() throws IOException {
    Path anchor = dir.resolve("settings.properties");
    String base = "settings.properties.bad";
    Path original = Files.writeString(dir.resolve(base), "испорченный файл");
    Path firstSuffix = Files.writeString(dir.resolve(base + "-1"), "обрывок записи");

    Path created = StampedFiles.reserve(anchor, base);

    assertThat(created).isEqualTo(dir.resolve(base + "-2"));
    assertThat(created).isEmptyFile();
    assertThat(Files.readString(original)).isEqualTo("испорченный файл");
    assertThat(Files.readString(firstSuffix)).isEqualTo("обрывок записи");
  }

  @Test
  void reserveSkipsDirectoryOccupyingTheName() throws IOException {
    Path anchor = dir.resolve("settings.properties");
    String base = "settings.properties.bad";
    Files.createDirectories(dir.resolve(base));

    Path created = StampedFiles.reserve(anchor, base);

    assertThat(created).isEqualTo(dir.resolve(base + "-1"));
    assertThat(created).isEmptyFile();
    assertThat(dir.resolve(base)).isDirectory();
  }

  @Test
  void stampFormatsFixedClock() {
    Clock clock =
        Clock.fixed(
            LocalDateTime.of(2026, 1, 2, 3, 4, 5).atZone(ZoneId.systemDefault()).toInstant(),
            ZoneId.systemDefault());

    assertThat(StampedFiles.stamp(clock)).isEqualTo("20260102-030405");
  }

  @Test
  void sweepDeletesOnlyMatchingRegularFiles() throws IOException {
    Path kept = Files.writeString(dir.resolve("keep.db"), "база данных");
    Path orphan1 = Files.writeString(dir.resolve("orphan-a.tmp"), "обрывок записи");
    Path orphan2 = Files.writeString(dir.resolve("orphan-b.tmp"), "обрывок записи");

    StampedFiles.sweepOrphans(dir, "*.tmp", "временный файл");

    assertThat(orphan1).doesNotExist();
    assertThat(orphan2).doesNotExist();
    assertThat(Files.readString(kept)).isEqualTo("база данных");
  }

  @Test
  void sweepKeepsMatchingNonRegularEntries() throws IOException {
    Path matchingDir = Files.createDirectories(dir.resolve("orphan-dir.tmp"));
    Path inner = Files.writeString(matchingDir.resolve("inner.txt"), "внутренний файл");
    Path matchingFile = Files.writeString(dir.resolve("orphan.tmp"), "обрывок записи");

    StampedFiles.sweepOrphans(dir, "*.tmp", "временный файл");

    assertThat(matchingDir).isDirectory();
    assertThat(inner).exists();
    assertThat(matchingFile).doesNotExist();
  }

  @Test
  void sweepContinuesWhenEntryCannotBeDeleted() throws IOException {
    Path swept = dir.resolve("swept");
    Files.createDirectories(swept);
    Path first = Files.writeString(swept.resolve("orphan-a.tmp"), "обрывок записи");
    Path second = Files.writeString(swept.resolve("orphan-b.tmp"), "обрывок записи");
    assumeTrue(isPosix());
    // Каталог только для чтения: удалить в нём файл нельзя
    makeReadOnly(swept);
    try {
      assumeFalse(Files.isWritable(swept), "под администратором права не ограничиваются");

      StampedFiles.sweepOrphans(swept, "*.tmp", "временный файл");

      assertThat(first).exists();
      assertThat(second).exists();
    } finally {
      makeWritable(swept);
    }
  }

  @Test
  void sweepMissingDirectoryIsNoOp() {
    Path absent = dir.resolve("absent");

    StampedFiles.sweepOrphans(absent, "*.tmp", "временный файл");

    assertThat(absent).doesNotExist();
  }

  @Test
  void sweepFileInsteadOfDirectoryIsNoOp() throws IOException {
    Path file = Files.writeString(dir.resolve("settings.properties"), "настройки");

    StampedFiles.sweepOrphans(file, "*.tmp", "временный файл");

    assertThat(Files.readString(file)).isEqualTo("настройки");
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
