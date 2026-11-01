// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

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
import ru.onlychill.bonds2.dbservice.ErrorMessages;
import ru.onlychill.bonds2.settings.Settings;
import ru.onlychill.bonds2.settings.SettingsValidator;

/**
 * Модальное окно настроек БД: путь к файлу с кнопкой «Обзор…», флажок автооткрытия, «Сохранить» и
 * «Отмена».
 *
 * <p>Проверку пути делает {@link SettingsValidator}, сохранение — переданный обработчик;
 * платформенное хранилище пишется за миллисекунды, поэтому запись выполняется синхронно в потоке
 * интерфейса и окно закрывается сразу после неё. Если запись не удалась, окно остаётся открытым и
 * показывает причину ошибки.
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
  private final Consumer<Settings> saver;

  /**
   * @param owner главное окно, над которым открывается диалог
   * @param current текущие настройки: из них заполняются поля
   * @param defaultFile путь по умолчанию: подсказка для пустого поля
   * @param saver сохраняет настройки синхронно; ошибка записи даёт {@link UncheckedIOException}
   */
  SettingsDialog(
      @Nullable Window owner, Settings current, Path defaultFile, Consumer<Settings> saver) {
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
   * «Сохранить»: проверяет путь и сохраняет настройки синхронно; при успехе окно закрывается.
   * Ошибка проверки или записи показывается в окне, настройки при этом не применяются.
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
      stage.close();
    } catch (UncheckedIOException e) {
      errorLabel.setText(ErrorMessages.of(e));
    }
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
