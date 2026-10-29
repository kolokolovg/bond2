// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DbStateTest {

  private static final Path FILE = Path.of("bonds.db");

  @Test
  void closedAllowsOpenCreateAndSettingsButNotClose() {
    assertThat(DbState.CLOSED.allowsOpen()).isTrue();
    assertThat(DbState.CLOSED.allowsCreate()).isTrue();
    assertThat(DbState.CLOSED.allowsClose()).isFalse();
    assertThat(DbState.CLOSED.allowsSettings()).isTrue();
    assertThat(new DbState.Closed("причина", "подробности").allowsOpen()).isTrue();
  }

  @Test
  void openAllowsOnlyCloseAndSettings() {
    var open = new DbState.Open(FILE);

    assertThat(open.allowsOpen()).isFalse();
    assertThat(open.allowsCreate()).isFalse();
    assertThat(open.allowsClose()).isTrue();
    assertThat(open.allowsSettings()).isTrue();
  }

  @Test
  void busyStatesBlockEveryDatabaseAction() {
    for (DbState busy :
        new DbState[] {
          new DbState.Opening(DbState.Kind.OPEN, FILE),
          new DbState.Opening(DbState.Kind.CREATE, FILE),
          new DbState.AwaitingMigration(FILE),
          new DbState.Migrating(FILE)
        }) {
      assertThat(busy.allowsOpen()).as(busy.toString()).isFalse();
      assertThat(busy.allowsCreate()).as(busy.toString()).isFalse();
      assertThat(busy.allowsClose()).as(busy.toString()).isFalse();
      assertThat(busy.allowsSettings()).as(busy.toString()).isFalse();
    }
  }

  @Test
  void statesCarryReasonAndPath() {
    var closed = new DbState.Closed("схема устарела", "сообщение");

    assertThat(closed.reason()).isEqualTo("схема устарела");
    assertThat(closed.message()).isEqualTo("сообщение");
    assertThat(new DbState.Open(FILE).file()).isEqualTo(FILE);
    assertThat(new DbState.Opening(DbState.Kind.CREATE, FILE).kind())
        .isEqualTo(DbState.Kind.CREATE);
  }
}
