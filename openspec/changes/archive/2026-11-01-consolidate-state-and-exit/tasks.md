# Tasks

## 1. Единый дом решений состояния

- [x] 1.1 Добавить в `DbState` предикаты `allowsShutdown()` и `awaitsUserDecision()` по решению 1 design.md; `DatabaseService.mustNotStop` перевести на них и удалить. Проверка: `./gradlew test --tests 'ru.onlychill.bonds2.dbservice.*'` зелёный; новых `instanceof DbState` вне `DbState`/`DatabaseService` не появилось.
- [x] 1.2 Тесты предикатов: полный перебор состояний (sealed) на оба предиката; документирующий тест «новое состояние ломает компиляцию switch-проверки» через исчерпывающий переключатель в тесте. Проверка: тест-ран dbservice зелёный.

## 2. Типизированная причина отказа остановки

- [x] 2.1 Превратить `ShutdownResult` в sealed-интерфейс: `STOPPED`, `TIMED_OUT`, `REFUSED(ShutdownRefusal cause)` с enum `MIGRATING`/`DECISION_PENDING` по решению 2; сервис заполняет причину. Проверка: компиляция, все использования `REFUSED` обновлены.
- [x] 2.2 `DatabaseController`: убрать `instanceof DbState` (:211, :287, :317) — решения по причине `REFUSED` и предикатам; запрос выхода для `Migrating` — по предикату. Тесты существующих сценариев REFUSED-веток зелёные, поведение не изменилось. Проверка: `grep -n "instanceof DbState" ui/DatabaseController.java` пуст; `--tests 'ru.onlychill.bonds2.ui.DatabaseControllerTest'` зелёный с `-Pheadless=true`.
- [x] 2.3 Сброс `abandoned` при отказе остановки (решение 5) + тест гонки «срок истёк → отказ → сервис работоспособен» (сценарий дельты database-lifecycle). Проверка: новый тест зелёный, прогон dbservice зелёный.

## 3. Фаза выхода и пакет `app`

- [x] 3.1 Ввести пакет `app`: `ExitPhase` (`RUNNING`/`EXITING`/`CONCLUDED`, `AtomicReference`) и `ExitCoordinator` по решениям 3-4: таймауты, retry после `REFUSED(DECISION_PENDING)`, порядок «остановка → блокировка → снятие хука», порты диалогов без JavaFX-импортов. Проверка: тест `NoJavaFxDependencyTest`-образца для `app` зелёный, `grep javafx app/*.java` пуст.
- [x] 3.2 Заменить `exiting`/`stopped`/`exitStarted` на фазу; `MigrationFlow` — на один предикат фазы; зомби-фикс: после `CONCLUDED` сбой предупреждения журналируется, выход продолжается (сценарий дельты main-window) + тест «сбой предупреждения после истечения срока завершает приложение». Проверка: тесты ui-области зелёные с `-Pheadless=true`, флаговых полей больше нет.
- [x] 3.3 Перенести тесты машины выхода в `app` (из `DatabaseControllerTest`/`MainWindowAppTest`): таймауты, retry, порядок, повторный выход, внешний хук — без FX-тулкита. Проверка: новые тесты зелёные и быстрые, `./gradlew pitest -Pheadless=true` зелёный с порогом, мутации `app` убиваются.

## 4. Проверки и документация

- [x] 4.1 Прогнать `./gradlew check` и `pitest` (логика dbservice/app — по правилу config.yaml); убедиться, что порог покрытия и мутаций соблюдён после переноса тестов. Проверка: обе команды зелёные.
- [x] 4.2 Проверить README: пользовательское поведение выхода не изменилось, кроме закрытия граничных случаев — правка только при фактическом расхождении. Проверка: README просмотрен, расхождений нет или устранены.

## Workflow follow-up

- После правок Java — прогон субагента `security-auditor` по диффу (правило из AGENTS.md).
- Архивировать изменение после завершения задач и ревью (`/opsx:archive`).
