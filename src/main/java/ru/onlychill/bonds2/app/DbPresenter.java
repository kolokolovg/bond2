// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.dbservice.BackupResult;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;
import ru.onlychill.bonds2.dbservice.OpenResult;

/**
 * Презентер операций БД: busy-гейтинг {@code open/backup/retryOpen}, маршрутизация итогов открытия
 * и копирования (тихие отклонения — в журнал, ошибки и итоги — в порт отображения операций),
 * применение состояния и фасад к машине выхода. Состояние отдаётся слушателям колбэками JDK-типов,
 * поэтому класс не зависит от интерфейсного тулкита и тестируется без него.
 *
 * <p>Запросы выполняются в потоке интерфейса, результаты сервиса доставляются через {@code
 * uiExecutor}. Порт отображения операций и взгляд на сценарий миграции назначаются при сборке окна
 * — до первого открытия БД. Решения о завершении приложения остаются в {@link ExitCoordinator}:
 * презентер строит его при сборке и лишь делегирует.
 */
public final class DbPresenter {

  private static final System.Logger LOG = System.getLogger(DbPresenter.class.getName());

  private final DatabaseService service;
  private final Executor uiExecutor;

  /** Машина выхода: фазы, сроки и порядок завершения. */
  private final ExitCoordinator exits;

  private final List<Consumer<DbState>> listeners = new CopyOnWriteArrayList<>();

  /**
   * Открытие, миграция или копирование идёт: повторный запрос до доставки результата игнорируется.
   * {@code volatile}: запись и сброс происходят в потоке интерфейса, но исполнитель в тестах
   * отличается.
   */
  private volatile boolean busy;

  /** Состояние, видимое интерфейсу: доставленная в потоке интерфейса копия. */
  private volatile DbState state = DbState.CLOSED;

  /** Порт отображения итогов операций; назначается при сборке окна, до первого открытия БД. */
  private volatile @Nullable OperationsView operations;

  /** Взгляд на сценарий миграции; назначается при сборке окна, до первого открытия БД. */
  private volatile @Nullable MigrationView migration;

  /**
   * @param service сервис жизненного цикла БД; презентер подписывается на его состояние
   * @param exitView порты интерфейса машины выхода: предупреждения и ошибки
   * @param exit завершение приложения
   * @param uiExecutor выполняет задачу в потоке интерфейса
   */
  public DbPresenter(
      DatabaseService service, ExitView exitView, Runnable exit, Executor uiExecutor) {
    this.service = service;
    this.uiExecutor = uiExecutor;
    this.exits = new ExitCoordinator(service, exitView, exit, uiExecutor, this::state);
    service.states().subscribe(new StateSubscriber(uiExecutor, this::apply));
  }

  /** Текущее состояние БД; интерфейс подписывается на изменения через {@link #listen}. */
  public DbState state() {
    return state;
  }

  /** Регистрирует слушателя состояний; вызывается в потоке интерфейса. */
  public void listen(Consumer<DbState> listener) {
    listeners.add(listener);
  }

  /** Назначает порт отображения итогов операций; до первого открытия БД. */
  public void attachOperations(OperationsView view) {
    this.operations = view;
  }

  /** Назначает взгляд на сценарий миграции; до первого открытия БД. */
  public void attachMigration(MigrationView migrationView) {
    this.migration = migrationView;
    exits.attachMigration(migrationView);
  }

  /**
   * Безусловное открытие единственной БД после показа окна; отсутствующий файл создаётся.
   *
   * @return будущность доставки итога открытия в поток интерфейса; {@code null}, если запрос
   *     проигнорирован гейтингом
   */
  public @Nullable CompletableFuture<Void> openOnStartup() {
    return open();
  }

  /** «Файл → Повторить открытие»: доступно, когда БД не открыта и не идёт операция. */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void retryOpen() {
    open();
  }

  private @Nullable CompletableFuture<Void> open() {
    if (busy || !state.allowsOpen()) {
      return null;
    }
    return deliverOperation(service.open(), this::onOpenResult);
  }

  /**
   * «Файл → Сделать резервную копию…»: копирует открытую БД по выбранному пользователем пути.
   * Решение о замене существующего файла подтверждает диалог сохранения.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void backupTo(Path target) {
    if (busy || !state.allowsBackup()) {
      return;
    }
    deliverOperation(service.backup(target), this::onBackupResult);
  }

  /** «Файл → Выход» и закрытие окна кнопкой: решает машина выхода координатора. */
  public void requestExit() {
    exits.requestExit();
  }

  /** Выход начат или итог уже известен; живой взгляд на фазу координатора. */
  public boolean exiting() {
    return exits.exiting();
  }

  /** Итог остановки известен: поздние результаты операций игнорируются. */
  public boolean concluded() {
    return exits.concluded();
  }

  /** Действие хука завершения JVM: останавливает сервис не дольше срока координатора. */
  public void stopForExternalShutdown() {
    exits.stopForExternalShutdown();
  }

  /**
   * Завершение в обход меню; выполняется на выделенном потоке завершения: ожидающая решения
   * миграция отклоняется, идущая доводится до конца не дольше срока координатора. Ошибки
   * журналируются координатором, завершение продолжается.
   */
  public void shutdownForExternalTermination() {
    exits.shutdownForExternalTermination();
  }

  /** Цепочка штатного завершения; однократность выполнения гарантирует машина выхода. */
  public boolean runExitChain(Runnable releaseLock, Runnable disarmHook) {
    return exits.runExitChain(releaseLock, disarmHook);
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
      case OpenResult.NeedsMigration needs -> migration().askAboutMigration();
      case OpenResult.Failed failure -> operations().showError(failure.message());
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
          operations().showInfo("Резервная копия создана: " + copied.target());
      case BackupResult.Failed failure -> operations().showError(failure.message());
      case BackupResult.Rejected rejected ->
          LOG.log(System.Logger.Level.DEBUG, "Копирование отклонено: " + rejected.message());
    }
  }

  /**
   * Ставит операцию сервиса и помечает её идущей ({@code busy}): повторный запрос до доставки
   * результата игнорируется даже если состояние в интерфейсе ещё не обновилось. Признак
   * сбрасывается в потоке интерфейса, когда цепочка доставки доходит до конца: и при результате, и
   * при сбое операции или обработчика. Возвращается будущность конца доставки — конвейер запуска
   * фиксирует готовность после доставки итога открытия.
   */
  private <T> CompletableFuture<Void> deliverOperation(
      CompletableFuture<T> future, Consumer<T> handler) {
    busy = true;
    return future
        .thenAcceptAsync(handler, uiExecutor)
        .handleAsync(
            (ignored, error) -> {
              busy = false;
              return (Void) null;
            },
            uiExecutor);
  }

  /** Применяет состояние в потоке интерфейса: обновляет копию и извещает слушателей. */
  private void apply(DbState next) {
    state = next;
    for (Consumer<DbState> listener : listeners) {
      listener.accept(next);
    }
  }

  private OperationsView operations() {
    return Objects.requireNonNull(operations, "порт отображения операций не назначен");
  }

  private MigrationView migration() {
    return Objects.requireNonNull(migration, "взгляд на сценарий миграции не назначен");
  }
}
