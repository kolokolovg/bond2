# Tasks

## 1. Остановка контроллера

- [x] 1.1 Добавить флаг остановки в `DatabaseController`: после `shutdown()` результаты и ошибки фоновых операций игнорируются, открытая БД закрывается. Проверка: юнит-тесты на заглушках `Background`/`Dialogs` («результат пришёл после остановки», «ошибка пришла после остановки») проходят.
- [x] 1.2 Изменить `Background.run` так, чтобы он возвращал `Future<?>` работы, и хранить его в контроллере. `shutdown()` ждёт его до 30 секунд, закрывает результат-`Database` и продолжает без ожидания по таймауту. Проверка: тесты «shutdown при OPENING», «shutdown при MIGRATING» и «таймаут» проходят.
- [x] 1.3 Обновить `FxBackground`: возвращать дескриптор работы, сделать поток `bonds2-db` не демоном. Проверка: `FxBackgroundTest` дополнен проверкой дескриптора и проходит.

## 2. Порядок завершения приложения

- [x] 2.1 Вынести порядок «`shutdown()` контроллера, затем снятие `InstanceLock`» из `MainWindowApp.stop()` в метод, не использующий JavaFX, и вызвать его из `stop()`. Проверка: юнит-тест на порядок вызовов проходит.

## 3. Тесты без дисплея

- [x] 3.1 В `build.gradle.kts` добавить флаг `-Pheadless=true`: для `test` и `pitest` передаются `-Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw`. Проверка: `env -u DISPLAY -u WAYLAND_DISPLAY ./gradlew test -Pheadless=true` проходит, а без флага тот же запуск падает на `Unable to open DISPLAY`.
- [x] 3.2 В `.github/workflows/ci.yml` перейти на `distribution: liberica`, `java-package: jdk+fx` и передать `-Pheadless=true` в `check` и `pitest`. Проверка: прогон CI зелёный.
- [x] 3.3 В `README.md` убрать фразу «нужен дисплей», описать `-Pheadless=true`. Проверка: описанная команда запускается как написано.

## 4. Интеграция

- [x] 4.1 Выполнить `./gradlew check pitest openjmlProve -Pheadless=true` без дисплея и убедиться, что покрытие и порог мутантов не упали.

## Workflow follow-up

- Архивировать изменение после ревью.
