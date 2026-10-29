# bonds2

Приложение на Java 21 (Gradle) с встроенными тестами и инструментами верификации кода.

## Требования

Нужен любой JDK для запуска Gradle (17+; `JAVA_HOME` или `java` в `PATH`). JDK 21 для компиляции Gradle скачает сам через toolchain (foojay), если его нет локально.

## Сборка и запуск

```bash
./gradlew build                      # сборка + все обязательные проверки
./gradlew run                        # запуск с параметрами по умолчанию
./gradlew run --args="100000 750 2"  # номинал (коп.), ставка (б.п.), выплат в год
```

Приложение печатает купонный платёж; при некорректных аргументах пишет ошибку в stderr и завершается с кодом 1.

## Тесты

| Команда | Что делает |
| --- | --- |
| `./gradlew test` | модульные (JUnit + AssertJ) и property-based (jqwik) тесты, затем отчёт JaCoCo |
| `./gradlew jacocoTestCoverageVerification` | падает, если покрытие строк ниже `coverage.minLine` (0.80) |
| `./gradlew pitest` | мутационное тестирование (PIT), порог `mutation.threshold` (70%); отчёт `build/reports/pitest/` |

Пороги задаются в `gradle.properties` и переопределяются, например: `-Pcoverage.minLine=0.9`.
Отчёт покрытия: `build/reports/jacoco/test/html/index.html`. `check` включает тесты и проверку покрытия, но не `pitest` (он медленнее; запускается в CI отдельно).

## Статический анализ и стиль

Всё ниже входит в `./gradlew check`:

- **Error Prone + NullAway** — при компиляции; нарушение null-безопасности (`@Nullable` из JSpecify) — ошибка сборки. Проверяются пакеты `ru.onlychill.bonds2`.
- **SpotBugs** — анализ байткода (`spotbugsMain`, `spotbugsTest`), отчёты в `build/reports/spotbugs/`.
- **Spotless** (google-java-format, ktlint для `*.gradle.kts`) — `./gradlew spotlessCheck` проверяет, `./gradlew spotlessApply` исправляет.

## Формальная верификация (JML + OpenJML)

Чистая арифметика купона вынесена в `src/verified/java/ru/onlychill/bonds2/CouponMath.java` и снабжена JML-контрактами (пред-/постусловия). Каталог входит в основной source set, поэтому код одновременно компилируется и доказывается.

```bash
./gradlew openjmlProve   # статическое доказательство контрактов (OpenJML ESC)
```

- Первый запуск скачивает OpenJML (`openjml.version` в `gradle.properties`, ~500 МБ) в `tools/` (в `.gitignore`).
- OpenJML поставляется со своим JDK 21, поэтому весь проект использует Java 21: единая версия для компиляции, тестов, анализаторов и верификации. Переход на более новую Java возможен только после выхода OpenJML с её поддержкой.
- Спецификации пишутся блочными комментариями `/*@ ... @*/`: google-java-format портит строчные `//@`.
- Нарушенный контракт приводит к ненулевому коду выхода и сообщению `The prover cannot establish an assertion (Postcondition ...)`.
- Задача не входит в `check` (долгая загрузка и работа), но обязательна в CI.

## CI

`.github/workflows/ci.yml` на каждый push и pull request выполняет `./gradlew check pitest openjmlProve`.
