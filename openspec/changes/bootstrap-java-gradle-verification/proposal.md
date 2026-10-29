# Proposal

## Why

Проект `bonds2` пока пуст. Нужна основа: приложение на Java 21 со сборкой на Gradle, в которую с первого дня встроены автоматические тесты и инструменты верификации кода. Так качество проверяется механически, а не вручную, и не придётся вводить проверки в уже написанный код.

## What Changes

- Создать Gradle-проект (Gradle Wrapper, Kotlin DSL, version catalog) с Java 21 через toolchain.
- Добавить минимальное исполняемое приложение (точка входа, один доменный класс с проверяемой логикой) как носитель для проверок.
- Подключить тестирование: модульные тесты, property-based тесты, отчёт о покрытии, мутационное тестирование.
- Подключить верификацию: статический анализ (Error Prone + NullAway, SpotBugs), форматирование/стиль (Spotless), формальная верификация контрактов (JML + OpenJML) для выделенного чистого модуля.
- Единая команда `./gradlew check` запускает всё обязательное; тяжёлые проверки (мутации, формальная верификация) доступны отдельными задачами и в CI.
- Добавить CI-workflow, запускающий проверки.

## Capabilities

### New Capabilities
- `java-gradle-build`: воспроизводимая сборка и запуск приложения на Java 21 через Gradle.
- `automated-testing`: модульные и property-based тесты, пороги покрытия и мутационного тестирования.
- `code-verification`: статический анализ, контроль стиля и формальная верификация контрактов.

### Modified Capabilities
<!-- Нет: существующих спецификаций нет. -->

## Impact

- Новые файлы в корне: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew*`, `.github/workflows/ci.yml`, `README.md`.
- Новый код: `src/main/java`, `src/test/java`, каталог `src/verified/java` с верифицируемым модулем и JML-контрактами.
- Зависимости (только build/test): JUnit 5, AssertJ, jqwik, JaCoCo, PIT, Error Prone, NullAway, SpotBugs, Spotless, OpenJML.
- Координаты: группа `ru.onlychill`, пакет `ru.onlychill.bonds2`.
- Версия JDK: 21 для всего проекта, так как OpenJML работает только на встроенном javac 21.
- Окружение: JDK в PATH сейчас нет — JDK 21 должен подтягиваться Gradle toolchain auto-provisioning.
