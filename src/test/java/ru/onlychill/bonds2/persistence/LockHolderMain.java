package ru.onlychill.bonds2.persistence;

import java.nio.file.Path;

/** Вспомогательный процесс для {@link InstanceLockTest}: держит блокировку, пока не будет убит. */
public final class LockHolderMain {

  private LockHolderMain() {}

  public static void main(String[] args) throws InterruptedException {
    var lock = InstanceLock.tryAcquire(Path.of(args[0]));
    if (lock.isEmpty()) {
      System.out.println("BUSY");
      return;
    }
    System.out.println("LOCKED");
    System.out.flush();
    Thread.sleep(Long.MAX_VALUE);
  }
}
