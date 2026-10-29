// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DbStateTest {

  @Test
  void closedAllowsOpenCreateAndSettingsButNotClose() {
    assertThat(DbState.CLOSED.allowsOpen()).isTrue();
    assertThat(DbState.CLOSED.allowsCreate()).isTrue();
    assertThat(DbState.CLOSED.allowsClose()).isFalse();
    assertThat(DbState.CLOSED.allowsSettings()).isTrue();
  }

  @Test
  void openAllowsOnlyCloseAndSettings() {
    assertThat(DbState.OPEN.allowsOpen()).isFalse();
    assertThat(DbState.OPEN.allowsCreate()).isFalse();
    assertThat(DbState.OPEN.allowsClose()).isTrue();
    assertThat(DbState.OPEN.allowsSettings()).isTrue();
  }

  @Test
  void openingAndMigratingBlockEveryDatabaseAction() {
    for (DbState busy : new DbState[] {DbState.OPENING, DbState.MIGRATING}) {
      assertThat(busy.allowsOpen()).as(busy.name()).isFalse();
      assertThat(busy.allowsCreate()).as(busy.name()).isFalse();
      assertThat(busy.allowsClose()).as(busy.name()).isFalse();
      assertThat(busy.allowsSettings()).as(busy.name()).isFalse();
    }
  }
}
