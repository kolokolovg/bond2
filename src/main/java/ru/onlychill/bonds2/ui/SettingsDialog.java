// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.dbservice.ErrorMessages;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsValidator;

/**
 * Модальное окно настроек БД: путь к файлу с кнопкой «Обзор…», флажок автооткрытия, «Сохранить» и
 * «Отмена».
 *
 * <p>Проверку пути делает {@link SettingsValidator}, сохранение — переданный обработчик на фоновом
 * потоке. Окно закрывается только после завершения записи, и всё время записи оно заблокировано:
 * кнопки и поля отключены, закрытие через крестик и «Отмена» игнорируются, повторное сохранение
 * невозможно. Если запись не удалась, окно остаётся открытым, элементы снова активны и показывается
 * причина ошибки.
 */
final class SettingsDialog {

  /** Ширина текстовых блоков окна, px: длинные подсказки и ошибки переносятся. */
  private static final double WRAP_WIDTH = 520;

  private final Stage stage;
  private final TextField pathField = new TextField();
  private final CheckBox autoOpenBox = new CheckBox("Открывать базу при запуске");
  private final Label errorLabel = new Label();
  private final Button browseButton = new Button("Обзор…");
  private final Button saveButton = new Button("Сохранить");
  private final Button cancelButton = new Button("Отмена");
  private final Function<Settings, CompletableFuture<Void>> saver;

  /** Идёт фоновая запись: до её завершения окно заблокировано. */
  private boolean saving;

  /**
   * @param owner главное окно, над которым открывается диалог
   * @param current текущие настройки: из них заполняются поля
   * @param defaultFile путь по умолчанию: подсказка для пустого поля
   * @param saver записывает настройки в фоне и возвращает future, завершающийся после записи;
   *     ошибка записи завершает future исключением
   */
  SettingsDialog(
      @Nullable Window owner,
      Settings current,
      Path defaultFile,
      Function<Settings, CompletableFuture<Void>> saver) {
    this.saver = saver;
    this.stage = new Stage();

    Path path = current.databasePath();
    pathField.setText(path == null ? "" : path.toString());
    HBox.setHgrow(pathField, Priority.ALWAYS);
    browseButton.setOnAction(event -> browse());
    var pathRow = new HBox(8, pathField, browseButton);

    autoOpenBox.setSelected(current.autoOpen());

    var hint =
        new Label(
            "Пустое поле — путь по умолчанию: "
                + defaultFile
                + ". Новый путь применяется при следующем открытии: закройте базу («Файл → Закрыть"
                + " базу») и откройте её снова.");
    // Перенос по ширине окна: без ограничения длинная подсказка растянула бы окно
    hint.setWrapText(true);
    hint.setMaxWidth(WRAP_WIDTH);

    errorLabel.setWrapText(true);
    errorLabel.setMaxWidth(WRAP_WIDTH);
    errorLabel.setStyle("-fx-text-fill: firebrick;");

    saveButton.setDefaultButton(true);
    saveButton.setOnAction(event -> save());
    cancelButton.setCancelButton(true);
    cancelButton.setOnAction(event -> cancel());
    var buttons = new HBox(8, saveButton, cancelButton);
    buttons.setAlignment(Pos.CENTER_RIGHT);

    var root =
        new VBox(
            10, new Label("Файл базы данных:"), pathRow, autoOpenBox, hint, errorLabel, buttons);
    root.setPadding(new Insets(16));

    stage.initOwner(owner);
    stage.initModality(Modality.APPLICATION_MODAL);
    stage.setTitle("Настройки");
    // Размер окна — по содержимому, ширина ограничена переносом текста
    stage.setScene(new Scene(root));
  }

  /** Показывает окно и ждёт его закрытия. */
  void showAndWait() {
    stage.showAndWait();
  }

  /** Показывает окно без ожидания; используется тестами. */
  void show() {
    stage.show();
  }

  /** Открыто ли окно; используется тестами. */
  boolean isShowing() {
    return stage.isShowing();
  }

  /**
   * «Сохранить»: проверяет путь и передаёт настройки обработчику; окно закрывается только после
   * завершения записи. Ошибка проверки или записи показывается в окне, настройки при этом не
   * меняются. Пока запись идёт, окно заблокировано: повторный вызов ничего не делает.
   */
  void save() {
    // Повторный вызов во время записи игнорируется: кнопка отключена, а это страховка
    if (saving) {
      return;
    }
    String text = pathField.getText();
    Optional<String> problem = SettingsValidator.validateDatabasePath(text);
    if (problem.isPresent()) {
      errorLabel.setText(problem.get());
      return;
    }
    errorLabel.setText("");
    CompletableFuture<Void> write;
    try {
      write = saver.apply(new Settings(Settings.parseDatabasePath(text), autoOpenBox.isSelected()));
    } catch (UncheckedIOException e) {
      errorLabel.setText(ErrorMessages.of(e));
      return;
    }
    // Запись идёт в фоне: блокируем окно, чтобы её нельзя было прервать или запустить заново
    setBusy(true);
    stage.setOnCloseRequest(WindowEvent::consume);
    write.whenComplete(
        (value, error) ->
            Platform.runLater(
                () -> {
                  // Сначала снимаем блокировку и перехват закрытия: окно снова активно
                  stage.setOnCloseRequest(null);
                  setBusy(false);
                  if (error != null) {
                    errorLabel.setText(ErrorMessages.of(error));
                  } else {
                    stage.close();
                  }
                }));
  }

  /**
   * «Отмена» и закрытие окна: настройки не меняются. Пока идёт запись, отменить нельзя: окно
   * блокируется до завершения записи, поэтому вызов ничего не делает.
   */
  void cancel() {
    if (saving) {
      return;
    }
    stage.close();
  }

  /** Блокирует или разблокирует управляющие элементы окна на время записи. */
  private void setBusy(boolean busy) {
    saving = busy;
    saveButton.setDisable(busy);
    cancelButton.setDisable(busy);
    browseButton.setDisable(busy);
    pathField.setDisable(busy);
    autoOpenBox.setDisable(busy);
  }

  /** Текст ошибки; пустой, если ошибки нет. */
  String errorMessage() {
    return errorLabel.getText();
  }

  TextField pathField() {
    return pathField;
  }

  CheckBox autoOpenBox() {
    return autoOpenBox;
  }

  /** Окно диалога; используется тестами для имитации запроса закрытия. */
  Stage stage() {
    return stage;
  }

  /** Кнопка «Сохранить»; используется тестами. */
  Button saveButton() {
    return saveButton;
  }

  /** Кнопка «Отмена»; используется тестами. */
  Button cancelButton() {
    return cancelButton;
  }

  /** Кнопка «Обзор…»; используется тестами. */
  Button browseButton() {
    return browseButton;
  }

  private void browse() {
    var chooser = new FileChooser();
    chooser.setTitle("Выберите файл базы данных");
    chooser
        .getExtensionFilters()
        .add(new FileChooser.ExtensionFilter("База данных SQLite", "*.db"));
    Optional.ofNullable(chooser.showOpenDialog(stage))
        .ifPresent(file -> pathField.setText(file.getAbsolutePath()));
  }
}
