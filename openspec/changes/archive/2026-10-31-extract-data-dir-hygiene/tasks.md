# Tasks

## 1. Компонент StampedFiles

- [x] 1.1 Создать `ru.onlychill.bonds2.files.StampedFiles` (публичный, только JDK-зависимости): `stamp(Clock)` — `yyyyMMdd-HHmmss`; `reserve(anchor, base)` — атомарное создание первого свободного имени, суффиксы `-1`, `-2`…, прочий `IOException` — вызывающему; `sweepOrphans(dir, glob, what)` — no-op без каталога, удаление обычных файлов по glob, не-файл и сбои — WARNING с `what`, не прерывает. Javadoc на русском. Проверка: `./gradlew compileJava` проходит.
- [x] 1.2 Тесты `StampedFilesTest` (граничные случаи): свободное имя; занято — суффиксы без перезаписи; занят каталог с тем же именем — следующее; штамп по внедрённому `Clock` фиксирован; очистка удаляет подходящие файлы и трогает только обычные; сбой удаления не прерывает обход; отсутствующий каталог — no-op. Проверка: `./gradlew test --tests '*StampedFilesTest' -Pheadless=true` зелёные.

## 2. SettingsStore на компоненте

- [x] 2.1 Перепроводка: `removeOrphanedTempFiles`/`deleteOrphan` → `sweepOrphans` (glob `settings-*.tmp`); `badCopyPath` → `reserve` + `Files.copy(..., REPLACE_EXISTING)`; при сбое копирования — удаление зарезервированного пустого файла, WARNING и `LoadResult(defaults, null, true)`, как сегодня; константы `BAD_STAMP` удалить, штамп — из компонента. Проверка: `./gradlew test --tests '*SettingsStoreTest' -Pheadless=true` зелёные без правки утверждений (сценарии bad-копии и оставшегося временного файла).

## 3. BackupManager на компоненте

- [x] 3.1 Перепроводка: `reserveBackupFile` → `reserve` (обёртка `IOException` в `MigrationException` сохранена), `removeOrphanedRestoreFiles`/`deleteRestoreLeftover` → `sweepOrphans` (glob `<имя-БД>.*.restore`); константу `BACKUP_STAMP` удалить. Проверка: `./gradlew test --tests '*DatabaseMigrationTest' --tests '*DatabaseServiceTest' -Pheadless=true` зелёные без правки утверждений (имена копий, очистка `.restore`).

## 4. Финальные проверки

- [x] 4.1 Проверить README на упоминание временных файлов/имён копий, обновить при необходимости; прогнать `./gradlew check -Pheadless=true` и `pitest`. Проверка: обе задачи зелёные, покрытие строк и доля убитых мутантов не ниже порогов из gradle.properties; `grep -rn "removeOrphaned\|deleteRestoreLeftover\|badCopyPath\|reserveBackupFile" src/main` пуст.
