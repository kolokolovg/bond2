// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.nio.file.Path;
import java.util.concurrent.Executor;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import ru.onlychill.bonds2.app.DbPresenter;
import ru.onlychill.bonds2.app.OperationsView;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.DbState;

/**
 * FX-адаптер презентера ядра: свойства состояния для интерфейса ({@code javafx.beans} запрещён в
 * {@code app/}), сборка сценария диалога миграции и адаптация порта отображения итогов операций к
 * {@link Dialogs}. Решения — в презентере и машине выхода, контроллер лишь делегирует и адаптирует;
 * сам с файлом БД не работает.
 *
 * <p>Методы вызываются из потока JavaFX; состояния доставляются презентером через его исполнитель
 * потока интерфейса. Состояние видно через {@link #stateProperty()} и {@link #statusProperty()}.
 * Класс не зависит от контролов, поэтому тестируется без показа окон.
 */
final class DatabaseController {

  private final DbPresenter presenter;

  private final ReadOnlyObjectWrapper<DbState> state = new ReadOnlyObjectWrapper<>(DbState.CLOSED);
  private final ReadOnlyStringWrapper status = new ReadOnlyStringWrapper(StatusText.CLOSED);

  /**
   * @param presenter презентер операций БД: делегирование запросов и состояния
   * @param service сервис жизненного цикла БД: сценарий миграции запускает его команды
   * @param dialogs диалоги пользователя
   * @param uiExecutor выполняет задачу в потоке JavaFX
   */
  DatabaseController(
      DbPresenter presenter, DatabaseService service, Dialogs dialogs, Executor uiExecutor) {
    this.presenter = presenter;
    // Сценарий диалога миграции; предикаты — живой взгляд на фазу выхода координатора:
    // значения читаются в момент решения
    var flow =
        new MigrationFlow(service, dialogs, uiExecutor, presenter::exiting, presenter::concluded);
    presenter.attachMigration(flow);
    presenter.attachOperations(
        new OperationsView() {
          @Override
          public void showInfo(String message) {
            dialogs.info(message);
          }

          @Override
          public void showError(String message) {
            dialogs.error(message);
          }
        });
    presenter.listen(this::apply);
  }

  /** Текущее состояние БД; меню и кнопки подписываются на него. */
  ReadOnlyObjectProperty<DbState> stateProperty() {
    return state.getReadOnlyProperty();
  }

  /** Строка состояния: «БД открыта» или «БД не открыта» с причиной. */
  ReadOnlyStringProperty statusProperty() {
    return status.getReadOnlyProperty();
  }

  /** «Файл → Повторить открытие»: доступно, когда БД не открыта и не идёт операция. */
  void retryOpen() {
    presenter.retryOpen();
  }

  /**
   * «Файл → Сделать резервную копию…»: копирует открытую БД по выбранному пользователем пути.
   * Решение о замене существующего файла подтверждает диалог сохранения.
   */
  void backupTo(Path target) {
    presenter.backupTo(target);
  }

  /** «Файл → Выход» и закрытие окна кнопкой: решает машина выхода координатора. */
  void requestExit() {
    presenter.requestExit();
  }

  /** Применяет состояние в потоке JavaFX: обновляет свойство состояния и строку состояния. */
  private void apply(DbState next) {
    state.set(next);
    status.set(StatusText.of(next));
  }
}
