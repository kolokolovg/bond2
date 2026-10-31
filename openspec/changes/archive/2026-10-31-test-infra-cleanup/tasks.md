# Tasks

## 1. Общий хелпер executeSql

- [x] 1.1 Создать `ru.onlychill.bonds2.testutil` с публичным `executeSql(Path, String...)` (Javadoc на русском); удалить копии из `TestDatabases` и `DatabaseTestSupport`, перевести вызывающие (`TestDatabases` изнутри, `DatabaseControllerTest`, `MigrationFlowTest`, `DatabaseSchemaTest`, `DatabaseMigrationTest`, при необходимости `DatabaseTestSupport`/`DatabaseTest`) на общий хелпер. Проверка: `./gradlew compileTestJava` проходит; `grep -rn "static void executeSql" src/test` даёт ровно одно вхождение — в `testutil`.

## 2. Детерминизация теста короткой блокировки

- [x] 2.1 В `DatabaseTest.writerWaitsForShortLock` заменить `Thread.sleep(500)` на сигнальную схему: держатель после `locked.countDown()` ждёт `writerStarted.await(30s)` и фиксирует транзакцию; основной поток сигнализирует `writerStarted` перед блокирующим `inTransaction`. Утверждения без изменений (итог `2`, без исключений). Граничный случай: сигнал писателя строго до блокирующего вызова — взаимоблокировки нет. Проверка: `./gradlew test --tests '*DatabaseTest' -Pheadless=true` зелёные; `grep -rn "Thread.sleep" src/test/java/ru/onlychill/bonds2/persistence/DatabaseTest.java` пуст.

## 3. Финальные проверки

- [x] 3.1 Проверить README на упоминание тестовой инфраструктуры, обновить при необходимости; прогнать `./gradlew check -Pheadless=true` (`pitest` не требуется — прод-код не меняется). Проверка: `check` зелёный, покрытие строк не ниже порога из gradle.properties.
