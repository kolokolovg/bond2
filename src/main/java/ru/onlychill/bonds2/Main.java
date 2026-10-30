// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2;

import javafx.application.Application;
import ru.onlychill.bonds2.ui.MainWindowApp;

/** Точка входа: запускает главное окно. */
public final class Main {

  private Main() {}

  /**
   * Запуск GUI; аргументы командной строки игнорируются. До запуска JavaFX регистрируется хук
   * завершения JVM: при внешнем завершении (сигнал, остановка из среды разработки) он выполняет
   * лучшую попытку остановить сервис БД с коротким сроком, даже если тулкит уже мёртв. Хук работает
   * и тогда, когда JavaFX не запустился вовсе: без созданного контроллера делать нечего.
   */
  public static void main(String[] args) {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(MainWindowApp::stopDatabaseOnExternalShutdown, "bonds2-shutdown"));
    Application.launch(MainWindowApp.class);
  }
}
