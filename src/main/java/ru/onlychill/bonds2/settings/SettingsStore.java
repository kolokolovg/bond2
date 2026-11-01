// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.settings;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Хранилище настроек в платформенном хранилище пользователя через {@link Backend}.
 *
 * <p>Чтение не бросает исключений: отсутствующее значение даёт значение по умолчанию, а
 * некорректное или нечитаемое — значение по умолчанию с записью события в журнал. Запись
 * выполняется синхронно и завершена к возврату из {@link #save(Settings)}; ошибка хранилища даёт
 * {@link UncheckedIOException}.
 */
public final class SettingsStore {

  /**
   * Минимальный шов между хранилищем настроек и платформенным backend'ом. Отделяет настройки от
   * глобального JVM-состояния {@code java.util.prefs}: продакшн-реализация — {@link
   * PreferencesBackend}, тесты работают с in-memory реализацией. Сбой хранилища при записи даёт
   * {@link UncheckedIOException}.
   */
  public interface Backend {

    /** Читает значение ключа; пуст, если значения нет. */
    Optional<String> read(String key);

    /** Записывает значение ключа. */
    void write(String key, String value);

    /**
     * Делает записанное устойчивым: без вызова значения на платформах с ленивой записью могут
     * остаться только в памяти процесса.
     */
    void persist();
  }

  /** Ключ пути к файлу БД; пустое значение означает путь по умолчанию. */
  static final String KEY_DATABASE_PATH = "database.path";

  /** Ключ флага автооткрытия БД при запуске. */
  static final String KEY_AUTO_OPEN = "database.autoOpen";

  private static final System.Logger LOG = System.getLogger(SettingsStore.class.getName());

  private final Backend backend;

  /** Хранилище настроек в платформенном хранилище текущего пользователя. */
  public SettingsStore() {
    this(new PreferencesBackend());
  }

  /**
   * Хранилище настроек поверх заданного backend'а; тесты используют in-memory реализацию вместо
   * глобального состояния {@code java.util.prefs}.
   */
  public SettingsStore(Backend backend) {
    this.backend = backend;
  }

  /**
   * Читает настройки. Отсутствующее значение даёт значение по умолчанию без события; значение, не
   * разбираемое как путь или флаг, и ошибка чтения дают значение по умолчанию с записью события в
   * журнал.
   */
  public Settings load() {
    return new Settings(readPath(), readAutoOpen());
  }

  /**
   * Сохраняет настройки синхронно; к возврату из метода запись устойчива.
   *
   * @throws UncheckedIOException если хранилище не приняло запись
   */
  public void save(Settings settings) {
    Path path = settings.databasePath();
    backend.write(KEY_DATABASE_PATH, path == null ? "" : path.toString());
    backend.write(KEY_AUTO_OPEN, Boolean.toString(settings.autoOpen()));
    backend.persist();
  }

  /**
   * Путь из хранилища: {@code null} (путь по умолчанию) для отсутствующего и пустого значения;
   * значение, не разбираемое как абсолютный путь, даёт {@code null} с записью события в журнал.
   */
  private @Nullable Path readPath() {
    Optional<String> raw = read(KEY_DATABASE_PATH).filter(text -> !text.isBlank());
    if (raw.isEmpty()) {
      return null;
    }
    String text = raw.orElseThrow();
    Path path = Settings.parseDatabasePath(text);
    if (path == null) {
      invalidValue(KEY_DATABASE_PATH, text);
    }
    return path;
  }

  /**
   * Флаг из хранилища: {@code true} или {@code false} без учёта регистра; отсутствующее значение
   * даёт {@link Settings#DEFAULT_AUTO_OPEN}, любое другое — то же значение по умолчанию с записью
   * события в журнал.
   */
  private boolean readAutoOpen() {
    Optional<String> raw = read(KEY_AUTO_OPEN);
    if (raw.isEmpty()) {
      return Settings.DEFAULT_AUTO_OPEN;
    }
    String text = raw.orElseThrow().strip();
    if (text.equalsIgnoreCase("true")) {
      return true;
    }
    if (text.equalsIgnoreCase("false")) {
      return false;
    }
    invalidValue(KEY_AUTO_OPEN, text);
    return Settings.DEFAULT_AUTO_OPEN;
  }

  /** Значение ключа; ошибка backend'а даёт пустое значение с записью события в журнал. */
  private Optional<String> read(String key) {
    try {
      return backend.read(key);
    } catch (RuntimeException e) {
      LOG.log(
          System.Logger.Level.WARNING,
          "Не удалось прочитать настройки " + key + "; применяется значение по умолчанию",
          e);
      return Optional.empty();
    }
  }

  private static void invalidValue(String key, String value) {
    LOG.log(
        System.Logger.Level.WARNING,
        "Некорректное значение настроек "
            + key
            + ": \""
            + value
            + "\"; применяется значение по умолчанию");
  }
}
