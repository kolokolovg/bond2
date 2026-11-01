// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.layout.BorderPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import ru.onlychill.bonds2.dbservice.DbState;

/**
 * Строитель главного окна: меню «Файл» (резервная копия, повторное открытие БД, выход), строка
 * состояния внизу и обработчик закрытия окна. Логика операций БД — в презентере ядра, интерфейсная
 * адаптация — в {@link DatabaseController}; строитель только собирает окно и связывает его с
 * контроллером. Без {@code Application}: окно строится после подъёма тулкита ядром.
 */
final class MainWindow {

  /** Действия пунктов меню «Файл». */
  record MenuActions(Runnable backup, Runnable retryOpen, Runnable exit) {}

  private MainWindow() {}

  /** Собирает и показывает главное окно; вызывается в потоке JavaFX после получения блокировки. */
  static void show(Stage stage, DatabaseController db) {
    var status = new Label();
    status.textProperty().bind(db.statusProperty());
    status.setPadding(new Insets(4, 8, 4, 8));

    var root = new BorderPane();
    root.setTop(createMenuBar(menuActions(stage, db), db.stateProperty()));
    root.setBottom(status);

    stage.setTitle("Bonds");
    stage.setScene(new Scene(root, 800, 600));
    // Закрытие окна кнопкой закрытия — то же, что «Файл → Выход»
    stage.setOnCloseRequest(
        event -> {
          event.consume();
          db.requestExit();
        });
    stage.show();
  }

  /** Строка меню с пунктами «Файл»; доступность пунктов следует за состоянием БД. */
  static MenuBar createMenuBar(MenuActions actions, ObservableValue<DbState> state) {
    var exit = new MenuItem("Выход");
    exit.setOnAction(event -> actions.exit().run());
    return new MenuBar(
        new Menu(
            "Файл",
            null,
            menuItem(
                "Сделать резервную копию…", actions.backup(), state.map(s -> !s.allowsBackup())),
            menuItem("Повторить открытие", actions.retryOpen(), state.map(s -> !s.allowsOpen())),
            new SeparatorMenuItem(),
            exit));
  }

  private static MenuItem menuItem(
      String text, Runnable action, ObservableValue<Boolean> disabled) {
    var item = new MenuItem(text);
    item.setOnAction(event -> action.run());
    item.disableProperty().bind(disabled);
    return item;
  }

  private static MenuActions menuActions(Stage stage, DatabaseController db) {
    return new MenuActions(
        () -> chooseBackupTarget(stage).ifPresent(db::backupTo), db::retryOpen, db::requestExit);
  }

  /**
   * Единственный в приложении файловый диалог: выбор места резервной копии. Пустой результат
   * означает отмену. Имя без расширения дополняется {@code .db}, чтобы копия была видна фильтрам
   * диалогов приложения.
   */
  private static Optional<Path> chooseBackupTarget(Stage stage) {
    var chooser = new FileChooser();
    chooser.setTitle("Сделать резервную копию");
    chooser.setInitialFileName("bonds2-copy.db");
    chooser
        .getExtensionFilters()
        .add(new FileChooser.ExtensionFilter("База данных SQLite", "*.db"));
    return Optional.ofNullable(chooser.showSaveDialog(stage))
        .map(File::toPath)
        .map(MainWindow::withDbExtension);
  }

  /**
   * Дописывает расширение {@code .db} к выбранному имени без расширения: по последней точке в имени
   * файла. Имя с расширением не меняется.
   */
  static Path withDbExtension(Path chosen) {
    Path fileName = chosen.getFileName();
    if (fileName == null) {
      // Путь без имени файла (корень): дописывать нечего
      return chosen;
    }
    String name = fileName.toString();
    int dot = name.lastIndexOf('.');
    if (dot > 0 && dot < name.length() - 1) {
      return chosen;
    }
    return chosen.resolveSibling(name + ".db");
  }
}
