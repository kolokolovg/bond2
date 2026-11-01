// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import java.util.concurrent.Executor;
import ru.onlychill.bonds2.dbservice.DatabaseService;

/**
 * Порт ядра к интерфейсу пользователя: ядро стартует интерфейс, а не наоборот. Реализация владеет
 * всем UI-стартом — подъёмом тулкита, исполнителем потока интерфейса, главным окном, сообщением об
 * ошибке старта и гашением тулкита при завершении. Предупреждения и ошибки машины выхода — часть
 * окна, поэтому порт наследует {@link ExitView}.
 */
public interface AppView extends ExitView {

  /** Поднимает тулкит и возвращает исполнитель потока интерфейса. */
  Executor startToolkit();

  /**
   * Показывает главное окно, собирая интерфейсную часть на презентере; блокирует до показа окна.
   *
   * @param service сервис жизненного цикла БД: интерфейсная часть подписывается на его состояние
   * @param presenter презентер операций БД
   * @param uiExecutor исполнитель потока интерфейса
   */
  void showMainWindow(DatabaseService service, DbPresenter presenter, Executor uiExecutor);

  /** Сообщение об ошибке старта; блокирует до подтверждения пользователем. */
  void showStartupError(String message);

  /** Гасит тулкит: интерфейсная часть приложения завершается; повторный вызов ничего не делает. */
  void shutdown();
}
