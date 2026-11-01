# Tasks

## 1. Хранилище на java.util.prefs

- [x] 1.1 Ввести шов `SettingsBackend` (`get`/`put`/`flush`) и переписать `SettingsStore` поверх него по решению 3 design.md: прод-реализация на `Preferences.userNodeForPackage(Settings.class)`, API — `Settings load()` / `void save(Settings)`; чтение: `BackingStoreException` и некорректное значение → дефолт ключа + WARNING в журнал; запись: `BackingStoreException` → `UncheckedIOException` «Не удалось сохранить настройки». Проверка: компиляция, класс без файловых операций.
- [x] 1.2 Тесты нового хранилища на in-memory реализации `SettingsBackend` по сценарием дельты: раундтрип «Перезапуск», «Отсутствующее значение» (дефолт без прерывания), «Некорректное значение» (дефолт + событие в журнале), ошибка `flush` → `UncheckedIOException`, сбой чтения → дефолты. Проверка: новые тесты зелёные, покрывают все ветки.
- [x] 1.3 Удалить файловую машину: старую реализацию `SettingsStore` (BOM, recovery, `.bad`, временные файлы), `DataDirectory.settingsFile()` и его тесты, `SettingsStoreProperties`, `SettingsStoreTest`. Проверка: `grep -r "settingsFile\|badCopy\|TEMP_PREFIX" src/` пуст, сборка зелёная.

## 2. Интерфейс: синхронное сохранение

- [x] 2.1 `SettingsDialog`: убрать состояние записи (блокировки, `saving`, повторный ввод во время записи); «Сохранить» = валидация → `store.save` → закрытие окна, при ошибке записи — причина и окно открыто. Тесты по сценариям «Сохранение», «Отмена», «Недопустимый путь», «Ошибка записи» (запуск `-Pheadless=true`). Проверка: тесты окна зелёные, состояния «идёт запись» в коде нет.
- [x] 2.2 `MainWindowApp`: синхронное чтение настроек при старте вместо асинхронной загрузки; убрать показ предупреждения о сбросе и путь к `.bad`-копии. Проверка: запуск приложения с `-Pheadless=true` открывает окно, предупреждений о настройках нет, тесты окна зелёные.
- [x] 2.3 Удалить `SettingsPersistence` и ожидание записи настроек в цепочке выхода (`DatabaseController`): выделенный поток и `future` записи исчезают, ожидание закрытия БД в цепочке выхода сохраняется. Проверка: `grep -rn "SETTINGS_THREAD_NAME\|SettingsPersistence" src/` пуст, тесты выхода (`MainWindowAppTest`, `DatabaseControllerTest`) зелёные с `-Pheadless=true`.

## 3. Проверки и документация

- [x] 3.1 Прогнать `./gradlew check` и `pitest` (логика настроек — по правилу config.yaml); убедиться, что порог покрытия из `gradle.properties` соблюдён после удаления старых тестов. Проверка: обе команды зелёные.
- [x] 3.2 Обновить README: убрать упоминания `settings.properties`, восстановления и `.bad`-копий, описать платформенное хранение настроек. Проверка: `grep -n "settings.properties\|\.bad" README*.md` пуст или описывает новое поведение.

## Workflow follow-up

- После правок Java — прогон субагента `security-auditor` по диффу (правило из AGENTS.md).
- Архивировать изменение после завершения задач и ревью (`/opsx:archive`).
