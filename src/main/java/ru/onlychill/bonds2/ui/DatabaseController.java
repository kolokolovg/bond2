// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import ru.onlychill.bonds2.app.ExitCoordinator;
import ru.onlychill.bonds2.app.ExitView;
import ru.onlychill.bonds2.dbservice.BackupResult;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.OpenResult;
import ru.onlychill.bonds2.dbservice.ShutdownResult;

/**
 * FX-адаптер {@link DatabaseService}: подписывается на состояние, переносит его в поток JavaFX и
 * показывает диалоги. Сам с файлом БД не работает; решения о завершении приложения — в {@link
 * ExitCoordinator}, контроллер лишь делегирует и адаптирует диалоги к его портам.
 *
 * <p>Методы вызываются из потока JavaFX, кроме {@link #shutdown()} и {@link
 * #stopForExternalShutdown()}: они выполняются в цепочках завершения — на выделенном потоке
 * завершения приложения и в хуке завершения JVM. Результаты сервиса и состояние доставляются через
 * {@code uiExecutor} ({@code Platform::runLater} в приложении). Состояние видно через {@link
 * #stateProperty()} и {@link #statusProperty()}. Класс не зависит от контролов, поэтому тестируется
 * без показа окон.
 */
final class DatabaseController {

  private static final System.Logger LOG = System.getLogger(DatabaseController.class.getName());

  private final DatabaseService service;
  private final Dialogs dialogs;
  private final Executor uiExecutor;

  /** Машина выхода: фазы, сроки и порядок завершения. */
  private final ExitCoordinator exits;

  private final ReadOnlyObjectWrapper<DbState> state = new ReadOnlyObjectWrapper<>(DbState.CLOSED);
  private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper(StatusText.CLOSED);

  /** Сценарий диалога миграции: вопрос, запуск или отклонение миграции, итог. */
  private final MigrationFlow flow;

  /**
   * Открытие, миграция или копирование идёт: повторный запрос до доставки результата игнорируется.
   * {@code volatile}: запись и сброс происходят в потоке интерфейса, но исполнитель в тестах
   * отличается.
   */
  private volatile boolean busy;

  /**
   * @param service сервис жизненного цикла БД; контроллер подписывается на его состояние
   * @param dialogs диалоги пользователя
   * @param exit завершение приложения
   * @param uiExecutor выполняет задачу в потоке JavaFX
   */
  DatabaseController(DatabaseService service, Dialogs dialogs, Runnable exit, Executor uiExecutor) {
    this.service = service;
    this.dialogs = dialogs;
    this.uiExecutor = uiExecutor;
    this.exits =
        new ExitCoordinator(
            service,
            new ExitView() {
              @Override
              public CompletableFuture<Void> warnTimedOutBeforeExit(String message) {
                return dialogs.warnBeforeExit(message);
              }

              @Override
              public void warnMigrationInProgress(String message) {
                dialogs.warn(message);
              }

              @Override
              public void showError(String message) {
                dialogs.error(message);
              }
            },
            exit,
            uiExecutor,
            () -> state.get());
    // Предикаты — живой взгляд на фазу выхода координатора: значения читаются в момент решения
    this.flow = new MigrationFlow(service, dialogs, uiExecutor, exits::exiting, exits::concluded);
    exits.attachMigration(flow);
    service.states().subscribe(new StateSubscriber(uiExecutor, this::apply));
  }

  /** Текущее состояние БД; меню и кнопки подписываются на него. */
  ReadOnlyObjectProperty<DbState> stateProperty() {
    return state.getReadOnlyProperty();
  }

  /** Строка состояния: «БД открыта» или «БД не открыта» с причиной. */
  ReadOnlyStringProperty statusProperty() {
    return status.getReadOnlyProperty();
  }

  /** Безусловное открытие единственной БД после показа окна; отсутствующий файл создаётся. */
  void openOnStartup() {
    open();
  }

  /** «Файл → Повторить открытие»: доступно, когда БД не открыта и не идёт операция. */
  void retryOpen() {
    open();
  }

  private void open() {
    if (busy || !state.get().allowsOpen()) {
      return;
    }
    deliverOperation(service.open(), this::onOpenResult);
  }

  /**
   * «Файл → Сделать резервную копию…»: копирует открытую БД по выбранному пользователем пути.
   * Решение о замене существующего файла подтверждает диалог сохранения.
   */
  void backupTo(Path target) {
    if (busy || !state.get().allowsBackup()) {
      return;
    }
    deliverOperation(service.backup(target), this::onBackupResult);
  }

  /** «Файл → Выход» и закрытие окна кнопкой: решает машина выхода координатора. */
  void requestExit() {
    exits.requestExit();
  }

  /**
   * Итог остановки в потоке интерфейса. Точка доставки из тестов: тесты передают собранные
   * результаты напрямую.
   */
  void onExitResult(ShutdownResult result) {
    exits.onExitResult(result);
  }

  /**
   * Завершение приложения в обход меню; выполняется на выделенном потоке завершения. Ошибки
   * журналируются координатором, завершение продолжается.
   */
  void shutdown() {
    exits.shutdownForExternalTermination();
  }

  /** Синхронная остановка сервиса; точка доставки из тестов. */
  ShutdownResult shutdown(Duration timeout) {
    return exits.shutdownWaiting(timeout);
  }

  /** Действие хука завершения JVM: останавливает сервис не дольше срока координатора. */
  void stopForExternalShutdown() {
    exits.stopForExternalShutdown();
  }

  /** Цепочка штатного завершения: остановка БД → блокировка экземпляра → снятие хука JVM. */
  void runExitChain(Runnable releaseLock, Runnable disarmHook) {
    exits.runExitChain(releaseLock, disarmHook);
  }

  /**
   * Итог открытия в потоке интерфейса. Отклонение гонки состояния или начатой остановки — не ошибка
   * файла: оно тихое, только в журнал. Точка доставки из тестов: тесты передают собранные
   * результаты напрямую.
   */
  void onOpenResult(OpenResult result) {
    if (exits.concluded()) {
      return;
    }
    switch (result) {
      case OpenResult.Opened opened -> {}
      case OpenResult.NeedsMigration needs -> flow.askAboutMigration();
      case OpenResult.Failed failure -> dialogs.error(failure.message());
      case OpenResult.Rejected rejected ->
          LOG.log(System.Logger.Level.DEBUG, "Открытие отклонено: " + rejected.message());
    }
  }

  /**
   * Итог копирования в потоке интерфейса: успех — сообщение с путём, сбой — классифицированная
   * причина; отклонение гонки состояния или остановки тихое, в журнал. Точка доставки из тестов.
   */
  void onBackupResult(BackupResult result) {
    if (exits.concluded()) {
      return;
    }
    switch (result) {
      case BackupResult.Copied copied ->
          dialogs.info("Резервная копия создана: " + copied.target());
      case BackupResult.Failed failure -> dialogs.error(failure.message());
      case BackupResult.Rejected rejected ->
          LOG.log(System.Logger.Level.DEBUG, "Копирование отклонено: " + rejected.message());
    }
  }

  /**
   * Ставит операцию сервиса и помечает её идущей ({@code busy}): повторный запрос до доставки
   * результата игнорируется даже если состояние в интерфейсе ещё не обновилось. Признак
   * сбрасывается в потоке JavaFX, когда цепочка доставки доходит до конца: и при результате, и при
   * сбое операции или обработчика.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  private <T> void deliverOperation(CompletableFuture<T> future, Consumer<T> handler) {
    busy = true;
    CompletableFuture<Void> delivered =
        future
            .thenAcceptAsync(handler, uiExecutor)
            .handleAsync(
                (ignored, error) -> {
                  busy = false;
                  return (Void) null;
                },
                uiExecutor);
    delivered.exceptionally(
        error -> {
          LOG.log(System.Logger.Level.ERROR, "Сбой операции с БД", error);
          return null;
        });
  }

  /** Применяет состояние в потоке JavaFX: обновляет свойство состояния и строку состояния. */
  private void apply(DbState next) {
    state.set(next);
    status.set(StatusText.of(next));
  }
}
