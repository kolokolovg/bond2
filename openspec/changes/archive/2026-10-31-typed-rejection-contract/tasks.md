# Tasks

## 1. Типизированная причина в контракте dbservice

- [x] 1.1 Ввести `enum RejectionCause { STATE, STOPPED, FILE_EXISTS }`; добавить поле `cause` в записи `OpenResult.Rejected` и `CloseResult.Rejected` (Javadoc — по-русски); заполнить причину во всех точках производства: 5× `notAllowed` → `STATE`, 2× `MESSAGE_STOPPED` напрямую и `submit(...)` с `BiFunction<RejectionCause, String, T>` → `STOPPED`, `OpenFailures.fileExists` → `FILE_EXISTS`. Проверка: `./gradlew compileJava` проходит, в main-коде не осталось конструирования `Rejected` без причины.
- [x] 1.2 Механически обновить тестовые конструкции и сопоставления `Rejected` (`DatabaseServiceTest`, `OpenFailuresTest`, `DatabaseControllerTest`, `SettingsDialogTest`). Проверка: `./gradlew compileTestJava` проходит.
- [x] 1.3 Добавить контрактные тесты причин (включая граничные случаи): операция в неверном состоянии → `STATE`; операция после начала остановки → `STOPPED`; создание при существующем файле → `FILE_EXISTS`, файл не изменён; отклонения всех видов сохраняют пользовательский текст сообщения. Проверка: `./gradlew test --tests '*DatabaseServiceTest*' --tests '*OpenFailuresTest*' -Pheadless=true` проходит.

## 2. UI различает отклонения по причине

- [x] 2.1 В `DatabaseController.onCreateResult` заменить `isStateOrStoppingRejection(...)` сопоставлением по `cause` (`STATE`/`STOPPED` — тихий DEBUG-журнал, `FILE_EXISTS` — диалог ошибки); метод-парсер удалить. Проверка: UI-тесты — при `STATE`/`STOPPED` с произвольным текстом сообщения диалог не показывается, при `FILE_EXISTS` с произвольным текстом показывается; `./gradlew test --tests '*DatabaseControllerTest*' -Pheadless=true` проходит.
- [x] 2.2 Обновить тесты тихого отклонения и «файл существует» на конструирование по `cause`, а не по тексту (граничный случай: сообщение перефразировано — классификация не меняется). Проверка: соответствующие тесты `DatabaseControllerTest` проходят в headless-режиме.

## 3. Тесты поведения вместо утверждений об именах потоков

- [x] 3.1 `DatabaseServiceTest.operationsRunOnOneDaemonThreadNamedBondsDb` (строки 151-160): заменить утверждения имени потока и daemon-флага на поведенческие — все операции выполняются в одном потоке, отличном от потока вызова. Проверка: тест проходит; `grep -rn 'isEqualTo("bonds2-db")' src/test` пуст.
- [x] 3.2 `DatabaseControllerTest` (строки 1284, 1309) и `SettingsDialogTest` (строка 411): заменить `isEqualTo(SETTINGS_THREAD_NAME)` на утверждение, что запись настроек выполнена вне потока JavaFX. Проверка: тесты проходят; `grep -rn 'SETTINGS_THREAD_NAME' src/test` пуст; константа в main-коде сохранена.

## 4. Финальные проверки

- [x] 4.1 Проверить README на упоминание контракта отклонений, обновить при необходимости; прогнать `./gradlew check -Pheadless=true` и `pitest` (затронута логика сервиса БД; пороги из gradle.properties). Проверка: обе задачи зелёные, покрытие и доля убитых мутантов не ниже порогов.
