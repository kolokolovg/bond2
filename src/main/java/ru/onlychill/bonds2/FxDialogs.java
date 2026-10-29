// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2;

import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

/** Диалоги JavaFX: модальные {@link Alert} поверх главного окна. Вызываются из потока JavaFX. */
final class FxDialogs implements DatabaseController.Dialogs {

  private final Window owner;

  FxDialogs(Window owner) {
    this.owner = owner;
  }

  @Override
  public boolean confirmMigration(String question) {
    var apply = new ButtonType("Применить", ButtonData.OK_DONE);
    return alert(AlertType.CONFIRMATION, "Миграция базы", question, apply, ButtonType.CANCEL)
        .showAndWait()
        .filter(apply::equals)
        .isPresent();
  }

  @Override
  public boolean confirmExitDuringOpen() {
    // «Дождаться» — кнопка отмены: закрытие диалога означает продолжение работы, а не выход
    var wait = new ButtonType("Дождаться", ButtonData.CANCEL_CLOSE);
    var exit = new ButtonType("Выйти", ButtonData.OTHER);
    return alert(
            AlertType.CONFIRMATION,
            "Выход",
            "Идёт открытие или создание базы. Дождаться окончания или выйти? При выходе приложение"
                + " ждёт операцию не дольше 3 секунд и завершается.",
            wait,
            exit)
        .showAndWait()
        .filter(exit::equals)
        .isPresent();
  }

  @Override
  public void warn(String message) {
    alert(AlertType.WARNING, "Внимание", message, ButtonType.OK).showAndWait();
  }

  @Override
  public void error(String message) {
    alert(AlertType.ERROR, "Ошибка", message, ButtonType.OK).showAndWait();
  }

  private Alert alert(AlertType type, String title, String message, ButtonType... buttons) {
    var alert = new Alert(type, message, buttons);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.initOwner(owner);
    return alert;
  }
}
