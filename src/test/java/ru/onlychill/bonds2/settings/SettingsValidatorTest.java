package ru.onlychill.bonds2.settings;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsValidatorTest {

  @TempDir Path dir;

  @Test
  void emptyAndBlankPathsAreAllowed() {
    assertThat(SettingsValidator.validateDatabasePath("")).isEmpty();
    assertThat(SettingsValidator.validateDatabasePath(" \t ")).isEmpty();
  }

  @Test
  void newFileInExistingDirectoryIsAllowed() {
    assertThat(SettingsValidator.validateDatabasePath(dir.resolve("bonds.db").toString()))
        .isEmpty();
  }

  @Test
  void newFileInMissingNestedDirectoriesIsAllowed() {
    assertThat(SettingsValidator.validateDatabasePath(dir.resolve("a/b/bonds.db").toString()))
        .isEmpty();
  }

  @Test
  void existingFileIsAllowed() throws IOException {
    Path file = Files.writeString(dir.resolve("bonds.db"), "x");

    assertThat(SettingsValidator.validateDatabasePath(file.toString())).isEmpty();
  }

  @Test
  void surroundingSpacesAreIgnored() {
    String padded = "  " + dir.resolve("bonds.db") + "  ";

    assertThat(SettingsValidator.validateDatabasePath(padded)).isEmpty();
  }

  @Test
  void existingDirectoryIsRejectedWithMessage() {
    assertThat(SettingsValidator.validateDatabasePath(dir.toString()))
        .hasValueSatisfying(message -> assertThat(message).contains("каталог"));
  }

  @Test
  void filesystemRootIsRejectedAsHavingNoFileName() {
    assertThat(SettingsValidator.validateDatabasePath(dir.getRoot().toString()))
        .hasValueSatisfying(message -> assertThat(message).contains("имя файла"));
  }

  @Test
  void parentThatIsAFileIsRejected() throws IOException {
    Files.writeString(dir.resolve("blocker"), "x");

    assertThat(SettingsValidator.validateDatabasePath(dir.resolve("blocker/bonds.db").toString()))
        .isPresent();
    assertThat(
            SettingsValidator.validateDatabasePath(dir.resolve("blocker/sub/bonds.db").toString()))
        .isPresent();
  }

  @Test
  void relativePathIsRejected() {
    assertThat(SettingsValidator.validateDatabasePath("bonds.db"))
        .hasValueSatisfying(message -> assertThat(message).contains("полный путь"));
  }

  @Test
  void invalidPathCharactersAreRejected() {
    // NUL делает путь невалидным для Path, перевод строки — управляющий символ
    assertThat(SettingsValidator.validateDatabasePath(dir + "/bo\0nds.db"))
        .hasValueSatisfying(message -> assertThat(message).contains("недопустимые символы"));
    assertThat(SettingsValidator.validateDatabasePath(dir + "/bo\nnds.db"))
        .hasValueSatisfying(message -> assertThat(message).contains("недопустимые символы"));
  }
}
