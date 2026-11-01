// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.exec;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.queryString;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.sibling;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Резервная копия по указанию пользователя: {@link Database#backupTo(Path)}. */
class DatabaseBackupTest {

  @TempDir Path dir;

  private Path sourceWithRow(String value) {
    Path file = dir.resolve("app.db");
    var db = Database.create(file);
    db.inTransaction(
        c -> {
          try (var st = c.prepareStatement("INSERT INTO app_info VALUES ('k', ?)")) {
            st.setString(1, value);
            st.executeUpdate();
          } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
          }
          return null;
        });
    db.close();
    return file;
  }

  private List<String> dirContent() throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.map(p -> String.valueOf(p.getFileName())).sorted().toList();
    }
  }

  @Test
  void backupProducesWorkingVerifiedCopyOfCurrentState() throws Exception {
    Path file = sourceWithRow("v");
    Path target = dir.resolve("copies").resolve("my-copy.db");
    Files.createDirectories(java.util.Objects.requireNonNull(target.getParent()));

    try (var db = Database.open(file)) {
      db.backupTo(target);
    }

    assertThat(target).isRegularFile();
    try (var copy = Database.open(target)) {
      assertThat(queryString(copy, "SELECT value FROM app_info WHERE key = 'k'")).isEqualTo("v");
    }
    // Временный файл рядом с целью удалён
    assertThat(dirContent()).containsExactlyInAnyOrder("app.db", "copies");
  }

  @Test
  void backupIncludesDataThatIsStillOnlyInWalFile() throws Exception {
    Path file = sourceWithRow("v");
    Path target = dir.resolve("copy.db");
    Path wal = sibling(file, "-wal");
    try (var db = Database.open(file);
        var holder = DriverManager.getConnection("jdbc:sqlite:" + file)) {
      // Читатель держит WAL открытым: без чтения закрытие пишущего соединения перенесёт журнал
      assertThat(queryString(holder, "SELECT count(*) FROM app_info")).isEqualTo("1");
      db.inTransaction(
          c -> {
            exec(c, "UPDATE app_info SET value = 'in-wal' WHERE key = 'k'");
            return null;
          });
      assertThat(Files.readString(wal, StandardCharsets.ISO_8859_1)).contains("in-wal");

      db.backupTo(target);
    }

    try (var copy = Database.open(target)) {
      assertThat(queryString(copy, "SELECT value FROM app_info WHERE key = 'k'"))
          .isEqualTo("in-wal");
    }
  }

  @Test
  void backupReplacesExistingTargetFile() throws Exception {
    Path file = sourceWithRow("fresh");
    Path target = dir.resolve("copy.db");
    Files.writeString(target, "прежнее содержимое файла-цели");

    try (var db = Database.open(file)) {
      db.backupTo(target);
    }

    try (var copy = Database.open(target)) {
      assertThat(queryString(copy, "SELECT value FROM app_info WHERE key = 'k'"))
          .isEqualTo("fresh");
    }
  }

  @Test
  void backupFailureRemovesTempFileAndKeepsTargetAndDatabaseUsable() throws Exception {
    Path file = sourceWithRow("v");
    // Цель — каталог: временный файл создаётся и копия пишется, но замена цели невозможна
    Path target = Files.createDirectory(dir.resolve("copy.db"));
    Files.writeString(target.resolve("keep.txt"), "не трогать");

    try (var db = Database.open(file)) {
      assertThatThrownBy(() -> db.backupTo(target)).isInstanceOf(PersistenceException.class);
      // БД остаётся открытой и рабочей после неудачного копирования
      assertThat(queryString(db, "SELECT value FROM app_info WHERE key = 'k'")).isEqualTo("v");
    }

    // Частично записанный временный файл удалён, цель не тронута
    assertThat(target.resolve("keep.txt")).hasContent("не трогать");
    assertThat(dirContent()).containsExactlyInAnyOrder("app.db", "copy.db");
  }

  @Test
  void backupToMissingDirectoryFailsAndCreatesNothing() throws Exception {
    Path file = sourceWithRow("v");
    Path target = dir.resolve("no-such-dir").resolve("copy.db");

    try (var db = Database.open(file)) {
      assertThatThrownBy(() -> db.backupTo(target))
          .isInstanceOf(PersistenceException.class)
          .hasMessageContaining(target.toString());
    }

    assertThat(target).doesNotExist();
    assertThat(dirContent()).containsExactly("app.db");
  }

  @Test
  void backupOfRemovedDatabaseFileFailsWithoutCreatingEmptyFiles() throws IOException {
    Path file = sourceWithRow("v");
    Path target = dir.resolve("copy.db");

    var db = Database.open(file);
    Files.delete(file);
    assertThatThrownBy(() -> db.backupTo(target)).isInstanceOf(PersistenceException.class);
    // Закрытие БД без файла тоже ошибка — но она не создаёт пустой файл и не меняет итог
    assertThatThrownBy(db::close).isInstanceOf(PersistenceException.class);

    // Пустой файл не появляется ни на месте БД, ни на месте цели
    assertThat(file).doesNotExist();
    assertThat(target).doesNotExist();
  }

  @Test
  void backupOfClosedDatabaseIsRejected() {
    Path file = sourceWithRow("v");
    var db = Database.open(file);
    db.close();

    assertThatThrownBy(() -> db.backupTo(dir.resolve("copy.db")))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("закрыта");
  }

  @Test
  void backupOntoOpenDatabaseFileIsRejectedAndDatabaseKept() throws Exception {
    Path file = sourceWithRow("v");

    try (var db = Database.open(file)) {
      // Замена открытой БД снимком отбросила бы данные, зафиксированные после начала копирования
      assertThatThrownBy(() -> db.backupTo(file))
          .isInstanceOf(PersistenceException.class)
          .hasMessageContaining("файл приложения");
      assertThat(queryString(db, "SELECT value FROM app_info WHERE key = 'k'")).isEqualTo("v");
    }
  }

  @Test
  void backupOntoSidecarAndServiceFilesIsRejected() throws Exception {
    Path file = sourceWithRow("v");

    try (var db = Database.open(file)) {
      for (String name :
          List.of(
              file.getFileName() + "-wal",
              file.getFileName() + "-shm",
              file.getFileName() + "-journal",
              "bonds2.pre-migration.db")) {
        assertThatThrownBy(() -> db.backupTo(dir.resolve(name)))
            .as("цель %s", name)
            .isInstanceOf(PersistenceException.class)
            .hasMessageContaining("файл приложения");
      }
      // Ни один из запрещённых путей не создан и не изменён
      assertThat(dirContent()).containsExactly("app.db");
    }
  }
}
