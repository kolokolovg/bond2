// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

/** Порт отображения итогов операций с БД; реализация не зависит от машины выхода. */
public interface OperationsView {

  /** Информационное сообщение: операция выполнена успешно. */
  void showInfo(String message);

  /** Сообщение об ошибке: операция не выполнена. */
  void showError(String message);
}
