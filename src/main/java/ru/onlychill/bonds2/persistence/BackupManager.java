// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Резервные копии файла БД и его откат: копия перед миграцией, проверка копии, восстановление после
 * неудачной миграции, хранение последних копий и возврат файла к пустому состоянию после неудачной
 * инициализации.
 */
final class BackupManager {

  private static final DateTimeFormatter BACKUP_STAMP =
      DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

  /** Сколько резервных копий файла БД хранится после успешной миграции. */
  private static final int BACKUPS_KEPT = 3;

  private static final System.Logger LOG = System.getLogger(BackupManager.class.getName());

  private BackupManager() {}

  /**
   * Миграция существующей БД с резервной копией; см. {@link Database#migrateWithBackup(Path)}.
   * Перед миграцией WAL переносится в основной файл, копия создаётся рядом и проверяется {@code
   * PRAGMA integrity_check}. Если миграция завершилась ошибкой, файл восстанавливается из копии.
   *
   * @param absolute абсолютный путь к файлу БД
   * @return путь к резервной копии
   */
  static Path migrateWithBackup(Path absolute, Clock clock, String... locations) {
    Database.State state = SchemaInspector.inspect(absolute, locations);
    if (state != Database.State.NEEDS_MIGRATION) {
      throw new PersistenceException(refusal(state, absolute));
    }
    Path backup = createBackup(absolute, clock);
    verifyBackup(backup);
    try {
      SchemaInspector.flyway(SqliteSupport.url(absolute), locations).migrate();
    } catch (RuntimeException e) {
      throw restore(absolute, backup, e);
    }
    pruneBackups(absolute);
    return backup;
  }

  /**
   * Возвращает существующий файл к пустому состоянию после неудачной инициализации: удаляет
   * служебные файлы WAL и журнала и обрезает основной файл до 0 байт. Ошибка добавляется к {@code
   * cause}.
   */
  static void resetToEmpty(Path file, Throwable cause) {
    try {
      SqliteSupport.deleteSidecars(file);
      Files.write(file, new byte[0]);
    } catch (IOException e) {
      cause.addSuppressed(e);
    }
  }

  /**
   * Переносит WAL в основной файл и копирует его рядом; существующую копию не перезаписывает, а при
   * совпадении имени добавляет числовой суффикс.
   */
  private static Path createBackup(Path file, Clock clock) {
    String base = ".bak-" + LocalDateTime.now(clock).format(BACKUP_STAMP);
    Path backup = SqliteSupport.withSuffix(file, base);
    try {
      SqliteSupport.checkpoint(SqliteSupport.url(file));
      for (int i = 1; ; i++) {
        try {
          Files.copy(file, backup);
          return backup;
        } catch (FileAlreadyExistsException e) {
          backup = SqliteSupport.withSuffix(file, base + "-" + i);
        }
      }
    } catch (SQLException e) {
      throw new MigrationException(
          "Не удалось перенести WAL в файл БД, миграция не выполнена: " + file, e);
    } catch (IOException e) {
      var failure =
          new MigrationException(
              "Не удалось создать резервную копию, миграция не выполнена: " + backup, e);
      SqliteSupport.deleteQuietly(backup, failure);
      throw failure;
    }
  }

  /** Проверяет копию; если она повреждена, удаляет её и сообщает об ошибке. */
  private static void verifyBackup(Path backup) {
    String result;
    try {
      result = SqliteSupport.integrityCheck(SqliteSupport.url(backup));
    } catch (SQLException e) {
      throw rejectBackup(backup, e);
    }
    if (!"ok".equals(result)) {
      throw rejectBackup(backup, new SQLException("PRAGMA integrity_check: " + result));
    }
  }

  private static MigrationException rejectBackup(Path backup, SQLException cause) {
    var failure =
        new MigrationException(
            "Резервная копия не прошла проверку целостности, миграция не выполнена: " + backup,
            cause);
    SqliteSupport.deleteQuietly(backup, failure);
    return failure;
  }

  /**
   * Оставляет {@link #BACKUPS_KEPT} самых новых копий файла БД; порядок — по метке времени в имени,
   * затем по числовому суффиксу. Другие файлы не затрагиваются, сбой удаления пишется в лог.
   */
  private static void pruneBackups(Path file) {
    Path dir = file.getParent();
    var pattern =
        Pattern.compile(
            Pattern.quote(file.getFileName() + ".bak-") + "(\\d{8}-\\d{6})(?:-(\\d+))?");
    record Found(Path path, String stamp, int number) {}
    List<Found> found = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      files.forEach(
          p -> {
            Matcher m = pattern.matcher(String.valueOf(p.getFileName()));
            if (m.matches() && Files.isRegularFile(p)) {
              found.add(new Found(p, m.group(1), m.group(2) == null ? 0 : parseSuffix(m.group(2))));
            }
          });
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось просмотреть резервные копии: " + dir, e);
      return;
    }
    found.sort(Comparator.comparing(Found::stamp).thenComparingInt(Found::number).reversed());
    for (Found old : found.subList(Math.min(BACKUPS_KEPT, found.size()), found.size())) {
      try {
        Files.deleteIfExists(old.path());
      } catch (IOException e) {
        LOG.log(System.Logger.Level.WARNING, "Не удалось удалить старую копию: " + old.path(), e);
      }
    }
  }

  private static int parseSuffix(String digits) {
    try {
      return Integer.parseInt(digits);
    } catch (NumberFormatException e) {
      return Integer.MAX_VALUE;
    }
  }

  /**
   * Возвращает файл БД к состоянию резервной копии после неудачной миграции. К этому моменту
   * соединения Flyway уже закрыты; служебные файлы WAL и журнала удаляются, чтобы не применяться к
   * восстановленному файлу.
   */
  private static MigrationException restore(Path file, Path backup, RuntimeException cause) {
    try {
      SqliteSupport.deleteSidecars(file);
      Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      cause.addSuppressed(e);
      return new MigrationException(
          "Миграция не выполнена, и восстановить БД из резервной копии не удалось. Восстановите"
              + " файл вручную из: "
              + backup,
          cause);
    }
    return new MigrationException(
        "Миграция не выполнена, БД восстановлена из резервной копии "
            + backup
            + ": "
            + cause.getMessage(),
        cause);
  }

  /** Причина отказа в миграции для состояния, кроме {@link Database.State#NEEDS_MIGRATION}. */
  private static String refusal(Database.State state, Path file) {
    return switch (state) {
      case TOO_NEW -> "Схема БД новее приложения, миграция невозможна: " + file;
      case EMPTY -> "Файл БД пуст, миграция невозможна: " + file;
      case FOREIGN -> "Файл не база данных приложения, миграция невозможна: " + file;
      default -> "Миграция не требуется, схема БД актуальна: " + file;
    };
  }
}
