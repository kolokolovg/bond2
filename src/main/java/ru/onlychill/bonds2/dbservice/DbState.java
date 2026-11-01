// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Неизменяемый снимок состояния БД. Определяет доступность пунктов меню и возможность выхода.
 *
 * <ul>
 *   <li>{@link Closed} — БД не открыта: её можно открыть («Повторить открытие»);
 *   <li>{@link Opening} — БД открывается или создаётся: другие операции недопустимы;
 *   <li>{@link AwaitingMigration} — схема устарела, сервис ждёт решения о миграции; файл не
 *       изменён;
 *   <li>{@link Migrating} — выполняется миграция со служебной копией;
 *   <li>{@link Open} — БД открыта: её можно копировать;
 *   <li>{@link BackingUp} — создаётся резервная копия по указанию пользователя: БД открыта,
 *       остановка сервиса дожидается окончания копирования.
 * </ul>
 */
public sealed interface DbState {

  /** Начальное состояние: БД не открыта, причины нет. */
  DbState CLOSED = new Closed(null, null);

  /**
   * БД не открыта.
   *
   * @param reason короткая причина для строки состояния; {@code null}, если БД просто закрыта
   * @param message подробности для пользователя; {@code null}, если их нет
   */
  record Closed(@Nullable String reason, @Nullable String message) implements DbState {}

  /** Открывается единственная БД приложения {@code file}; отсутствующий файл создаётся. */
  record Opening(Path file) implements DbState {}

  /** Схема файла {@code file} устарела; команды миграции или отказа ещё нет. */
  record AwaitingMigration(Path file) implements DbState {}

  /** Выполняется миграция файла {@code file}. */
  record Migrating(Path file) implements DbState {}

  /** БД {@code file} открыта. */
  record Open(Path file) implements DbState {}

  /** Создаётся резервная копия открытой БД в файл {@code target}. */
  record BackingUp(Path target) implements DbState {}

  /** «Повторить открытие»: только когда БД закрыта и никакая операция не идёт. */
  default boolean allowsOpen() {
    return this instanceof Closed;
  }

  /** «Сделать резервную копию…»: только при открытой БД и без идущей операции. */
  default boolean allowsBackup() {
    return this instanceof Open;
  }

  /**
   * Остановка сервиса возможна: не идёт миграция и не ожидается решение о ней. Открытие и резервное
   * копирование останавливающий поток дожидается: операции идут по одному в очереди сервиса. Единый
   * дом решения для сервиса и интерфейса: новое состояние, меняющее эту возможность, описывается
   * здесь, а не в точках вызова.
   */
  default boolean allowsShutdown() {
    return !(this instanceof Migrating) && !(this instanceof AwaitingMigration);
  }

  /**
   * Состояние ждёт решения пользователя (о миграции): остановка невозможна, но решение можно
   * принять за пользователя (отклонить миграцию), после чего остановка станет возможной.
   */
  default boolean awaitsUserDecision() {
    return this instanceof AwaitingMigration;
  }
}
