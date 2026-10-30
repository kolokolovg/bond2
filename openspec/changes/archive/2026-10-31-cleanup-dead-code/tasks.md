# Tasks

## 1. Settings без зависимости от persistence

- [x] 1.1 Перед удалением проверить покрытие поведения «пустой путь = путь по умолчанию» тестами контроллера (автооткрытие и авто-создание по `defaultFile()` в `DatabaseControllerTest`); при зазоре — добавить недостающий тест. Проверка: перечень покрывающих тестов зафиксирован в описании задачи/PR, зазоров нет. — Покрытие подтверждено, зазоров нет: авто-открытие — `firstRunWithoutSettingsFileCreatesDefaultDatabaseWithoutWarning` (строки 349–359), `resetSettingsStillOpenExistingDefaultDatabase` (335–347); авто-создание — `missingDefaultPathDatabaseIsCreatedAutomatically` (286–296); семантика «null = по умолчанию» — `SettingsTest.defaultsUseDefaultPathAndEnableAutoOpen`.
- [x] 1.2 Удалить `Settings.resolvedDatabasePath()`, импорт `ru.onlychill.bonds2.persistence.DataDirectory` из `Settings` и тесты `missingPathResolvesToDefaultDatabaseFile`, `configuredPathResolvesToItself` из `SettingsTest`. Проверка: `./gradlew test --tests '*SettingsTest' --tests '*DatabaseControllerTest*' -Pheadless=true` проходит; `grep -rn "resolvedDatabasePath\|persistence.DataDirectory" src/main/java/ru/onlychill/bonds2/settings` пуст.

## 2. OpenFailures без неиспользуемого предиката

- [x] 2.1 Удалить `OpenFailures.retryable()`; удалить его тесты из `OpenFailuresTest`; утверждение в `DatabaseServiceTest` (около строки 342) переписать на равенство причины `REASON_BUSY` (граничный случай: прочие причины не помечаются занятостью — уже покрыто соседними тестами, сохранить их). Проверка: `./gradlew test --tests '*OpenFailuresTest' --tests '*DatabaseServiceTest*' -Pheadless=true` проходит; `grep -rn "retryable" src` пуст.

## 3. SqliteSupport без перегрузки для тестов

- [x] 3.1 Удалить `SqliteSupport.maintenanceProperties(Duration)`; в `SqliteSupportTest` проверки свойств (строки ~79-93) переключить на `maintenanceProperties()`, сценарии с коротким таймаутом (строки ~139, 168) — на пакетно-приватную `existingFileProperties(Duration)` (граничные случаи таймаута блокировки сохраняются). Проверка: `./gradlew test --tests '*SqliteSupportTest' -Pheadless=true` проходит; `grep -rn "maintenanceProperties(Duration\|maintenanceProperties(timeout" src` пуст.

## 4. Финальные проверки

- [x] 4.1 Проверить README на упоминание удалённых элементов, обновить при необходимости; прогнать `./gradlew check -Pheadless=true` и `pitest`. Проверка: обе задачи зелёные, покрытие строк и доля убитых мутантов не ниже порогов из gradle.properties.
