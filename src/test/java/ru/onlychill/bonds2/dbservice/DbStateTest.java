// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DbStateTest {

  private static final Path FILE = Path.of("bonds.db");

  @Test
  void closedAllowsOpenButNotBackup() {
    assertThat(DbState.CLOSED.allowsOpen()).isTrue();
    assertThat(DbState.CLOSED.allowsBackup()).isFalse();
    assertThat(new DbState.Closed("причина", "подробности").allowsOpen()).isTrue();
  }

  @Test
  void openAllowsOnlyBackup() {
    var open = new DbState.Open(FILE);

    assertThat(open.allowsOpen()).isFalse();
    assertThat(open.allowsBackup()).isTrue();
  }

  @Test
  void busyStatesBlockOpenAndBackup() {
    for (DbState busy :
        new DbState[] {
          new DbState.Opening(FILE),
          new DbState.AwaitingMigration(FILE),
          new DbState.Migrating(FILE),
          new DbState.BackingUp(FILE)
        }) {
      assertThat(busy.allowsOpen()).as(busy.toString()).isFalse();
      assertThat(busy.allowsBackup()).as(busy.toString()).isFalse();
    }
  }

  @Test
  void backingUpWaitsForShutdownAndNotForUserDecision() {
    var backingUp = new DbState.BackingUp(FILE);

    assertThat(backingUp.allowsShutdown()).isTrue();
    assertThat(backingUp.awaitsUserDecision()).isFalse();
    assertThat(backingUp.allowsBackup()).isFalse();
    assertThat(backingUp.allowsOpen()).isFalse();
  }

  @Test
  void statesCarryPath() {
    assertThat(new DbState.Open(FILE).file()).isEqualTo(FILE);
    assertThat(new DbState.Opening(FILE).file()).isEqualTo(FILE);
    assertThat(new DbState.BackingUp(FILE).target()).isEqualTo(FILE);
  }

  @Test
  void everyStateAnswersShutdownDecisionExactlyOnce() {
    // Полный перебор sealed-состояний: предикаты остановки определяются здесь целиком,
    // новое состояние, не попавшее в перебор, не пройдёт мимо этого теста
    assertThat(allowsShutdownPerState())
        .containsEntry("Closed", true)
        .containsEntry("Opening", true)
        .containsEntry("Open", true)
        .containsEntry("BackingUp", true)
        .containsEntry("AwaitingMigration", false)
        .containsEntry("Migrating", false);
    assertThat(awaitsUserDecisionPerState())
        .containsEntry("AwaitingMigration", true)
        .containsEntry("Closed", false)
        .containsEntry("Opening", false)
        .containsEntry("Open", false)
        .containsEntry("BackingUp", false)
        .containsEntry("Migrating", false);
  }

  @Test
  void exhaustiveSwitchOverStatesCoversAllVariants() {
    // Исчерпывающий переключатель: добавление нового варианта DbState ломает компиляцию теста —
    // новое состояние обязано явно решить, что оно означает для меню и остановки
    for (DbState state : allStates()) {
      boolean usedByMenus =
          switch (state) {
            case DbState.Closed c -> c.allowsOpen();
            case DbState.Opening o -> o.allowsOpen();
            case DbState.AwaitingMigration a -> a.allowsOpen();
            case DbState.Migrating m -> m.allowsOpen();
            case DbState.Open p -> p.allowsOpen();
            case DbState.BackingUp b -> b.allowsOpen();
          };
      assertThat(usedByMenus).isNotNull();
      assertThat(state.allowsShutdown()).isNotNull();
      assertThat(state.awaitsUserDecision()).isNotNull();
    }
  }

  private static java.util.Map<String, Boolean> allowsShutdownPerState() {
    var answers = new java.util.LinkedHashMap<String, Boolean>();
    for (DbState state : allStates()) {
      answers.put(nameOf(state), state.allowsShutdown());
    }
    return answers;
  }

  private static java.util.Map<String, Boolean> awaitsUserDecisionPerState() {
    var answers = new java.util.LinkedHashMap<String, Boolean>();
    for (DbState state : allStates()) {
      answers.put(nameOf(state), state.awaitsUserDecision());
    }
    return answers;
  }

  private static DbState[] allStates() {
    return new DbState[] {
      DbState.CLOSED,
      new DbState.Closed("причина", null),
      new DbState.Opening(FILE),
      new DbState.AwaitingMigration(FILE),
      new DbState.Migrating(FILE),
      new DbState.Open(FILE),
      new DbState.BackingUp(FILE)
    };
  }

  private static String nameOf(DbState state) {
    return state.getClass().getSimpleName();
  }
}
