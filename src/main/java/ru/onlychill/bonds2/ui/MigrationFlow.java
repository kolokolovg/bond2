// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.OpenResult;

/**
 * Сценарий диалога миграции: вопрос пользователю, запуск или отклонение миграции, показ итога.
 * Предикаты {@code exitStarted} и {@code stopping} — живой взгляд контроллера на флаги выхода и
 * остановки: они читаются в момент решения, значения не копируются.
 *
 * <p>Методы вызываются из потока JavaFX; результаты сервиса доставляются через {@code uiExecutor}.
 */
final class MigrationFlow {

  /** Текст вопроса о миграции; резервная копия названа явно. */
  private static final String MIGRATION_QUESTION =
      "Схема БД устарела. Применить миграции? Перед миграцией будет создана резервная копия файла"
          + " БД.";

  private static final System.Logger LOG = System.getLogger(MigrationFlow.class.getName());

  private final DatabaseService service;
  private final Dialogs dialogs;
  private final Executor uiExecutor;
  private final BooleanSupplier exitStarted;
  private final BooleanSupplier stopping;

  /** Открытый диалог миграции; {@code null}, если диалога нет. */
  private @Nullable CompletableFuture<Boolean> migrationDialog;

  /** Запущенная миграция; {@code null}, если её не было. */
  private @Nullable CompletableFuture<OpenResult> migration;

  /**
   * @param service сервис жизненного цикла БД: запуск и отклонение миграции
   * @param dialogs диалоги пользователя
   * @param uiExecutor выполняет задачу в потоке JavaFX: доставка ответа и итога миграции
   * @param exitStarted выход уже начат; читается в момент решения
   * @param stopping приложение остановлено; читается в момент решения
   */
  MigrationFlow(
      DatabaseService service,
      Dialogs dialogs,
      Executor uiExecutor,
      BooleanSupplier exitStarted,
      BooleanSupplier stopping) {
    this.service = service;
    this.dialogs = dialogs;
    this.uiExecutor = uiExecutor;
    this.exitStarted = exitStarted;
    this.stopping = stopping;
  }

  /**
   * Спрашивает согласие на миграцию; без согласия файл не меняется и БД не открывается. Если выход
   * уже начат или приложение остановлено, диалог не показывается и миграция не запускается.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  void askAboutMigration() {
    if (exitStarted.getAsBoolean() || stopping.getAsBoolean()) {
      return;
    }
    CompletableFuture<Boolean> dialog = dialogs.confirmMigration(MIGRATION_QUESTION);
    migrationDialog = dialog;
    dialog
        .thenComposeAsync(this::onMigrationAnswer, uiExecutor)
        .exceptionally(
            error -> {
              if (!(error instanceof CancellationException)
                  && !(error.getCause() instanceof CancellationException)) {
                LOG.log(System.Logger.Level.ERROR, "Сбой операции с БД", error);
              }
              return null;
            });
  }

  private CompletableFuture<Void> onMigrationAnswer(boolean confirmed) {
    migrationDialog = null;
    if (confirmed) {
      CompletableFuture<OpenResult> started = service.migrate();
      migration = started;
      return started.thenAcceptAsync(this::onMigrationResult, uiExecutor);
    }
    return service.declineMigration().thenAccept(result -> {});
  }

  /** Итог миграции в потоке интерфейса: тихо при остановке, диалог об ошибке при сбое. */
  private void onMigrationResult(OpenResult result) {
    if (stopping.getAsBoolean()) {
      return;
    }
    if (result instanceof OpenResult.Failed failure) {
      dialogs.error(failure.message());
    }
  }

  /** Закрывает открытый диалог миграции без решения: отмена не считается ошибкой. */
  void cancelDialog() {
    CompletableFuture<Boolean> dialog = migrationDialog;
    migrationDialog = null;
    if (dialog != null) {
      dialog.cancel(true);
    }
  }

  /** Запущенная миграция; {@code null}, если её не было. */
  @Nullable CompletableFuture<OpenResult> currentMigration() {
    return migration;
  }
}
