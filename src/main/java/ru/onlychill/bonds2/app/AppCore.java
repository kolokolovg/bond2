// SPDX-License-Identifier: Apache-2.0

package ru.onlychill.bonds2.app;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import org.jspecify.annotations.Nullable;
import ru.onlychill.bonds2.dbservice.DatabaseService;
import ru.onlychill.bonds2.dbservice.ErrorMessages;
import ru.onlychill.bonds2.persistence.DataDirectory;
import ru.onlychill.bonds2.persistence.InstanceLock;
import ru.onlychill.bonds2.persistence.PersistenceException;

/**
 * Composition root приложения: стартовый конвейер «тулкит → блокировка единственного экземпляра →
 * главное окно → открытие БД → READY», хук завершения JVM и цепочка штатного завершения. Ядро
 * стартует интерфейс через порт {@link AppView}, а не наоборот: пакет ядра не зависит от тулкита и
 * тестируется без него.
 *
 * <p>{@link #start()} держит главный поток до конца цепочки завершения — как раньше его держал
 * блокирующий запуск JavaFX. Отказ шага до показа окна завершает процесс: сообщение показывается
 * портом, окно не показывается, БД не открывается. Хук завершения JVM регистрируется до открытия БД
 * и разряжается штатной цепочкой: внешнее завершение (сигнал, остановка из среды разработки)
 * останавливает сервис БД лучшей попыткой, если штатный выход этого ещё не сделал.
 */
public final class AppCore {

  /** Имя потока, на котором выполняется цепочка завершения приложения. */
  static final String EXIT_THREAD_NAME = "bonds2-exit";

  private static final System.Logger LOG = System.getLogger(AppCore.class.getName());

  private final AppView view;
  private final DatabaseService service;
  private final Path lockFile;
  private final Hooks hooks;

  /** Открывается в конце цепочки завершения: главный поток ждёт его до конца работы. */
  private final CountDownLatch finished = new CountDownLatch(1);

  /**
   * Презентер и части завершения; назначаются в {@link #start()} до открытия БД. {@code volatile}:
   * читаются потоком цепочки завершения и хуком завершения JVM.
   */
  private volatile @Nullable DbPresenter presenter;

  private volatile @Nullable InstanceLock instanceLock;

  private volatile @Nullable Thread hook;

  /** Готовность конвейера: итог открытия единственной БД доставлен в поток интерфейса. */
  private volatile boolean ready;

  /** Ядро с настоящими файлами приложения. */
  public AppCore(AppView view) {
    this(view, new DatabaseService(), DataDirectory.lockFile(), Hooks.runtime());
  }

  /** Ядро с заданным сервисом и файлом блокировки; тесты подменяют их и хуки завершения JVM. */
  AppCore(AppView view, DatabaseService service, Path lockFile, Hooks hooks) {
    this.view = view;
    this.service = service;
    this.lockFile = lockFile;
    this.hooks = hooks;
  }

  /**
   * Запуск приложения; аргументы командной строки игнорируются. Возвращается при отказе шага до
   * показа окна — после сообщения пользователю — и после окончания цепочки завершения. Сбой сборки
   * ядра или окна гасит тулкит и пробрасывается: иначе не-демон-поток интерфейса удержал бы процесс
   * живым без окна и с занятой блокировкой экземпляра.
   */
  @SuppressWarnings("FutureReturnValueIgnored")
  public void start() throws InterruptedException {
    Executor ui = view.startToolkit();
    try {
      run(ui);
    } catch (InterruptedException e) {
      // Главный поток прерван: тулкит гасится и здесь, иначе не-демон-поток интерфейса удержал бы
      // процесс живым без окна и с занятой блокировкой экземпляра
      Thread.currentThread().interrupt();
      view.shutdown();
      throw e;
    } catch (RuntimeException | Error e) {
      LOG.log(System.Logger.Level.ERROR, "Сбой запуска приложения", e);
      // Зарегистрированный к этому моменту хук завершения JVM остановит сервис лучшей попыткой
      // при выходе процесса; блокировку снимет операционная система
      view.shutdown();
      throw e;
    }
  }

  private void run(Executor ui) throws InterruptedException {
    Optional<InstanceLock> lock;
    try {
      // Блокировка берётся до показа окна и открытия БД: второй экземпляр не должен их трогать
      lock = InstanceLock.tryAcquire(lockFile);
    } catch (PersistenceException e) {
      startupFailure("Не удалось запустить приложение: " + ErrorMessages.of(e));
      return;
    }
    if (lock.isEmpty()) {
      startupFailure("Приложение уже запущено.");
      return;
    }
    instanceLock = lock.get();
    var db = new DbPresenter(service, view, this::beginExit, ui);
    presenter = db;
    // Хук регистрируется до открытия БД: внешнее завершение останавливает сервис с любым итогом
    // запуска; разряжается хук последним шагом штатной цепочки
    Thread registered = new Thread(this::stopForExternalShutdown, "bonds2-shutdown");
    hook = registered;
    hooks.register(registered);
    view.showMainWindow(service, db, ui);
    // БД открывается после показа окна; готовность фиксируется доставкой итога открытия
    var delivery = db.openOnStartup();
    if (delivery != null) {
      delivery.thenRun(this::markReady);
    }
    finished.await();
  }

  /** Готовность конвейера: итог открытия доставлен; окно и БД открыты до этого. */
  boolean ready() {
    return ready;
  }

  private void markReady() {
    ready = true;
  }

  /** Отказ шага до показа окна: сообщение пользователю и завершение процесса с кодом 0. */
  private void startupFailure(String message) {
    view.showStartupError(message);
    view.shutdown();
  }

  /**
   * Завершение приложения из меню и окна; возвращается сразу и не блокирует поток интерфейса.
   * Интерфейс гаснет немедленно, цепочка завершения выполняется на выделенном потоке {@link
   * #EXIT_THREAD_NAME}. Вызывается машиной выхода в потоке интерфейса; повторный вызов второй
   * цепочки не создаёт — однократность держит машина выхода.
   */
  private void beginExit() {
    launchExitThread();
    view.shutdown();
  }

  /** Запускает цепочку завершения на выделенном потоке {@link #EXIT_THREAD_NAME}. */
  private void launchExitThread() {
    // Не демон: процесс живёт до конца цепочки и завершается сам с кодом 0
    var thread = new Thread(this::exitChain, EXIT_THREAD_NAME);
    thread.setDaemon(false);
    thread.start();
  }

  /**
   * Цепочка штатного завершения: остановка БД → освобождение блокировки экземпляра → разоружение
   * хука JVM → освобождение главного потока → гашение тулкита. Порядок и однократность первых трёх
   * шагов держит машина выхода; здесь — сборка цепочки из частей приложения. Блокировка
   * освобождается и при сбое остановки БД, а главный поток и тулкит отпускаются при любом исходе.
   */
  private void exitChain() {
    try {
      presenter().runExitChain(this::releaseLock, this::disarmHook);
    } finally {
      finished.countDown();
      view.shutdown();
    }
  }

  /**
   * Тело хука завершения JVM: лучшая попытка остановить сервис БД, если штатный выход его ещё не
   * остановил. Не касается интерфейса — тулкит к моменту внешнего завершения может быть уже мёртв.
   * Сбой останавливается журналом и завершению не мешает; идемпотентно со штатным выходом, который
   * разряжает хук.
   */
  void stopForExternalShutdown() {
    try {
      presenter().stopForExternalShutdown();
    } catch (RuntimeException | Error e) {
      LOG.log(System.Logger.Level.WARNING, "Не удалось остановить сервис БД при завершении", e);
    }
  }

  private void releaseLock() {
    InstanceLock lock = instanceLock;
    if (lock != null) {
      lock.close();
    }
  }

  private void disarmHook() {
    Thread registered = hook;
    if (registered == null) {
      return;
    }
    try {
      hooks.disarm(registered);
    } catch (IllegalStateException e) {
      // Завершение JVM уже идёт: хук и так выполнится, разряжать нечего
      LOG.log(System.Logger.Level.DEBUG, "Хук завершения не снят: выход JVM уже идёт", e);
    }
  }

  private DbPresenter presenter() {
    return Objects.requireNonNull(presenter, "ядро не собрано");
  }

  /** Регистрация и разоружение хука завершения JVM; тесты подменяют записью вызовов. */
  interface Hooks {

    static Hooks runtime() {
      Runtime runtime = Runtime.getRuntime();
      return new Hooks() {
        @Override
        public void register(Thread hook) {
          runtime.addShutdownHook(hook);
        }

        @Override
        public void disarm(Thread hook) {
          runtime.removeShutdownHook(hook);
        }
      };
    }

    /** Регистрирует хук завершения JVM. */
    void register(Thread hook);

    /** Разоружает хук: штатный выход физически забирает его у среды выполнения. */
    void disarm(Thread hook);
  }
}
