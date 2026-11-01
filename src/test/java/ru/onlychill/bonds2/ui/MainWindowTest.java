// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javafx.application.Platform;
import javafx.beans.property.SimpleObjectProperty;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.onlychill.bonds2.dbservice.DbState;

/**
 * Строитель главного окна без {@code Application}: состав меню «Файл», доступность пунктов по
 * состоянию БД и дописывание расширения выбранной цели копии. Проверки цепочки выхода и блокировки
 * переехали в тесты ядра ({@code AppCoreTest}); сборка окна через клей — в {@link FxAppViewTest}.
 */
class MainWindowTest {

  // Индексы пунктов меню «Файл»: разделитель стоит между «Повторить открытие» и «Выход»
  private static final int BACKUP = 0;
  private static final int RETRY_OPEN = 1;
  private static final int EXIT = 3;

  private static final Path FILE = Path.of("bonds.db");
  private static final DbState OPEN_STATE = new DbState.Open(FILE);

  @BeforeAll
  static void startToolkit() throws InterruptedException {
    FxToolkit.start();
    // Тесты создают контролы: тулкит не должен гаснуть между тестами
    Platform.setImplicitExit(false);
  }

  @Test
  void fileMenuContainsBackupRetryAndExit() {
    var calls = new ArrayList<String>();
    MenuBar bar =
        MainWindow.createMenuBar(
            recording(calls), new SimpleObjectProperty<DbState>(DbState.CLOSED));

    assertThat(bar.getMenus()).extracting(Menu::getText).containsExactly("Файл");
    var items = bar.getMenus().get(0).getItems();
    assertThat(items)
        .extracting(MenuItem::getText)
        .containsExactly("Сделать резервную копию…", "Повторить открытие", null, "Выход");

    items.get(BACKUP).fire();
    items.get(RETRY_OPEN).fire();
    items.get(EXIT).fire();
    assertThat(calls).containsExactly("backup", "retryOpen", "exit");
  }

  @Test
  void menuItemsFollowDatabaseState() {
    var state = new SimpleObjectProperty<DbState>(DbState.CLOSED);
    var items =
        MainWindow.createMenuBar(recording(new ArrayList<>()), state).getMenus().get(0).getItems();

    // Закрыта: копирование недоступно, повторное открытие доступно
    assertThat(disabledFlags(items)).containsExactly(true, false, false, false);

    state.set(OPEN_STATE);
    // Открыта: копирование доступно, повторное открытие недоступно
    assertThat(disabledFlags(items)).containsExactly(false, true, false, false);

    state.set(new DbState.Opening(FILE));
    assertThat(disabledFlags(items)).containsExactly(true, true, false, false);

    state.set(new DbState.AwaitingMigration(FILE));
    assertThat(disabledFlags(items)).containsExactly(true, true, false, false);

    state.set(new DbState.Migrating(FILE));
    assertThat(disabledFlags(items)).containsExactly(true, true, false, false);

    state.set(new DbState.BackingUp(Path.of("bonds2-copy.db")));
    assertThat(disabledFlags(items)).containsExactly(true, true, false, false);
  }

  @Test
  void newDatabaseNameWithoutExtensionGetsDbAppended(@TempDir Path dir) {
    assertThat(MainWindow.withDbExtension(dir.resolve("база")).getFileName().toString())
        .isEqualTo("база.db");

    // Имя с расширением не меняется: чувствительность к регистру не требуется
    assertThat(MainWindow.withDbExtension(dir.resolve("база.db")))
        .isEqualTo(dir.resolve("база.db"));
    assertThat(MainWindow.withDbExtension(dir.resolve("база.DB")))
        .isEqualTo(dir.resolve("база.DB"));
    assertThat(MainWindow.withDbExtension(dir.resolve("архив.sqlite")))
        .isEqualTo(dir.resolve("архив.sqlite"));
    // Скрытый файл без расширения тоже получает .db
    assertThat(MainWindow.withDbExtension(dir.resolve(".bonds")))
        .isEqualTo(dir.resolve(".bonds.db"));
  }

  private static List<Boolean> disabledFlags(List<MenuItem> items) {
    return items.stream().map(MenuItem::isDisable).toList();
  }

  private static MainWindow.MenuActions recording(List<String> calls) {
    return new MainWindow.MenuActions(
        () -> calls.add("backup"), () -> calls.add("retryOpen"), () -> calls.add("exit"));
  }
}
