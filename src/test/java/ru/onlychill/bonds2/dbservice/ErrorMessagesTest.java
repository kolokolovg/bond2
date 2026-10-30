// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

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
  void deepCauseMessageIsIncludedWhenImmediateCauseHasNone() {
    // Сценарий «Причина без сообщения у непосредственной причины»: обёртка без сообщения поверх
    // настоящего сбоя ввода-вывода. Конструктор с причиной подставил бы cause.toString(),
    // поэтому сообщение очищается через initCause
    var deep = new SQLException("disk I/O error");
    var wrapper = new SQLException();
    wrapper.initCause(deep);
    var error = new MigrationException("Миграция не выполнена", wrapper);

    assertThat(ErrorMessages.of(error)).isEqualTo("Миграция не выполнена Причина: disk I/O error");
  }

  @Test
  void causeWithoutMessageIsIgnored() {
    // Непосредственная причина без сообщения пропускается, но текст глубокой причины из цепочки
    // включается
    var deep = new SQLException("disk I/O error");
    var immediate = new IllegalArgumentException();
    immediate.initCause(deep);
    var error = new IllegalStateException("сбой", immediate);

    assertThat(ErrorMessages.of(error)).isEqualTo("сбой Причина: disk I/O error");
  }

  @Test
  void withoutAnyCauseMessageNothingIsAppended() {
    // Ни одна из причин в цепочке не имеет сообщения: «Причина» не добавляется
    var error = new IllegalStateException("сбой", new IllegalArgumentException());

    assertThat(ErrorMessages.of(error)).isEqualTo("сбой");
  }

  @Test
  void traversalStopsAtDepthFive() {
    // Сообщение на пятом уровне цепочки ещё достигается
    var atLimit = new IllegalStateException("сбой", chainOfCauses(5, "на пятом уровне"));
    assertThat(ErrorMessages.of(atLimit)).isEqualTo("сбой Причина: на пятом уровне");

    // Сообщение на шестом уровне — уже нет: обход останавливается на глубине 5
    var beyondLimit = new IllegalStateException("сбой", chainOfCauses(6, "на шестом уровне"));
    assertThat(ErrorMessages.of(beyondLimit)).isEqualTo("сбой");
  }

  @Test
  void cyclicCauseChainDoesNotLoop() {
    var inner = new IllegalArgumentException();
    var error = new IllegalStateException("сбой", inner);
    // Конструктор запрещает cause == this, поэтому замыкаем причину на саму цепочку:
    // inner -> error -> inner -> ...
    inner.initCause(error);

    assertThat(ErrorMessages.of(error)).isEqualTo("сбой");
  }

  @Test
  void nextExceptionChainIsTraversed() {
    // Для SQLException также обходится getNextException(): непосредственная причина без сообщения
    var head = new SQLException();
    head.setNextException(new SQLException("file is not a database"));
    var error = new MigrationException("Миграция не выполнена", head);

    assertThat(ErrorMessages.of(error))
        .isEqualTo("Миграция не выполнена Причина: file is not a database");
  }

  @Test
  void causeFullyContainedInTextAddsNothing() {
    // Все сообщения цепочки уже вошли в текст: «Причина» не добавляется
    var error =
        new MigrationException(
            "Миграция не выполнена: disk I/O error",
            new SQLException("disk I/O error", new IllegalStateException("disk I/O error")));

    assertThat(ErrorMessages.of(error)).isEqualTo("Миграция не выполнена: disk I/O error");
  }

  @Test
  void nullMessagesInMiddleOfChainAreSkipped() {
    var deep = new IllegalStateException("обрыв соединения");
    var wrapper = new SQLException();
    wrapper.initCause(deep);
    var error = new MigrationException("Миграция не выполнена", wrapper);

    assertThat(ErrorMessages.of(error))
        .isEqualTo("Миграция не выполнена Причина: обрыв соединения");
  }

  /** Цепочка из {@code depth} причин без сообщений; на последнем уровне — {@code message}. */
  private static Throwable chainOfCauses(int depth, String message) {
    Throwable current = new SQLException(message);
    for (int i = 1; i < depth; i++) {
      var wrapper = new IllegalArgumentException();
      wrapper.initCause(current);
      current = wrapper;
    }
    return current;
  }
}
