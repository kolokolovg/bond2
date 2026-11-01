// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2;

import ru.onlychill.bonds2.app.AppCore;
import ru.onlychill.bonds2.ui.FxAppView;

/** Точка входа: запускает ядро приложения. */
public final class Main {

  private Main() {}

  /**
   * Запуск GUI; аргументы командной строки игнорируются. Ядро поднимает интерфейс через порт {@link
   * ru.onlychill.bonds2.app.AppView}, держит главный поток до конца цепочки завершения и
   * регистрирует хук завершения JVM: при внешнем завершении (сигнал, остановка из среды разработки)
   * он выполняет лучшую попытку остановить сервис БД с коротким сроком, даже если тулкит уже мёртв.
   */
  public static void main(String[] args) throws InterruptedException {
    new AppCore(new FxAppView()).start();
  }
}
