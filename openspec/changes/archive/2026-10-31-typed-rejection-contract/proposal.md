# Proposal

## Why

UI различает «тихие» отклонения операций БД (гонка состояния, начатая остановка) и отклонение «файл уже существует» **разбором русского текста сообщения**: `DatabaseController.isStateOrStoppingRejection` (`src/main/java//ru/onlychill/bonds2/ui/DatabaseController.java:441-444`) делает `message.contains("недопустима в текущем состоянии")`. Литералы рождаются в другом слое (`OpenFailures.notAllowed`, `DatabaseService.MESSAGE_STOPPED`), общий контракт отсутствует: перефразирование сообщения в `dbservice` молча ломает классификацию в UI — пользователь начнёт получать диалоги ошибок на штатных гонках состояния. Ни компилятор, ни errorprone это не ловят. Родственный запах — тесты, утверждающие имя потока (`SETTINGS_THREAD_NAME`, `"bonds2-db"`) и его daemon-флаг: они проверяют реализацию и ломаются при рефакторинге без изменения поведения.

## What Changes

- Результаты отклонения `OpenResult.Rejected` и `CloseResult.Rejected` получают **типизированную причину** (гонка состояния / остановка сервиса / существование файла) отдельным полем; текст сообщения остаётся пользовательским и меняется независимо от причины.
- Все 9 мест производства отклонений в `dbservice` заполняют причину (`OpenFailures.notAllowed` ×5, `MESSAGE_STOPPED` ×2 напрямую и ×2 через функциональный параметр, `OpenFailures.fileExists` ×1).
- `DatabaseController` заменяет разбор строк на сопоставление по причине; `isStateOrStoppingRejection` удаляется. Наблюдаемое поведение не меняется: тихие отклонения остаются тихими (DEBUG-журнал), «файл существует» остаётся диалогом ошибки.
- **BREAKING** (внутренний API): изменение формы записей `OpenResult.Rejected` / `CloseResult.Rejected` — source-несовместимо для всего кода, конструирующего или разбирающего их (только `dbservice`, `ui` и тесты этого приложения; внешних потребителей нет, формат хранения не затронут).
- Тесты заменяют утверждения на имена потоков и daemon-флаг (`DatabaseServiceTest:151-160`, `DatabaseControllerTest:1284,1309`, `SettingsDialogTest:411`) на поведенческие: операции выполняются в одном фоновом потоке, запись настроек не происходит в потоке JavaFX. Именованные потоки в production-коде сохраняются как средство диагностики.

## Capabilities

### New Capabilities

_нет_

### Modified Capabilities

- `database-lifecycle`: добавляется требование о типизированной причине отклонения: результат отклонения операции жизненного цикла обязан нести причину отдельным значением, различимым потребителем без разбора текста сообщения.

## Impact

- Код: `dbservice/OpenResult.java`, `dbservice/CloseResult.java`, `dbservice/OpenFailures.java`, `dbservice/DatabaseService.java`, `ui/DatabaseController.java`.
- Тесты: `DatabaseServiceTest`, `OpenFailuresTest`, `DatabaseControllerTest`, `SettingsDialogTest` (конструкции `Rejected`, утверждения на потоки).
- Проверки: `./gradlew check -Pheadless=true` и `pitest` (затронута логика сервиса БД). `src/verified` не затрагивается.
- Хранение данных, миграции, пути и блокировки — не затрагиваются.
- Вне рамок: остальные кластеры найденных запахов (мёртвый код `retryable`/`resolvedDatabasePath`, дублирование helper'ов, разбиение `DatabaseControllerTest`, конкурентность `StatePublisher` и остановки) — отдельные изменения.
