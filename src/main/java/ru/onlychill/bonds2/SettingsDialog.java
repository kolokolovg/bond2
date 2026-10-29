// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2;

import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;
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
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsValidator;

/**
 * Модальное окно настроек БД: путь к файлу с кнопкой «Обзор…», флажок автооткрытия, «Сохранить» и
 * «Отмена».
 *
 * <p>Проверку пути делает {@link SettingsValidator}, сохранение — переданный обработчик. Если
 * запись не удалась, окно остаётся открытым и показывает причину.
 */
final class SettingsDialog {

  /** Ширина текстовых блоков окна, px: длинные подсказки и ошибки переносятся. */
  private static final double WRAP_WIDTH = 520;

  private final Stage stage;
  private final TextField pathField = new TextField();
  private final CheckBox autoOpenBox = new CheckBox("Открывать базу при запуске");
  private final Label errorLabel = new Label();
  private final Consumer<Settings> saver;

  /**
   * @param owner главное окно, над которым открывается диалог
   * @param current текущие настройки: из них заполняются поля
   * @param defaultFile путь по умолчанию: подсказка для пустого поля
   * @param saver сохраняет настройки; при ошибке записи бросает {@link UncheckedIOException}
   */
  SettingsDialog(
      @Nullable Window owner, Settings current, Path defaultFile, Consumer<Settings> saver) {
    this.saver = saver;
    this.stage = new Stage();

    Path path = current.databasePath();
    pathField.setText(path == null ? "" : path.toString());
    HBox.setHgrow(pathField, Priority.ALWAYS);
    var browseButton = new Button("Обзор…");
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

    var saveButton = new Button("Сохранить");
    saveButton.setDefaultButton(true);
    saveButton.setOnAction(event -> save());
    var cancelButton = new Button("Отмена");
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

  /**
   * «Сохранить»: проверяет путь, передаёт настройки обработчику и закрывает окно. Ошибка проверки
   * или записи показывается в окне, настройки при этом не меняются.
   */
  void save() {
    String text = pathField.getText();
    Optional<String> problem = SettingsValidator.validateDatabasePath(text);
    if (problem.isPresent()) {
      errorLabel.setText(problem.get());
      return;
    }
    errorLabel.setText("");
    try {
      saver.accept(new Settings(Settings.parseDatabasePath(text), autoOpenBox.isSelected()));
    } catch (UncheckedIOException e) {
      errorLabel.setText(ErrorMessages.of(e));
      return;
    }
    stage.close();
  }

  /** «Отмена» и закрытие окна: настройки не меняются. */
  void cancel() {
    stage.close();
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
