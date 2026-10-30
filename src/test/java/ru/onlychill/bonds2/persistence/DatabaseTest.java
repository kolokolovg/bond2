// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.exec;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.queryLong;
import static ru.onlychill.bonds2.persistence.DatabaseTestSupport.sibling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseTest {

  @TempDir Path dir;

  private Path testFile() {
    return dir.resolve("data/test.db");
  }

  private static String pragma(Connection c, String name) throws SQLException {
    try (var st = c.createStatement();
        var rs = st.executeQuery("PRAGMA " + name)) {
      rs.next();
      return rs.getString(1);
    }
  }

  private static long counter(Connection c) throws SQLException {
    return queryLong(c, "SELECT value FROM counter WHERE id = 1");
  }

  private static long read(Database db) {
    return db.inReadTransaction(DatabaseTest::counter);
  }

  private static String pragmaOf(Database db, String name) {
    return db.inTransaction(c -> pragma(c, name));
  }

  private static void await(CountDownLatch latch) {
    try {
      assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  private Database withCounter(Duration timeout) {
    var db = Database.create(testFile(), timeout);
    db.inTransaction(
        c -> {
          exec(c, "CREATE TABLE IF NOT EXISTS counter (id INTEGER PRIMARY KEY, value INTEGER)");
          exec(c, "INSERT OR REPLACE INTO counter VALUES (1, 0)");
          return null;
        });
    return db;
  }

  /**
   * Открывает читателя с читающей транзакцией на текущем снимке и фиксирует запись: пока читатель
   * держит старый снимок, WAL нельзя перенести в основной файл. Соединение закрывает вызывающий.
   */
  private Connection readerBehindWrite(Database db) throws SQLException {
    Connection reader = DriverManager.getConnection("jdbc:sqlite:" + testFile());
    reader.setAutoCommit(false);
    try (var st = reader.createStatement();
        var rs = st.executeQuery("SELECT count(*) FROM counter")) {
      rs.next();
    }
    db.inTransaction(
        c -> {
          exec(c, "UPDATE counter SET value = 5");
          return null;
        });
    return reader;
  }

  @Test
  void connectionsUseWalForeignKeysAndBusyTimeout() {
    var db = Database.create(dir.resolve("p.db"), Duration.ofMillis(1234));
    assertThat(pragmaOf(db, "journal_mode")).isEqualTo("wal");
    assertThat(pragmaOf(db, "foreign_keys")).isEqualTo("1");
    assertThat(pragmaOf(db, "busy_timeout")).isEqualTo("1234");
  }

  @Test
  void successfulTransactionIsVisibleAfterwards() {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    db.inTransaction(
        c -> {
          exec(c, "UPDATE counter SET value = 42");
          return null;
        });
    assertThat(read(db)).isEqualTo(42);
  }

  @Test
  void exceptionRollsBackAndPropagates() {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    assertThatThrownBy(
            () ->
                db.inTransaction(
                    c -> {
                      exec(c, "UPDATE counter SET value = 99");
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");
    assertThat(read(db)).isZero();
  }

  @Test
  void sqlErrorIsWrappedAndRolledBack() {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    assertThatThrownBy(
            () ->
                db.inTransaction(
                    c -> {
                      exec(c, "UPDATE counter SET value = 5");
                      exec(c, "THIS IS NOT SQL");
                      return null;
                    }))
        .isInstanceOf(PersistenceException.class)
        .hasCauseInstanceOf(SQLException.class);
    assertThat(read(db)).isZero();
  }

  @Test
  void concurrentIncrementsAreNotLost() throws Exception {
    var db = withCounter(Duration.ofSeconds(30));
    int threads = 8;
    int perThread = 25;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    var start = new CountDownLatch(1);
    List<Future<?>> futures = new ArrayList<>();
    for (int t = 0; t < threads; t++) {
      futures.add(
          pool.submit(
              () -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                  db.inTransaction(
                      c -> {
                        long v = counter(c);
                        exec(c, "UPDATE counter SET value = " + (v + 1));
                        return null;
                      });
                }
                return null;
              }));
    }
    start.countDown();
    for (Future<?> f : futures) {
      f.get(60, TimeUnit.SECONDS);
    }
    pool.shutdown();
    assertThat(read(db)).isEqualTo(threads * perThread);
  }

  @Test
  void readerSeesOnlyCommittedDataDuringWrite() throws Exception {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    var written = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<?> writer =
        pool.submit(
            () ->
                db.inTransaction(
                    c -> {
                      exec(c, "UPDATE counter SET value = 7");
                      written.countDown();
                      await(release);
                      return null;
                    }));
    assertThat(written.await(30, TimeUnit.SECONDS)).isTrue();

    long seen = read(db);

    release.countDown();
    writer.get(30, TimeUnit.SECONDS);
    pool.shutdown();
    assertThat(seen).isZero();
    assertThat(read(db)).isEqualTo(7);
  }

  @Test
  void writerWaitsForShortLock() throws Exception {
    var db = withCounter(Duration.ofSeconds(10));
    var locked = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<?> holder =
        pool.submit(
            () ->
                db.inTransaction(
                    c -> {
                      exec(c, "UPDATE counter SET value = 1");
                      locked.countDown();
                      try {
                        Thread.sleep(500);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                      }
                      return null;
                    }));
    assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

    db.inTransaction(
        c -> {
          exec(c, "UPDATE counter SET value = value + 1");
          return null;
        });

    holder.get(30, TimeUnit.SECONDS);
    pool.shutdown();
    assertThat(read(db)).isEqualTo(2);
  }

  @Test
  void lockTimeoutFailsExplicitlyAndRollsBack() throws Exception {
    var db = withCounter(Duration.ofMillis(200));
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<?> holder =
        pool.submit(
            () ->
                db.inTransaction(
                    c -> {
                      exec(c, "UPDATE counter SET value = 1");
                      locked.countDown();
                      await(release);
                      return null;
                    }));
    assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

    assertThatThrownBy(
            () ->
                db.inTransaction(
                    c -> {
                      exec(c, "UPDATE counter SET value = 100");
                      return null;
                    }))
        .isInstanceOf(PersistenceException.class);

    release.countDown();
    holder.get(30, TimeUnit.SECONDS);
    pool.shutdown();
    assertThat(read(db)).isEqualTo(1);
  }

  @Test
  void closeWaitsForActiveTransactionAndKeepsItsChanges() throws Exception {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    var updated = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> writer =
          pool.submit(
              () ->
                  db.inTransaction(
                      c -> {
                        exec(c, "UPDATE counter SET value = 42");
                        updated.countDown();
                        await(release);
                        return null;
                      }));
      assertThat(updated.await(30, TimeUnit.SECONDS)).isTrue();

      Future<?> closing = pool.submit(db::close);
      assertThatThrownBy(() -> closing.get(300, TimeUnit.MILLISECONDS))
          .isInstanceOf(TimeoutException.class);

      release.countDown();
      writer.get(30, TimeUnit.SECONDS);
      closing.get(30, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    try (var reopened = Database.open(testFile())) {
      assertThat(read(reopened)).isEqualTo(42);
    }
  }

  @Test
  void useAfterCloseIsRejected() {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    db.close();
    assertThatThrownBy(() -> db.inTransaction(c -> null))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("закрыта");
    assertThatThrownBy(() -> db.inReadTransaction(c -> null))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("закрыта");
  }

  @Test
  void closeIsIdempotent() {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    db.close();
    assertThatCode(db::close).doesNotThrowAnyException();
  }

  @Test
  void closeRejectsCallFromInsideOwnTransaction() {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    db.inTransaction(
        c -> {
          assertThatThrownBy(db::close).isInstanceOf(IllegalStateException.class);
          exec(c, "UPDATE counter SET value = 3");
          return null;
        });
    assertThat(read(db)).isEqualTo(3);
    db.close();
  }

  @Test
  void closeLeavesNoNonEmptyWal() throws Exception {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    Path wal = sibling(testFile(), "-wal");
    // Открытое, но простаивающее соединение не даёт SQLite удалить WAL при закрытии транзакций
    try (Connection holder = DriverManager.getConnection("jdbc:sqlite:" + testFile())) {
      try (var st = holder.createStatement();
          var rs = st.executeQuery("SELECT count(*) FROM counter")) {
        rs.next();
      }
      db.inTransaction(
          c -> {
            exec(c, "UPDATE counter SET value = 9");
            return null;
          });
      assertThat(wal).isNotEmptyFile();

      db.close();

      assertThat(wal)
          .satisfiesAnyOf(p -> assertThat(p).doesNotExist(), p -> assertThat(p).isEmptyFile());
    }
  }

  @Test
  void closeAfterFileDeletionFailsWithoutCreatingFile() throws IOException {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    Files.delete(testFile());

    assertThatThrownBy(db::close)
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("WAL")
        .hasCauseInstanceOf(SQLException.class);
    assertThat(testFile()).doesNotExist();
    assertThatThrownBy(() -> db.inTransaction(c -> null))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("закрыта");
  }

  @Test
  void closeWithExistingFileTransfersWalIntoMainFile() throws Exception {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    Path wal = sibling(testFile(), "-wal");
    Path copy = dir.resolve("copy.db");
    // Простаивающее соединение не даёт SQLite перенести WAL при закрытии последнего соединения
    try (Connection holder = DriverManager.getConnection("jdbc:sqlite:" + testFile())) {
      try (var st = holder.createStatement();
          var rs = st.executeQuery("SELECT count(*) FROM counter")) {
        rs.next();
      }
      db.inTransaction(
          c -> {
            exec(c, "UPDATE counter SET value = 21");
            return null;
          });
      assertThat(wal).isNotEmptyFile();

      db.close();

      assertThat(wal)
          .satisfiesAnyOf(p -> assertThat(p).doesNotExist(), p -> assertThat(p).isEmptyFile());
      // Журнал пуст, значит значение 21 есть в основном файле: копия одного файла его сохраняет
      Files.copy(testFile(), copy);
    }
    try (var reopened = Database.open(copy)) {
      assertThat(read(reopened)).isEqualTo(21);
    }
  }

  @Test
  void closeMarksDatabaseClosedEvenWhenCheckpointFails() throws SQLException {
    var db = withCounter(Duration.ofMillis(100));

    try (Connection reader = readerBehindWrite(db)) {
      assertThatThrownBy(db::close)
          .isInstanceOf(PersistenceException.class)
          .hasMessageContaining("WAL")
          .hasCauseInstanceOf(SQLException.class);
    }

    // Несмотря на сбой переноса WAL, БД закрыта: новые транзакции отклоняются
    assertThatThrownBy(() -> db.inTransaction(c -> null))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("закрыта");
    assertThatCode(db::close).doesNotThrowAnyException();
  }

  @Test
  void closeGivesUpWalCheckpointAfterConfiguredBusyTimeout() throws SQLException {
    var db = withCounter(Duration.ofMillis(100));

    try (Connection reader = readerBehindWrite(db)) {
      long started = System.nanoTime();
      assertThatThrownBy(db::close).isInstanceOf(PersistenceException.class);
      // Ожидание по умолчанию (5 с) заняло бы заметно дольше
      assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
    }
  }

  @Test
  void transactionsFailWithoutCreatingDeletedDatabaseFile() throws IOException {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    Files.delete(testFile());

    assertThatThrownBy(() -> db.inTransaction(c -> null))
        .isInstanceOf(PersistenceException.class)
        .hasCauseInstanceOf(SQLException.class);
    assertThatThrownBy(() -> db.inReadTransaction(c -> null))
        .isInstanceOf(PersistenceException.class)
        .hasCauseInstanceOf(SQLException.class);
    assertThat(testFile()).doesNotExist();
  }

  @Test
  void transactionsFailWhileFileIsMovedAwayAndWorkAgainWhenItIsReturned() throws IOException {
    var db = withCounter(Database.DEFAULT_BUSY_TIMEOUT);
    Path moved = dir.resolve("moved.db");
    Files.move(testFile(), moved);

    assertThatThrownBy(() -> db.inTransaction(c -> null)).isInstanceOf(PersistenceException.class);
    assertThat(testFile()).doesNotExist();

    Files.move(moved, testFile());
    db.inTransaction(
        c -> {
          exec(c, "UPDATE counter SET value = 11");
          return null;
        });
    assertThat(read(db)).isEqualTo(11);
  }
}
