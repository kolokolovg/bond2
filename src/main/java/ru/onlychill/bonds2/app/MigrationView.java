// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.dbservice.OpenResult;

/** Взгляд ядра на сценарий миграции: вопрос пользователю, отмена диалога, идущая миграция. */
public interface MigrationView {

  /** Спрашивает согласие на миграцию и запускает или отклоняет её. */
  void askAboutMigration();

  /** Закрывает ожидающий диалог миграции, не дожидаясь ответа. */
  void cancelDialog();

  /** Идущая миграция; {@code null}, если её нет. */
  @Nullable CompletableFuture<OpenResult> currentMigration();
}
