## 1. Неблокирующие диалоги (D3)

- [x] 1.1 Изменить `Dialogs`: `confirmMigration` возвращает `CompletableFuture<Boolean>`, `warn`/`error` не ждут закрытия; обновить фейки в тестах
- [x] 1.2 Реализовать `FxDialogs` через `Alert.show()` и `setOnHidden`; отмена future закрывает `Alert`; диалоги модальны относительно главного окна
- [x] 1.3 Перевести `DatabaseController.askAboutMigration` на `thenCompose`, удалить `migrationDialogOpen`
- [x] 1.4 Тесты: согласие, отказ, отмена future, результат другой операции во время диалога, поздний результат после `stopped`

## 2. Единый выход при ожидании миграции (D2, часть 1)

- [x] 2.1 `requestExit` при `AwaitingMigration`: отменить диалог, вызвать `declineMigration`, затем остановка
- [x] 2.2 Тесты: выход во время диалога миграции, файл БД не изменён, приложение завершается; `Migrating` по-прежнему даёт предупреждение «Идёт миграция»

## 3. Асинхронная остановка (D1, D2, D4)

- [x] 3.1 Добавить `DatabaseService.shutdownAsync(Duration)` с `TIMED_OUT` по сроку и идемпотентностью; `shutdown(Duration)` — обёртка
- [x] 3.2 Тесты сервиса: остановка не блокирует вызывающего, срок истёк, повторная остановка, `REFUSED` при миграции, запоздавшая БД закрывается
- [x] 3.3 `DatabaseController.requestExit`: флаг `exiting`, `shutdownAsync(30 с)` + `thenAcceptAsync(uiExecutor)`: `STOPPED`/`TIMED_OUT` → `exit.run()`, `REFUSED` → предупреждение и сброс флага
- [x] 3.4 Тесты контроллера: поток FX не блокируется, повторный выход игнорируется, `REFUSED`, `TIMED_OUT`
- [x] 3.5 `DatabaseController.shutdown()` (путь `stop()`): один `shutdown(5 с)`; при `REFUSED` — отказ от ожидающей миграции и ожидание идущей до 30 с без `Thread.sleep`
- [x] 3.6 Тесты `stop()`: срок 5 с для открытия, 30 с для миграции, идемпотентность после выхода через меню; `MainWindowAppTest` при необходимости

## 4. Документация и проверки

- [x] 4.1 Обновить README (поведение выхода и диалогов), если они там описаны
- [x] 4.2 Прогнать `./gradlew check -Pheadless=true`
- [x] 4.3 Прогнать `pitest` для изменённой логики контроллера и сервиса
