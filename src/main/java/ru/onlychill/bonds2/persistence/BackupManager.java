// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
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
   * Копия создаётся рядом командой {@code VACUUM INTO} и проверяется {@code PRAGMA
   * integrity_check}. Если миграция завершилась ошибкой, файл атомарно заменяется копией. После
   * любой попытки миграции, успешной или неудачной, копии ограничиваются лимитом.
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
    } finally {
      // Лимит копий действует после любой попытки, в том числе неудачной; новая копия остаётся
      pruneBackups(absolute, backup);
    }
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
   * Создаёт копию командой {@code VACUUM INTO}: она читает согласованный снимок вместе с журналом
   * WAL, поэтому перенос WAL перед копированием не нужен. Имя копии занимает пустой файл, созданный
   * атомарно, поэтому существующая копия не перезаписывается. Если копию создать не удалось,
   * частично записанный файл удаляется.
   */
  private static Path createBackup(Path file, Clock clock) {
    String base = ".bak-" + LocalDateTime.now(clock).format(BACKUP_STAMP);
    Path backup = reserveBackupFile(file, base);
    try {
      SqliteSupport.vacuumInto(SqliteSupport.url(file), backup);
      return backup;
    } catch (SQLException e) {
      var failure =
          new MigrationException(
              "Не удалось создать резервную копию, миграция не выполнена: " + backup, e);
      SqliteSupport.deleteQuietly(backup, failure);
      throw failure;
    }
  }

  /**
   * Создаёт пустой файл с первым свободным именем: {@code base}, затем {@code base-1}, {@code
   * base-2} и так далее. Занятое имя (файл или каталог) не трогается.
   */
  private static Path reserveBackupFile(Path file, String base) {
    Path candidate = SqliteSupport.withSuffix(file, base);
    for (int i = 1; ; i++) {
      try {
        return Files.createFile(candidate);
      } catch (FileAlreadyExistsException e) {
        candidate = SqliteSupport.withSuffix(file, base + "-" + i);
      } catch (IOException e) {
        throw new MigrationException(
            "Не удалось создать резервную копию, миграция не выполнена: " + candidate, e);
      }
    }
  }

  /**
   * Проверяет копию; если она повреждена, удаляет её и сообщает об ошибке. Отсутствующий файл копии
   * — тоже ошибка: проверка начинается с наличия, чтобы исчезнувшая копия не «проходила» проверку
   * по заново созданному пустому файлу. Пакетный доступ нужен тестам: копию, которую создал {@code
   * VACUUM INTO}, проверочный отказ напрямую не получить.
   */
  static void verifyBackup(Path backup) {
    if (Files.notExists(backup)) {
      throw new MigrationException(
          "Резервная копия не найдена, миграция не выполнена: " + backup,
          new NoSuchFileException(backup.toString()));
    }
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
   * Оставляет новую копию {@code created} и самые новые из остальных копий файла БД, всего не
   * больше {@link #BACKUPS_KEPT}. Новая копия не участвует в сортировке, поэтому не удаляется при
   * любом порядке меток времени, например после перевода часов назад. Остальные копии упорядочены
   * по метке времени в имени, затем по числовому суффиксу. Другие файлы не затрагиваются, сбой
   * удаления пишется в лог.
   */
  private static void pruneBackups(Path file, Path created) {
    Path dir = file.getParent();
    var pattern =
        Pattern.compile(
            Pattern.quote(file.getFileName() + ".bak-") + "(\\d{8}-\\d{6})(?:-(\\d+))?");
    Path createdName = created.getFileName();
    record Found(Path path, String stamp, int number) {}
    List<Found> found = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      files.forEach(
          p -> {
            Matcher m = pattern.matcher(String.valueOf(p.getFileName()));
            if (m.matches() && Files.isRegularFile(p) && !createdName.equals(p.getFileName())) {
              found.add(new Found(p, m.group(1), m.group(2) == null ? 0 : parseSuffix(m.group(2))));
            }
          });
    } catch (IOException | UncheckedIOException e) {
      // Сбой просмотра не должен подменить ошибку миграции, которую prune выполняет в finally
      LOG.log(System.Logger.Level.WARNING, "Не удалось просмотреть резервные копии: " + dir, e);
      return;
    }
    found.sort(Comparator.comparing(Found::stamp).thenComparingInt(Found::number).reversed());
    for (Found old : found.subList(Math.min(BACKUPS_KEPT - 1, found.size()), found.size())) {
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

  /** Каталог файла БД: абсолютный путь к файлу БД всегда содержит родительский каталог. */
  private static Path directoryOf(Path file) {
    return Objects.requireNonNull(file.toAbsolutePath().getParent(), "каталог файла БД");
  }

  /**
   * Возвращает файл БД к состоянию резервной копии после неудачной миграции. К этому моменту
   * соединения Flyway уже закрыты. Копия сначала переносится во временный файл рядом с БД (в том же
   * каталоге, чтобы перемещение оставалось в пределах одной файловой системы) и сбрасывается на
   * диск — потеря питания сразу после замены не оставит укороченный файл. Затем зафиксированные
   * транзакции переносятся из WAL в основной файл ({@code wal_checkpoint(TRUNCATE)}), делая его
   * самодостаточным, удаляются служебные файлы WAL и журнала, и временный файл атомарно заменяет
   * БД; поэтому неудача замены не теряет зафиксированные транзакции, а частично записанная копия
   * никогда не окажется на месте БД. Если основной файл удалён снаружи, перенос WAL завершается
   * ошибкой и откат прерывается. При сбое временный файл удаляется, а ошибка несёт путь к копии для
   * ручного восстановления. Пакетный доступ нужен тестам.
   */
  static MigrationException restore(Path file, Path backup, RuntimeException cause) {
    Path staged = null;
    try {
      staged = Files.createTempFile(directoryOf(file), file.getFileName() + ".", ".restore");
      Files.copy(backup, staged, StandardCopyOption.REPLACE_EXISTING);
      // Канал закрывается до перемещения: на Windows перемещение открытого файла ненадёжно
      try (FileChannel channel = FileChannel.open(staged, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      SqliteSupport.checkpoint(SqliteSupport.url(file), Database.DEFAULT_BUSY_TIMEOUT);
      SqliteSupport.deleteSidecars(file);
      Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException | SQLException e) {
      cause.addSuppressed(e);
      if (staged != null) {
        SqliteSupport.deleteQuietly(staged, cause);
      }
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
