// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import java.util.concurrent.CompletableFuture;

/** Порты интерфейса для машины выхода: координатор не зависит от JavaFX. */
public interface ExitView {

  /** Предупреждение об истечении срока остановки; завершение — после его подтверждения. */
  CompletableFuture<Void> warnTimedOutBeforeExit(String message);

  /** Предупреждение «идёт миграция»: выход откладывается. */
  void warnMigrationInProgress(String message);

  /** Сообщение об ошибке цепочки выхода: приложение остаётся работать. */
  void showError(String message);
}
