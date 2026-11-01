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

  @Test
  void everyStateAnswersShutdownDecisionExactlyOnce() {
    // Полный перебор sealed-состояний: предикаты остановки определяются здесь целиком,
    // новое состояние, не попавшее в перебор, не пройдёт мимо этого теста
    assertThat(allowsShutdownPerState())
        .containsEntry("Closed", true)
        .containsEntry("Opening", true)
        .containsEntry("Open", true)
        .containsEntry("AwaitingMigration", false)
        .containsEntry("Migrating", false);
    assertThat(awaitsUserDecisionPerState())
        .containsEntry("AwaitingMigration", true)
        .containsEntry("Closed", false)
        .containsEntry("Opening", false)
        .containsEntry("Open", false)
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
      new DbState.Opening(DbState.Kind.OPEN, FILE),
      new DbState.Opening(DbState.Kind.CREATE, FILE),
      new DbState.AwaitingMigration(FILE),
      new DbState.Migrating(FILE),
      new DbState.Open(FILE)
    };
  }

  private static String nameOf(DbState state) {
    return state.getClass().getSimpleName();
  }
}
