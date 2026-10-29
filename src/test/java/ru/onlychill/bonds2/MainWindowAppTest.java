package ru.onlychill.bonds2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class MainWindowAppTest {

  // Индексы пунктов меню «Файл»: разделитель стоит между «Закрыть базу» и «Выход»
  private static final int SETTINGS = 0;
  private static final int OPEN = 1;
  private static final int CREATE = 2;
  private static final int CLOSE = 3;
  private static final int EXIT = 5;

  @BeforeAll
  static void startToolkit() throws InterruptedException {
    FxToolkit.start();
  }

  @Test
  void fileMenuContainsDatabaseItemsAndExit() {
    var calls = new ArrayList<String>();
    MenuBar bar =
        MainWindowApp.createMenuBar(recording(calls), new SimpleObjectProperty<>(DbState.CLOSED));

    assertThat(bar.getMenus()).extracting(Menu::getText).containsExactly("Файл");
    var items = bar.getMenus().get(0).getItems();
    assertThat(items)
        .extracting(MenuItem::getText)
        .containsExactly(
            "Настройки…", "Открыть базу", "Создать базу…", "Закрыть базу", null, "Выход");

    items.get(SETTINGS).fire();
    items.get(OPEN).fire();
    items.get(CREATE).fire();
    items.get(EXIT).fire();
    assertThat(calls).containsExactly("settings", "open", "create", "exit");
  }

  @Test
  void shutdownStopsDatabaseBeforeReleasingLock() {
    var calls = new ArrayList<String>();

    MainWindowApp.shutDownInOrder(() -> calls.add("database"), () -> calls.add("lock"));

    assertThat(calls).containsExactly("database", "lock");
  }

  @Test
  void lockIsReleasedEvenWhenDatabaseStopFails() {
    var calls = new ArrayList<String>();

    assertThatThrownBy(
            () ->
                MainWindowApp.shutDownInOrder(
                    () -> {
                      throw new IllegalStateException("сбой");
                    },
                    () -> calls.add("lock")))
        .isInstanceOf(IllegalStateException.class);

    assertThat(calls).containsExactly("lock");
  }

  @Test
  void closeItemRunsCloseActionWhenDatabaseIsOpen() {
    var calls = new ArrayList<String>();
    var items =
        MainWindowApp.createMenuBar(recording(calls), new SimpleObjectProperty<>(DbState.OPEN))
            .getMenus()
            .get(0)
            .getItems();

    items.get(CLOSE).fire();

    assertThat(calls).containsExactly("close");
  }

  @Test
  void menuItemsFollowDatabaseState() {
    var state = new SimpleObjectProperty<>(DbState.CLOSED);
    var items =
        MainWindowApp.createMenuBar(recording(new ArrayList<>()), state)
            .getMenus()
            .get(0)
            .getItems();

    assertThat(disabledFlags(items)).containsExactly(false, false, false, true, false, false);

    state.set(DbState.OPEN);
    assertThat(disabledFlags(items)).containsExactly(false, true, true, false, false, false);

    state.set(DbState.OPENING);
    assertThat(disabledFlags(items)).containsExactly(true, true, true, true, false, false);

    state.set(DbState.MIGRATING);
    assertThat(disabledFlags(items)).containsExactly(true, true, true, true, false, false);
  }

  private static List<Boolean> disabledFlags(List<MenuItem> items) {
    return items.stream().map(MenuItem::isDisable).toList();
  }

  private static MainWindowApp.MenuActions recording(List<String> calls) {
    return new MainWindowApp.MenuActions(
        () -> calls.add("settings"),
        () -> calls.add("open"),
        () -> calls.add("create"),
        () -> calls.add("close"),
        () -> calls.add("exit"));
  }

  @org.junit.jupiter.api.io.TempDir java.nio.file.Path dir;

  @Test
  void acquireInstanceLockReportsRunningInstance() {
    var reports = new ArrayList<String>();
    var file = dir.resolve("bonds2.lock");

    var first = MainWindowApp.acquireInstanceLock(file, reports::add);
    var second = MainWindowApp.acquireInstanceLock(file, reports::add);

    assertThat(first).isNotNull();
    assertThat(second).isNull();
    assertThat(reports).containsExactly("Приложение уже запущено.");
    first.close();
  }

  @Test
  void acquireInstanceLockReportsFailure() throws java.io.IOException {
    java.nio.file.Files.writeString(dir.resolve("blocker"), "файл");
    var reports = new ArrayList<String>();

    var lock = MainWindowApp.acquireInstanceLock(dir.resolve("blocker/bonds2.lock"), reports::add);

    assertThat(lock).isNull();
    assertThat(reports).singleElement().asString().startsWith("Не удалось запустить приложение");
  }
}
