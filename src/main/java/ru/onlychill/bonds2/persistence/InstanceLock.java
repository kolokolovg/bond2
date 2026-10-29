package ru.onlychill.bonds2.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/**
 * Эксклюзивная блокировка файла: гарантирует один работающий экземпляр приложения. Блокирует ОС,
 * поэтому она снимается при любом завершении процесса, и оставшийся на диске файл не мешает
 * следующему запуску.
 */
public final class InstanceLock implements AutoCloseable {

  private final FileChannel channel;
  private final FileLock lock;

  private InstanceLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  /**
   * Пытается получить блокировку; недостающие каталоги создаются.
   *
   * @return блокировка либо пусто, если файл уже заблокирован (другим процессом или этой JVM)
   * @throws PersistenceException если файл блокировки создать или открыть не удалось
   */
  public static Optional<InstanceLock> tryAcquire(Path file) {
    Path absolute = file.toAbsolutePath();
    FileChannel channel;
    try {
      Path parent = absolute.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      channel = FileChannel.open(absolute, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
    } catch (IOException e) {
      throw new PersistenceException("Не удалось открыть файл блокировки: " + absolute, e);
    }
    try {
      FileLock acquired = channel.tryLock();
      if (acquired == null) {
        closeChannel(channel);
        return Optional.empty();
      }
      return Optional.of(new InstanceLock(channel, acquired));
    } catch (OverlappingFileLockException e) {
      // Файл уже заблокирован в этой JVM
      closeChannel(channel);
      return Optional.empty();
    } catch (IOException | RuntimeException e) {
      closeChannel(channel);
      throw new PersistenceException("Не удалось заблокировать файл: " + absolute, e);
    }
  }

  /** Снимает блокировку; повторный вызов ничего не делает. */
  @Override
  public void close() {
    try {
      if (lock.isValid()) {
        lock.release();
      }
    } catch (IOException e) {
      // Закрытие канала ниже всё равно снимает блокировку
    } finally {
      closeChannel(channel);
    }
  }

  private static void closeChannel(FileChannel channel) {
    try {
      channel.close();
    } catch (IOException e) {
      // Нечего делать: канал уже непригоден
    }
  }
}
