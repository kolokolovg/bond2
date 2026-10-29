// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import ru.onlychill.bonds2.persistence.MigrationException;

class ErrorMessagesTest {

  @Test
  void usesMessageOfException() {
    assertThat(ErrorMessages.of(new IllegalStateException("сбой"))).isEqualTo("сбой");
  }

  @Test
  void fallsBackToClassNameWithoutMessage() {
    assertThat(ErrorMessages.of(new IllegalStateException())).isEqualTo("IllegalStateException");
  }

  @Test
  void appendsCauseThatIsNotInMessage() {
    var error = new MigrationException("Миграция не выполнена", new SQLException("disk I/O error"));

    assertThat(ErrorMessages.of(error)).isEqualTo("Миграция не выполнена Причина: disk I/O error");
  }

  @Test
  void doesNotRepeatCauseAlreadyInMessage() {
    var error =
        new MigrationException(
            "БД восстановлена из резервной копии: disk I/O error",
            new SQLException("disk I/O error"));

    assertThat(ErrorMessages.of(error))
        .isEqualTo("БД восстановлена из резервной копии: disk I/O error");
  }

  @Test
  void causeWithoutMessageIsIgnored() {
    var error = new IllegalStateException("сбой", new IllegalArgumentException());

    assertThat(ErrorMessages.of(error)).isEqualTo("сбой");
  }
}
