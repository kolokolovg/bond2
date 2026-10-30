// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.nio.file.Path;
import ru.onlychill.bonds2.persistence.Database;

/** Настоящие операции с файлами БД: делегируют пакету {@code persistence}. */
final class FileStorage implements DatabaseService.Storage {

  @Override
  public Database.State inspect(Path file) {
    return Database.inspect(file);
  }

  @Override
  public Database open(Path file) {
    return Database.open(file);
  }

  @Override
  public Database create(Path file) {
    return Database.create(file);
  }

  @Override
  public Database initialize(Path file) {
    return Database.initialize(file);
  }

  @Override
  public void migrateWithBackup(Path file) {
    Database.migrateWithBackup(file);
  }

  @Override
  public void close(Database database) {
    database.close();
  }
}
