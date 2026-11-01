// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import java.util.concurrent.CompletableFuture;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.dbservice.OpenResult;

/** Взгляд машины выхода на сценарий миграции: отмена диалога и доступ к идущей миграции. */
public interface MigrationView {

  /** Закрывает ожидающий диалог миграции, не дожидаясь ответа. */
  void cancelDialog();

  /** Идущая миграция; {@code null}, если её нет. */
  @Nullable CompletableFuture<OpenResult> currentMigration();
}
