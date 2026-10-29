// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.nio.file.Path;
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
      String appData = env.apply("APPDATA");
      base =
          appData == null || appData.isBlank() ? home.resolve("AppData/Roaming") : Path.of(appData);
    } else if (os.contains("mac")) {
      base = home.resolve("Library/Application Support");
    } else {
      String xdg = env.apply("XDG_DATA_HOME");
      base = xdg == null || xdg.isBlank() ? home.resolve(".local/share") : Path.of(xdg);
    }
    return base.resolve("bonds2");
  }
}
