package ru.onlychill.bonds2;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import javafx.application.Application;
import javafx.application.Platform;
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
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.persistence.DataDirectory;
import ru.onlychill.bonds2.settings.SettingsStore;

/**
 * Главное окно приложения: меню «Файл» (настройки, открытие, создание и закрытие БД, выход) и
 * строка состояния БД внизу. Логика БД — в {@link DatabaseController}, здесь только сборка
 * интерфейса.
 */
public final class MainWindowApp extends Application {

  /** Действия пунктов меню «Файл». */
  record MenuActions(
      Runnable settings, Runnable open, Runnable create, Runnable close, Runnable exit) {}

  private @Nullable DatabaseController controller;

  @Override
  public void start(Stage stage) {
    var db =
        new DatabaseController(
            new SettingsStore(DataDirectory.settingsFile()),
            DataDirectory.databaseFile(),
            new FxBackground(),
            new FxDialogs(stage),
            Platform::exit);
    controller = db;

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
    // БД открывается после показа окна; ошибка показывается диалогом, окно остаётся рабочим
    db.openOnStartup();
  }

  @Override
  public void stop() {
    DatabaseController db = controller;
    if (db != null) {
      db.shutdown();
    }
  }

  /** Строка меню с пунктами «Файл»; доступность пунктов следует за состоянием БД. */
  static MenuBar createMenuBar(MenuActions actions, ObservableValue<DbState> state) {
    var exit = new MenuItem("Выход");
    exit.setOnAction(event -> actions.exit().run());
    return new MenuBar(
        new Menu(
            "Файл",
            null,
            menuItem("Настройки…", actions.settings(), state.map(s -> !s.allowsSettings())),
            menuItem("Открыть базу", actions.open(), state.map(s -> !s.allowsOpen())),
            menuItem("Создать базу…", actions.create(), state.map(s -> !s.allowsCreate())),
            menuItem("Закрыть базу", actions.close(), state.map(s -> !s.allowsClose())),
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
        () ->
            new SettingsDialog(stage, db.settings(), DataDirectory.databaseFile(), db::saveSettings)
                .showAndWait(),
        db::openDatabase,
        () -> chooseNewDatabase(stage).ifPresent(db::createDatabase),
        db::closeDatabase,
        db::requestExit);
  }

  /** Диалог выбора файла для новой БД; пустой результат означает отмену. */
  private static Optional<Path> chooseNewDatabase(Stage stage) {
    var chooser = new FileChooser();
    chooser.setTitle("Создать базу данных");
    chooser.setInitialFileName("bonds2.db");
    chooser
        .getExtensionFilters()
        .add(new FileChooser.ExtensionFilter("База данных SQLite", "*.db"));
    return Optional.ofNullable(chooser.showSaveDialog(stage)).map(File::toPath);
  }
}
