// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.util.concurrent.CompletableFuture;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.stage.Modality;
import javafx.stage.Window;

/** Диалоги JavaFX: модальные {@link Alert} поверх главного окна. Вызываются из потока JavaFX. */
final class FxDialogs implements Dialogs {

  private final Window owner;

  FxDialogs(Window owner) {
    this.owner = owner;
  }

  @Override
  public CompletableFuture<Boolean> confirmMigration(String question) {
    var apply = new ButtonType("Применить", ButtonData.OK_DONE);
    Alert alert =
        alert(AlertType.CONFIRMATION, "Миграция базы", question, apply, ButtonType.CANCEL);
    var result = new CompletableFuture<Boolean>();
    alert.setOnHidden(event -> result.complete(apply.equals(alert.getResult())));
    result.whenComplete(
        (confirmed, error) -> {
          if (result.isCancelled()) {
            alert.close();
          }
        });
    alert.show();
    return result;
  }

  @Override
  public CompletableFuture<Void> warnBeforeExit(String message) {
    Alert alert = alert(AlertType.WARNING, "Внимание", message, ButtonType.OK);
    var result = new CompletableFuture<Void>();
    alert.setOnHidden(event -> result.complete(null));
    result.whenComplete(
        (ignored, error) -> {
          if (result.isCancelled()) {
            alert.close();
          }
        });
    alert.show();
    return result;
  }

  @Override
  public void warn(String message) {
    alert(AlertType.WARNING, "Внимание", message, ButtonType.OK).show();
  }

  @Override
  public void error(String message) {
    alert(AlertType.ERROR, "Ошибка", message, ButtonType.OK).show();
  }

  private Alert alert(AlertType type, String title, String message, ButtonType... buttons) {
    var alert = new Alert(type, message, buttons);
    alert.setTitle(title);
    alert.setHeaderText(null);
    alert.initOwner(owner);
    alert.initModality(Modality.WINDOW_MODAL);
    return alert;
  }
}
