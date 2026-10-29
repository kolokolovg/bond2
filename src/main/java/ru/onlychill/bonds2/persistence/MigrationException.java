package ru.onlychill.bonds2.persistence;

/**
 * Ошибка миграции БД с резервной копией. Исходная причина передаётся через {@link #getCause()}.
 * Если миграция уже начала менять файл, он восстановлен из резервной копии; если копию создать или
 * проверить не удалось, миграция не выполнялась и файл не изменён.
 */
public final class MigrationException extends PersistenceException {

  private static final long serialVersionUID = 1L;

  public MigrationException(String message, Throwable cause) {
    super(message, cause);
  }
}
