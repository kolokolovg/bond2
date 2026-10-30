# Proposal

## Why

В production-коде остались элементы, которые не использует ни один production-потребитель — их держат живыми только тесты: `Settings.resolvedDatabasePath()` (используется только `SettingsTest`; production резолвит путь сам в `DatabaseController.open`), `OpenFailures.retryable()` (12 вызовов в тестах, 0 в production; классификация занятости как повторимой причины остаётся в `REASON_BUSY` + подсказке сообщения), `SqliteSupport.maintenanceProperties(Duration)` (перегрузка-проходной слой только для тестов; тесты в том же пакете могут звать пакетно-приватную `existingFileProperties(Duration)`). Такие элементы расширяют поверхность API без пользы, дублируют логику (`resolvedDatabasePath` дублирует резолв контроллера) и создают ложную связность: `Settings` ради одного мёртвого метода импортирует `persistence.DataDirectory`, связывая слой настроек со слоем хранения.

## What Changes

- Удалить `Settings.resolvedDatabasePath()` и импорт `ru.onlychill.bonds2.persistence.DataDirectory` из `Settings` — слой настроек перестаёт зависеть от слоя хранения. Два теста резолва в `SettingsTest` удаляются; поведение «пустой путь = путь по умолчанию» остаётся в production (`DatabaseController.open`) и покрыто тестами контроллера (`defaultFile()`).
- Удалить `OpenFailures.retryable()` и его тесты. Классификация занятости не меняется: причина `REASON_BUSY` и подсказка «Повторите операцию позже» остаются; утверждение в `DatabaseServiceTest` переписывается на проверку причины.
- Удалить перегрузку `SqliteSupport.maintenanceProperties(Duration)`; тесты переключаются на существующую пакетно-приватную `existingFileProperties(Duration)` (для проверок свойств — на `maintenanceProperties()` без аргумента).
- Наблюдаемое поведение, хранение данных, форматы файлов и тексты сообщений не меняются — чистый рефакторинг, спеки не затрагиваются (`skip_specs: true`).

## Capabilities

### New Capabilities

_нет_

### Modified Capabilities

_нет_ — изменения не затрагивают ни одно требование: `app-settings` описывает семантику «пустой путь = путь по умолчанию», которая остаётся реализованной и покрытой; `database-lifecycle` требует пометки занятости повторимой причиной — пометка остаётся причиной `REASON_BUSY` с подсказкой в сообщении, удаляется только неиспользуемый предикат над ней.

## Impact

- Код: `settings/Settings.java`, `dbservice/OpenFailures.java`, `persistence/SqliteSupport.java`.
- Тесты: `settings/SettingsTest.java`, `dbservice/OpenFailuresTest.java`, `dbservice/DatabaseServiceTest.java`, `persistence/SqliteSupportTest.java`.
- Проверки: `./gradlew check -Pheadless=true`, `pitest` (затронуты настройки и логика сервиса БД). `src/verified` не затрагивается.
- Координация: change `typed-rejection-contract` (ещё не применён) правит те же файлы `OpenFailures.java`, `OpenFailuresTest.java`, `DatabaseServiceTest.java` — другие строки, конфликт тривиален; порядок применения любой.
- Вне рамок: test-only API `SettingsDialog` (используется тестами — это вопрос redesign тестового драйвера, относится к будущему изменению по качеству тестов, не мёртвый код); дублирование helper'ов; разбиение `DatabaseControllerTest`; конкурентность `StatePublisher` и остановки; удаление `DataDirectory`-зависимости из `MainWindowApp` (там импорт используется production-кодом).
