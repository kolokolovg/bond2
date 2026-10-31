# Proposal

## Why

Два последних ортогональных дефекта тестовой инфраструктуры из аудита. (1) Хелпер `executeSql(Path, String...)` существует дословно дважды — публичный в `dbservice/TestDatabases` (импортируется в том числе UI-тестами: `DatabaseControllerTest`, `MigrationFlowTest`) и пакетно-приватный в `persistence/DatabaseTestSupport`; дублирование возникло из-за отсутствия нейтрального места для общих тестовых фикстур, а не из-за формы main-классов — SOLID-анализ подтвердил, что декомпозиция его не поглощает. (2) `DatabaseTest.writerWaitsForShortLock` (`DatabaseTest.java:257`) держит транзакцию через `Thread.sleep(500)` — таймингозависимый тест: на медленной CI писатель может не дождаться, на быстрой — фиктивные полсекунды задержки; соседний тест `lockTimeoutFailsExplicitlyAndRollsBack` в том же файле уже показывает детерминированный latch-паттерн.

## What Changes

- Новый нейтральный тестовый пакет `ru.onlychill.bonds2.testutil` с публичным хелпером `executeSql` (SQL отдельным соединением с фиксацией); обе копии удаляются, все вызывающие (dbservice, persistence, ui-тесты) импортируют один хелпер. Фабрики фикстурных БД (`TestDatabases.newDatabaseAt` и прочие) остаются на месте и пользуются общим хелпером.
- `writerWaitsForShortLock` переписывается с фиксированного сна на сигнальную синхронизацию: держатель освобождает блокировку после сигнала «писатель начал попытку» (перед блокирующим вызовом), как в соседнем latch-тесте; утверждения (писатель дождался, итог `2`, без исключений) не меняются.
- Production-код не меняется вовсе; `skip_specs: true`.

## Capabilities

### New Capabilities

_нет_

### Modified Capabilities

_нет_ — меняется только организация тестов и их устойчивость; поведение системы и требования спек не затрагиваются.

## Impact

- Тесты: новый `testutil/…`; `dbservice/TestDatabases.java`, `persistence/DatabaseTestSupport.java` (удаление дублей), `ui/DatabaseControllerTest.java`, `ui/MigrationFlowTest.java`, `persistence/DatabaseSchemaTest.java`, `persistence/DatabaseMigrationTest.java` (перевод импортов), `persistence/DatabaseTest.java` (детерминизация одного теста).
- Проверки: `./gradlew check -Pheadless=true`; `pitest` не требуется — прод-код не меняется (пороги покрытия контролируются `check`).
- Вне рамок: перестройка `Database`/`BackupManager` (программа `clarify-database-bounds`); прочие находки качества тестов (смешанность `DatabaseServiceTest`-кластера, test-only API `SettingsDialog`); поведение `GatedStorage`/`Eventually`/`UiQueue` — без изменений.
