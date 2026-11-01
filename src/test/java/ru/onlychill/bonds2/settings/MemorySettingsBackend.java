// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** In-memory backend хранилища настроек для тестов: карта значений с управляемыми сбоями. */
public final class MemorySettingsBackend implements SettingsStore.Backend {

  private final Map<String, String> values = new HashMap<>();

  /** Ключ, чтение которого падает; {@code null} — чтение исправно. */
  public @Nullable String failingReadKey;

  /** Следующий {@link #persist()} падает {@link UncheckedIOException}. */
  public boolean failOnPersist;

  @Override
  public Optional<String> read(String key) {
    if (key.equals(failingReadKey)) {
      throw new IllegalStateException("хранилище недоступно");
    }
    return Optional.ofNullable(values.get(key));
  }

  @Override
  public void write(String key, String value) {
    values.put(key, value);
  }

  @Override
  public void persist() {
    if (failOnPersist) {
      throw new UncheckedIOException(
          "Не удалось сохранить настройки", new IOException("хранилище недоступно"));
    }
  }
}
