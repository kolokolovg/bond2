// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.persistence.Database;

/**
 * Настоящие операции с файлами БД, которыми можно управлять в тестах: задерживать открытие и
 * миграцию, подменять сбой и смотреть, какие БД были открыты.
 */
public final class GatedStorage implements DatabaseService.Storage {

  /** Задержка операции: пока закрыта, операция стоит на входе. */
  public static final class Gate {

    private volatile @Nullable CountDownLatch release;
    private final CountDownLatch entered = new CountDownLatch(1);

    /** Закрывает ворота: следующая операция остановится. */
    public void hold() {
      release = new CountDownLatch(1);
    }

    /** Открывает ворота. */
    public void release() {
      CountDownLatch latch = release;
      if (latch != null) {
        latch.countDown();
      }
    }

    /** Ждёт, пока операция дойдёт до ворот. */
    public boolean awaitEntered() throws InterruptedException {
      return entered.await(10, TimeUnit.SECONDS);
    }

    void pass() {
      CountDownLatch latch = release;
      if (latch == null) {
        return;
      }
      entered.countDown();
      try {
        if (!latch.await(30, TimeUnit.SECONDS)) {
          throw new IllegalStateException("Ворота не открыли");
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  public final Gate openGate = new Gate();
  public final Gate migrateGate = new Gate();

  /** Открытые через это хранилище БД. */
  public final List<Database> opened = new CopyOnWriteArrayList<>();

  /** Потоки, в которых выполнялись операции с файлами БД. */
  public final List<Thread> threads = new CopyOnWriteArrayList<>();

  /** Если задано, открытие бросает это исключение или ошибку среды выполнения. */
  public volatile @Nullable Throwable openFailure;

  /** Если задано, закрытие бросает это исключение или ошибку после настоящего закрытия БД. */
  public volatile @Nullable Throwable closeFailure;

  @Override
  public Database.State inspect(Path file) {
    enter(openGate);
    return Database.inspect(file);
  }

  @Override
  public Database open(Path file) {
    enter(openGate);
    throwIfSet(openFailure);
    return track(Database.open(file));
  }

  @Override
  public void close(Database database) {
    // Настоящее закрытие первым: сбой подменяется уже после него, файл БД остаётся согласованным
    database.close();
    throwIfSet(closeFailure);
  }

  @Override
  public Database create(Path file) {
    enter(openGate);
    return track(Database.create(file));
  }

  @Override
  public Database initialize(Path file) {
    enter(openGate);
    return track(Database.initialize(file));
  }

  @Override
  public void migrateWithBackup(Path file) {
    enter(migrateGate);
    Database.migrateWithBackup(file);
  }

  /** Запоминает поток операции и проходит через ворота. */
  private void enter(Gate gate) {
    threads.add(Thread.currentThread());
    gate.pass();
  }

  /** Бросает заданный сбой: исключение среды выполнения или ошибка; без сбоя ничего не делает. */
  private static void throwIfSet(@Nullable Throwable failure) {
    if (failure instanceof RuntimeException runtime) {
      throw runtime;
    }
    if (failure instanceof Error error) {
      throw error;
    }
  }

  private Database track(Database database) {
    opened.add(database);
    return database;
  }
}
