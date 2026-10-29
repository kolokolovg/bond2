// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceLockTest {

  @TempDir Path dir;

  @Test
  void secondAcquireInSameJvmIsRejectedUntilFirstIsClosed() {
    Path file = dir.resolve("a/b/bonds2.lock");

    var first = InstanceLock.tryAcquire(file);
    assertThat(first).isPresent();
    assertThat(InstanceLock.tryAcquire(file)).isEmpty();

    first.orElseThrow().close();
    var again = InstanceLock.tryAcquire(file);
    assertThat(again).isPresent();
    again.orElseThrow().close();
  }

  @Test
  void closeIsIdempotent() {
    var lock = InstanceLock.tryAcquire(dir.resolve("l")).orElseThrow();

    lock.close();
    lock.close();

    assertThat(InstanceLock.tryAcquire(dir.resolve("l")))
        .isPresent()
        .get()
        .satisfies(l -> l.close());
  }

  @Test
  void leftoverLockFileDoesNotBlock() throws IOException {
    Path file = dir.resolve("bonds2.lock");
    Files.writeString(file, "остался от прошлого запуска");

    var lock = InstanceLock.tryAcquire(file);

    assertThat(lock).isPresent();
    lock.orElseThrow().close();
  }

  @Test
  void failsWhenLockFileCannotBeCreated() throws IOException {
    Files.writeString(dir.resolve("blocker"), "файл на месте каталога");

    assertThatThrownBy(() -> InstanceLock.tryAcquire(dir.resolve("blocker/bonds2.lock")))
        .isInstanceOf(PersistenceException.class)
        .hasMessageContaining("Не удалось");
  }

  @Test
  void otherProcessIsRejectedWhileHeldAndAcceptedAfterKill() throws Exception {
    Path file = dir.resolve("bonds2.lock");
    Process holder = start(file);
    try {
      assertThat(readLine(holder)).isEqualTo("LOCKED");

      Process rejected = start(file);
      assertThat(readLine(rejected)).isEqualTo("BUSY");
      rejected.waitFor();

      holder.destroyForcibly().waitFor();
      Process next = start(file);
      try {
        assertThat(readLine(next)).isEqualTo("LOCKED");
      } finally {
        next.destroyForcibly().waitFor();
      }
    } finally {
      holder.destroyForcibly().waitFor();
    }
  }

  private static Process start(Path file) throws IOException {
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    return new ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            LockHolderMain.class.getName(),
            file.toString())
        .redirectErrorStream(true)
        .start();
  }

  private static String readLine(Process process) throws IOException {
    try (var reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line = reader.readLine();
      return line == null ? "" : line;
    }
  }
}
