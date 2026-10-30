// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.onlychill.bonds2.dbservice.TestDatabases.isClosed;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.persistence.Database;

class FileStorageTest {

  @TempDir Path dir;

  @Test
  void closeClosesTheDatabaseOfRealStorage() {
    Database database = Database.create(dir.resolve("bonds.db"));

    new FileStorage().close(database);

    assertThat(isClosed(database)).isTrue();
  }
}
