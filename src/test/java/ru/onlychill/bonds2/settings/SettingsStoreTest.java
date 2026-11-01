// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Тесты хранилища настроек на in-memory backend'е: раундтрип, значения по умолчанию при отсутствии
 * и некорректности значения, дефолты при сбое чтения, ошибка записи. Предупреждения проверяются
 * через хендлер {@code java.util.logging}: системный логгер по умолчанию пишется туда.
 */
class SettingsStoreTest {

  private final MemorySettingsBackend backend = new MemorySettingsBackend();
  private final SettingsStore store = new SettingsStore(backend);

  private final RecordingHandler handler = new RecordingHandler();
  private final Logger log = Logger.getLogger(SettingsStore.class.getName());

  @BeforeEach
  void attachHandler() {
    handler.setLevel(java.util.logging.Level.ALL);
    log.addHandler(handler);
  }

  @AfterEach
  void detachHandler() {
    log.removeHandler(handler);
  }

  @Test
  void emptyBackendGivesDefaults() {
    assertThat(store.load()).isEqualTo(Settings.defaults());
    assertThat(handler.warnings()).isEmpty();
  }

  @Test
  void savedValuesAreRestoredOnNextLoad(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
    var custom = new Settings(dir.resolve("user.db"), false);

    store.save(custom);

    assertThat(store.load()).isEqualTo(custom);
    assertThat(handler.warnings()).isEmpty();
  }

  @Test
  void savedDefaultsRoundTrip() {
    store.save(Settings.defaults());

    assertThat(store.load()).isEqualTo(Settings.defaults());
  }

  @Test
  void missingKeysGiveDefaultsAndNoWarning(
      @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
    backend.write(SettingsStore.KEY_DATABASE_PATH, dir.resolve("user.db").toString());

    var loaded = store.load();

    assertThat(loaded.databasePath()).isEqualTo(dir.resolve("user.db"));
    assertThat(loaded.autoOpen()).isTrue();
    assertThat(handler.warnings()).isEmpty();
  }

  @Test
  void blankPathMeansDefaultPathWithoutWarning() {
    backend.write(SettingsStore.KEY_DATABASE_PATH, "   ");

    assertThat(store.load().databasePath()).isNull();
    assertThat(handler.warnings()).isEmpty();
  }

  @Test
  void relativePathValueGivesDefaultAndLogsWarning() {
    backend.write(SettingsStore.KEY_DATABASE_PATH, "relative/user.db");

    assertThat(store.load().databasePath()).isNull();
    assertThat(handler.warnings()).anyMatch(w -> w.contains(SettingsStore.KEY_DATABASE_PATH));
  }

  @Test
  void invalidFlagValueGivesDefaultAndLogsWarning() {
    backend.write(SettingsStore.KEY_AUTO_OPEN, "да");

    assertThat(store.load().autoOpen()).isTrue();
    assertThat(handler.warnings()).anyMatch(w -> w.contains(SettingsStore.KEY_AUTO_OPEN));
  }

  @Test
  void flagIsReadCaseInsensitively() {
    backend.write(SettingsStore.KEY_AUTO_OPEN, "FALSE");

    assertThat(store.load().autoOpen()).isFalse();
    assertThat(handler.warnings()).isEmpty();
  }

  @Test
  void failingGetGivesDefaultsAndLogsWarning() {
    backend.failingReadKey = SettingsStore.KEY_DATABASE_PATH;

    assertThat(store.load()).isEqualTo(Settings.defaults());
    assertThat(handler.warnings()).anyMatch(w -> w.contains(SettingsStore.KEY_DATABASE_PATH));
  }

  @Test
  void flushFailureThrowsUncheckedIOException() {
    backend.failOnPersist = true;

    assertThatThrownBy(() -> store.save(Settings.defaults()))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("Не удалось сохранить настройки");
  }

  /** Собирает предупреждения системного логгера, который по умолчанию пишет в JUL. */
  private static final class RecordingHandler extends Handler {

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @Override
    public void publish(LogRecord record) {
      if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
        warnings.add(record.getMessage());
      }
    }

    List<String> warnings() {
      return warnings;
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}
  }
}
