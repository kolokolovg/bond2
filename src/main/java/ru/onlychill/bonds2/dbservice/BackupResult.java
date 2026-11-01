// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.dbservice;

import java.nio.file.Path;

/** Итог резервного копирования БД. */
public sealed interface BackupResult {

  /** Копия создана, прошла проверку целостности и стоит по пути {@code target}. */
  record Copied(Path target) implements BackupResult {}

  /**
   * Копирование не выполнено; БД остаётся открытой.
   *
   * @param message текст для диалога с классифицированной причиной
   */
  record Failed(String message) implements BackupResult {}

  /**
   * Операция недопустима в текущем состоянии или сервис остановлен: состояние и файл не менялись.
   *
   * @param cause типизированная причина отклонения
   * @param message текст для пользователя; может меняться независимо от причины
   */
  record Rejected(RejectionCause cause, String message) implements BackupResult {}
}
