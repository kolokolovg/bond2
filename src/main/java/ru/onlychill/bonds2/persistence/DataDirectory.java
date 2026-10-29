// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.UnaryOperator;

/** Расположение файла БД в пользовательском каталоге данных. */
public final class DataDirectory {

  private DataDirectory() {}

  /** Файл БД для текущей ОС и окружения. */
  public static Path databaseFile() {
    return databaseFile(
        System.getProperty("os.name"), System::getenv, Path.of(System.getProperty("user.home")));
  }

  static Path databaseFile(String osName, UnaryOperator<String> env, Path home) {
    return appDirectory(osName, env, home).resolve("bonds2.db");
  }

  /** Файл настроек для текущей ОС и окружения: в том же каталоге, что и файл БД. */
  public static Path settingsFile() {
    return settingsFile(
        System.getProperty("os.name"), System::getenv, Path.of(System.getProperty("user.home")));
  }

  static Path settingsFile(String osName, UnaryOperator<String> env, Path home) {
    return appDirectory(osName, env, home).resolve("settings.properties");
  }

  /** Файл блокировки единственного экземпляра для текущей ОС и окружения. */
  public static Path lockFile() {
    return lockFile(
        System.getProperty("os.name"), System::getenv, Path.of(System.getProperty("user.home")));
  }

  static Path lockFile(String osName, UnaryOperator<String> env, Path home) {
    return appDirectory(osName, env, home).resolve("bonds2.lock");
  }

  /** Каталог приложения {@code bonds2} в пользовательском каталоге данных ОС. */
  private static Path appDirectory(String osName, UnaryOperator<String> env, Path home) {
    String os = osName.toLowerCase(java.util.Locale.ROOT);
    Path base;
    if (os.contains("win")) {
      // Local, а не Roaming: каталог содержит БД с WAL и файл блокировки
      base = absoluteEnv(env, "LOCALAPPDATA").orElse(home.resolve("AppData/Local"));
    } else if (os.contains("mac")) {
      base = home.resolve("Library/Application Support");
    } else {
      base = absoluteEnv(env, "XDG_DATA_HOME").orElse(home.resolve(".local/share"));
    }
    return base.resolve("bonds2");
  }

  /** Переменная окружения как путь: только непустая, корректная и абсолютная. */
  private static Optional<Path> absoluteEnv(UnaryOperator<String> env, String name) {
    String value = env.apply(name);
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    try {
      Path path = Path.of(value);
      return path.isAbsolute() ? Optional.of(path) : Optional.empty();
    } catch (InvalidPathException e) {
      return Optional.empty();
    }
  }
}
