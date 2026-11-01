// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Резервные копии файла БД. Два применения одной операции «проверенная копия»: служебная копия
 * фиксированного имени перед миграцией и копия по указанию пользователя. Копия создаётся командой
 * {@code VACUUM INTO} во временный файл рядом с целью, проверяется {@code PRAGMA integrity_check},
 * сбрасывается на диск и атомарно заменяет прежний файл цели; частично записанный временный файл
 * никогда не оказывается на месте цели.
 */
final class BackupManager {

  /** Имя служебной копии перед миграцией в каталоге данных приложения. */
  private static final String PRE_MIGRATION_NAME = "bonds2.pre-migration.db";

  private BackupManager() {}

  /**
   * Миграция существующей БД со служебной копией; см. {@link Database#migrateWithBackup(Path)}.
   * Копия фиксированного имени {@value #PRE_MIGRATION_NAME} атомарно перезаписывается; если копию
   * создать или проверить не удалось, миграция не выполняется и файл БД не изменяется. Набор
   * миграций применяется одной транзакцией: его сбой откатывает транзакция, восстановление из копии
   * не выполняется — файл остаётся до-миграционным.
   *
   * @param absolute абсолютный путь к файлу БД
   * @throws PersistenceException если файл уже открыт этим экземпляром приложения, миграция не
   *     нужна, схема новее приложения, файл пуст или не является БД приложения
   * @throws MigrationException если служебную копию создать или проверить не удалось (миграция не
   *     выполнена, временный файл удалён, прежняя копия не тронута) либо миграция завершилась
   *     ошибкой (транзакция откачена, файл до-миграционный)
   */
  static void migrateWithBackup(Path absolute, String... locations) {
    Database.State state = SchemaInspector.inspect(absolute, locations);
    if (state != Database.State.NEEDS_MIGRATION) {
      throw new PersistenceException(refusal(state, absolute));
    }
    preMigrationCopy(absolute);
    Migrations.applyPending(absolute, locations);
  }

  /**
   * Служебная копия состояния до миграции: {@value #PRE_MIGRATION_NAME} в каталоге файла БД,
   * атомарно перезаписываемая. Порядок: {@code VACUUM INTO} во временный файл того же каталога →
   * проверка целостности → сброс на диск (канал закрыт до перемещения: на Windows перемещение
   * открытого файла ненадёжно) → атомарная замена прежней копии. Сбой любого шага удаляет временный
   * файл, прежняя копия остаётся, миграция не выполняется.
   *
   * @throws MigrationException если копию создать или проверить не удалось
   */
  static void preMigrationCopy(Path file) {
    Path target = file.resolveSibling(PRE_MIGRATION_NAME);
    try {
      writeVerifiedCopy(file, target, false);
    } catch (PersistenceException e) {
      throw new MigrationException(
          "Не удалось создать служебную копию "
              + target
              + ", миграция не выполнена. Файл БД не изменён.",
          e);
    }
  }

  /**
   * Резервная копия по указанию пользователя: {@code VACUUM INTO} во временный файл рядом с целью
   * (цель может быть на другой файловой системе, чем БД), проверка целостности, сброс на диск и
   * замена цели — атомарная, а при невозможности обычным перемещением с заменой. Решение о замене
   * существующей цели подтверждает сам диалог сохранения. При любом сбое цель не тронута, временный
   * файл удалён.
   *
   * <p>Целью не может стать файл самой БД, её служебные файлы WAL/журнала, служебная копия перед
   * миграцией или файл блокировки экземпляра: замена открытой БД снимком молча отбросила бы
   * зафиксированные после начала копирования данные, а подмена блокировки лишила бы смысла защиту
   * от второго экземпляра.
   *
   * @throws PersistenceException если цель запрещена, либо копию создать, проверить или поставить
   *     на место не удалось; исходная причина сохранена в цепочке
   */
  static void userCopy(Path sourceFile, Path target) {
    Path goal = target.toAbsolutePath();
    rejectForbiddenTarget(sourceFile, goal);
    writeVerifiedCopy(sourceFile, goal, true);
  }

  /**
   * Отвергает цель, совпадающую с файлами приложения: сама БД, её {@code -wal}/{@code -shm}/{@code
   * -journal}, служебная копия миграции и {@code bonds2.lock}. Сравнение — по реальным путям
   * существующих файлов (символические ссылки раскрыты) и по нормализованным для отсутствующих.
   */
  private static void rejectForbiddenTarget(Path source, Path target) {
    List<Path> forbidden = new ArrayList<>(SqliteSupport.sidecars(source));
    forbidden.add(source);
    forbidden.add(source.resolveSibling(PRE_MIGRATION_NAME));
    forbidden.add(DataDirectory.lockFile());
    for (Path file : forbidden) {
      if (sameFile(file, target)) {
        throw new PersistenceException(
            "Резервная копия не может заменить файл приложения: " + target);
      }
    }
  }

  /** Одно и то же место на диске: для существующих путей — с раскрытием символических ссылок. */
  private static boolean sameFile(Path a, Path b) {
    try {
      return Files.isSameFile(a, b);
    } catch (IOException e) {
      // Цель может ещё не существовать: сравниваем нормализованные абсолютные пути
      return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }
  }

  /**
   * Копия командой {@code VACUUM INTO}: она читает согласованный снимок вместе с журналом WAL,
   * поэтому перенос WAL перед копированием не нужен. Временный файл создаётся рядом с целью: к
   * моменту замены копия уже на целевой файловой системе. {@code VACUUM INTO} требует отсутствующий
   * или пустой файл назначения — созданный {@code createTempFile} пустой файл подходит. {@code
   * plainMoveFallback} разрешает замену обычным перемещением, когда атомарное не поддерживается
   * файловой системой.
   */
  private static void writeVerifiedCopy(Path source, Path target, boolean plainMoveFallback) {
    Path temp = null;
    try {
      // Цель абсолютна по построению, поэтому каталог у неё есть
      Path targetDirectory = Objects.requireNonNull(target.getParent(), "каталог цели копии");
      temp = Files.createTempFile(targetDirectory, target.getFileName() + ".", ".part");
      SqliteSupport.vacuumInto(SqliteSupport.url(source), temp);
      verifyCopy(temp);
      // Канал закрывается до перемещения: на Windows перемещение открытого файла ненадёжно
      try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
        channel.force(true);
      }
      replace(temp, target, plainMoveFallback);
    } catch (IOException | SQLException | RuntimeException e) {
      if (temp != null) {
        SqliteSupport.deleteQuietly(temp, e);
      }
      throw new PersistenceException("Не удалось создать резервную копию: " + target, e);
    }
  }

  /**
   * Проверяет копию; повреждённый файл не становится копией. Отсутствующий файл копии — тоже
   * ошибка: проверка начинается с наличия, чтобы исчезнувшая копия не «проходила» проверку по
   * заново созданному пустому файлу. Пакетный доступ нужен тестам.
   */
  static void verifyCopy(Path copy) throws SQLException {
    if (Files.notExists(copy)) {
      throw new SQLException("Резервная копия не найдена: " + copy);
    }
    String result = SqliteSupport.integrityCheck(SqliteSupport.url(copy));
    if (!"ok".equals(result)) {
      throw new SQLException("PRAGMA integrity_check: " + result);
    }
  }

  /** Заменяет цель копией: атомарно в пределах каталога, при поддержке файловой системой. */
  private static void replace(Path temp, Path target, boolean plainMoveFallback)
      throws IOException {
    try {
      Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException e) {
      if (!plainMoveFallback) {
        throw e;
      }
      Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
    }
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
