## Context

`DatabaseController.requestExit()` и `shutdown()` вызываются в потоке JavaFX и блокируются на
`DatabaseService.shutdown(30s)`. В `shutdown()` дальше идёт `declineMigration().join()` (до 30 с), цикл
`Thread.sleep(20)` до 30 с и повторный `shutdown(30s)`. `Dialogs.confirmMigration/warn/error` используют
`Alert.showAndWait()` в обработчиках `thenAcceptAsync(..., runLater)` — это вложенный цикл событий.
Флаг `migrationDialogOpen` и ветка `return` в `requestExit()` защищают от выхода во время диалога.

## Goals / Non-Goals

**Goals:**
- Поток JavaFX не блокируется остановкой сервиса при выходе через меню/окно.
- Диалоги не создают вложенный цикл событий.
- Выход при ожидании решения о миграции определён: отказ и завершение.

**Non-Goals:**
- Блокировка `Database.close()` долгими транзакциями, `fsync` настроек, автосоздание БД после сброса
  настроек, `StatePublisher.request(n <= 0)`.
- Индикатор «Закрытие…» в строке состояния (не нужен).

## Decisions

### D1. `DatabaseService.shutdownAsync()`
Возвращает `CompletableFuture<ShutdownResult>`; срок задаётся параметром и применяется через `orTimeout`/
`completeOnTimeout(TIMED_OUT)` с установкой `abandoned = true`. `shutdown(Duration)` реализуется как
`shutdownAsync(timeout).get(...)` с прежним контрактом (`REFUSED`, `STOPPED`, `TIMED_OUT`), поэтому тесты
сервиса и вызовы в обход меню сохраняются. Повторный вызов возвращает тот же future (`shutdownFuture`).
- *Альтернатива:* оставить синхронный вызов и уменьшить таймаут до 3–5 с — окно всё равно замерзает.
- *Альтернатива:* модальное окно «Закрытие…» — избыточно, пользователь индикатор не просил.

### D2. Асинхронный `requestExit()`
Алгоритм в потоке FX:
1. `Migrating` → `dialogs.warn(MIGRATION_EXIT_WARNING)`, выход.
2. Если `exiting` уже установлен → выход (повторный запрос игнорируется).
3. `AwaitingMigration` → отмена future диалога (диалог закрывается), `declineMigration()`.
4. `exiting = true`; `service.shutdownAsync(SHUTDOWN_TIMEOUT)` → `thenAcceptAsync(uiExecutor)`:
   `STOPPED`/`TIMED_OUT` → `stopped = true`, `exit.run()`; `REFUSED` → `exiting = false`, `warn`.
Результаты операций, пришедшие после установки `stopped`, игнорируются (как сейчас).
- *Альтернатива:* оставить `return` при `AwaitingMigration` — поведение недостижимо при модальном диалоге
  и хрупко; единый путь через отказ совпадает с тем, что `shutdown()` уже делает при выходе в обход меню.

### D3. Неблокирующие диалоги
`Dialogs.confirmMigration(String)` → `CompletableFuture<Boolean>`; `FxDialogs` вызывает `alert.show()` и
завершает future в `setOnHidden`; отмена future закрывает `Alert`. `warn`/`error` — `show()` без ожидания.
Диалоги остаются модальными (`initOwner` + `Modality.WINDOW_MODAL`), меню уже заблокировано состоянием
(`allows*`). `askAboutMigration` строится на `thenCompose`; `migrationDialogOpen` удаляется.
- *Альтернатива:* `showAndWait` через отдельный `runLater` — вложенный цикл остаётся, а
  `IllegalStateException` во время анимации/раскладки не исключается.
Цена: тесты `DatabaseControllerTest` переписываются под future-интерфейс; фейковый `Dialogs` возвращает
`completedFuture`, FX-тулкит для логики контроллера не нужен.

### D4. `stop()` в обход меню
`MainWindowApp.stop()` → `DatabaseController.shutdown()`: один вызов `service.shutdown(5 с)`; если результат
`REFUSED` — отказ от ожидающей миграции и ожидание идущей миграции до 30 секунд без опроса
(`shutdownAsync(30 с)` после `declineMigration`). Штатный выход через меню уже остановил сервис, поэтому
вызов идемпотентен и мгновенен.

## Failure behavior

- Таймаут остановки: поток БД — демон, процесс завершается; WAL открытой БД остаётся на диске и
  применяется при следующем открытии (как при сбое закрытия в `doClose`). Данные не теряются.
- Выход в обход меню во время миграции: ожидание до 30 секунд, как раньше; при превышении миграция
  прерывается жёстко. Файл остаётся в состоянии до миграции, резервная копия существует (удаление старых
  копий выполняется только после успешной миграции), восстановление `BackupManager.restore` не
  вызывается. Риск принят, поведение не хуже прежнего.
- Отказ диалога миграции при выходе: файл БД не изменяется.

## Platform notes

Изменения не затрагивают пути, файлы и блокировки; Linux, macOS и Windows ведут себя одинаково.
`Alert.show()` и `setOnHidden` одинаковы на всех платформах JavaFX.

## Risks / Trade-offs

- Переписывание тестов контроллера: основной объём работы — [риск регрессий] → сначала тесты на новый
  интерфейс, затем реализация.
- Диалог закрывается программно при выходе: нужен тест отмены future в `FxDialogs`.
- Мутационное покрытие (`pitest`) для ветвей `requestExit` нужно сохранить.
