// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.settings;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * Backend хранилища настроек на {@link Preferences}: узел пакета настроек текущего пользователя
 * (реестр на Windows, XML-файл в домашнем каталоге на macOS и Linux). Запись устойчива после {@link
 * #persist()}; чтение идёт из уже загруженного узла и объявленных исключений не бросает.
 */
final class PreferencesBackend implements SettingsStore.Backend {

  private final Preferences node = Preferences.userNodeForPackage(Settings.class);

  @Override
  public Optional<String> read(String key) {
    return Optional.ofNullable(node.get(key, null));
  }

  @Override
  public void write(String key, String value) {
    node.put(key, value);
  }

  @Override
  public void persist() {
    try {
      node.flush();
    } catch (BackingStoreException e) {
      throw new UncheckedIOException("Не удалось сохранить настройки", new IOException(e));
    }
  }
}
