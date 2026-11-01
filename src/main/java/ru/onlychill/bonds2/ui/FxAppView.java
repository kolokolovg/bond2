// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.app.AppView;
import ru.onlychill.bonds2.app.DbPresenter;
import ru.onlychill.bonds2.dbservice.DatabaseService;

/**
 * Интерфейсная часть приложения на JavaFX: поднимает тулкит {@link Platform#startup}, строит
 * главное окно на презентере ядра и гасит тулкит при завершении. Неявный выход выключен: закрытие
 * окон не завершает процесс — его завершает цепочка ядра, пока идёт, процесс держит главный поток.
 * Предупреждения и ошибки машины выхода показываются диалогами главного окна. Из {@code
 * javafx.application} используется только {@link Platform}: жизненный цикл {@code Application}
 * больше не нужен.
 */
public final class FxAppView implements AppView {

  /** Диалоги главного окна; назначаются при показе окна, до любого предупреждения выхода. */
  private @Nullable Dialogs dialogs;

  @Override
  public Executor startToolkit() {
    // Неявный выход выключен: тулкит гасит только цепочка завершения ядра
    Platform.setImplicitExit(false);
    var started = new CountDownLatch(1);
    try {
      Platform.startup(started::countDown);
    } catch (IllegalStateException alreadyStarted) {
      // Уже поднят (в тестах — общим тулкитом): этим же исключением стартup отвечает и на
      // другие отказы, но ядро стартует один раз за процесс, различать причины незачем
      started.countDown();
    }
    try {
      started.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Прервано ожидание запуска тулкита", e);
    }
    return Platform::runLater;
  }

  @Override
  public void showMainWindow(DatabaseService service, DbPresenter presenter, Executor uiExecutor) {
    var shown = new CompletableFuture<Void>();
    Platform.runLater(
        () -> {
          try {
            buildMainWindow(service, presenter, uiExecutor);
            shown.complete(null);
          } catch (RuntimeException | Error e) {
            shown.completeExceptionally(e);
          }
        });
    // Сборка окна не требует пользователя: зависшее ожидание означало бы мёртвый поток
    // интерфейса, и главный поток не должен ждать его вечно
    try {
      shown.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof RuntimeException runtime) {
        throw runtime;
      }
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("Сбой сборки главного окна", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Прервано ожидание сборки главного окна", e);
    } catch (TimeoutException e) {
      throw new IllegalStateException("Поток интерфейса не собрал окно за 30 секунд", e);
    }
  }

  /** Строит и показывает главное окно; выполняется в потоке JavaFX. */
  private void buildMainWindow(
      DatabaseService service, DbPresenter presenter, Executor uiExecutor) {
    var stage = new Stage();
    var windowDialogs = new FxDialogs(stage);
    this.dialogs = windowDialogs;
    var db = new DatabaseController(presenter, service, windowDialogs, uiExecutor);
    MainWindow.show(stage, db);
  }

  @Override
  public void showStartupError(String message) {
    var dismissed = new CompletableFuture<Void>();
    Platform.runLater(
        () -> {
          var alert = new Alert(Alert.AlertType.ERROR, message);
          alert.setTitle("Bonds");
          alert.setHeaderText(null);
          alert.showAndWait();
          dismissed.complete(null);
        });
    dismissed.join();
  }

  @Override
  public void shutdown() {
    Platform.exit();
  }

  @Override
  public CompletableFuture<Void> warnTimedOutBeforeExit(String message) {
    return dialogs().warnBeforeExit(message);
  }

  @Override
  public void warnMigrationInProgress(String message) {
    dialogs().warn(message);
  }

  @Override
  public void showError(String message) {
    dialogs().error(message);
  }

  private Dialogs dialogs() {
    return Objects.requireNonNull(dialogs, "диалоги главного окна не назначены");
  }
}
