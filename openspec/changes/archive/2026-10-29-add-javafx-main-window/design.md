# Design

## Context

Проект — Java 21, Gradle Kotlin DSL, каталог версий `gradle/libs.versions.toml`, без модульного `module-info`. Есть строгие проверки: Error Prone + NullAway (ошибка), SpotBugs, Spotless (google-java-format), JaCoCo, PIT (`Main` исключён). Сейчас `Main` — консольный расчёт купона с тестируемым `run(...)`; консольный режим больше не нужен.

## Goals / Non-Goals

**Goals:**
- Минимальное JavaFX-окно с меню «Файл → Выход».
- Сборка и `gradlew check` остаются зелёными.

**Non-Goals:**
- Экраны расчёта облигаций, FXML, CSS, локализация, jlink/jpackage-упаковка.
- Подтверждение выхода.

## Decisions

- **Плагин `org.openjfx.javafxplugin`** (модули `javafx.controls`) вместо ручного подключения платформенных jar: сам выбирает classifier под ОС и настраивает module-path для `run`. Альтернатива — ручные зависимости с classifier; отвергнуто как хрупкое. Версию JavaFX зафиксировать в каталоге версий (актуальная LTS-линия на момент реализации).
- **Без `module-info.java`**: проект класспатчевый; плагин справляется с этим. Класс `Main` не наследует `Application`, а вызывает `Application.launch(MainWindowApp.class, args)` — это избегает ошибки «JavaFX runtime components are missing» при запуске с classpath.
- **Класс `MainWindowApp extends Application`**: строит `BorderPane` с `MenuBar` в top; `Menu «Файл»` → `MenuItem «Выход»` с `setOnAction(e -> Platform.exit())`. Закрытие окна по умолчанию завершает приложение при единственном окне (implicit exit). Сборка меню вынесена в статический метод, возвращающий `MenuBar`, для тестируемости.
- **Консольный режим удаляется**: `Main.run(...)`, разбор аргументов и вывод в консоль убираются вместе с `MainTest`; `Main.main` только запускает GUI. `Coupon`/`CouponMath` и их тесты сохраняются как доменный код. Так как `Main` теперь тонкая обёртка, исключение `Main` из PIT остаётся.
- **Тесты**: проверка структуры меню без показа окна (создание `MenuBar` требует JavaFX-тулкита — запускать через `Platform.startup` в тесте; в headless CI — `-Dglass.platform=Monocle`/пропуск при отсутствии дисплея). Класс UI исключить из PIT при нестабильности.

## Risks / Trade-offs

- [Headless CI/тесты без дисплея] → инициализировать тулкит только при необходимости, структуру меню проверять без `Stage`; при невозможности — исключить UI-класс из тестов JaCoCo-порога с обоснованием.
- [NullAway/SpotBugs шумят на JavaFX API] → точечные настройки/`@SuppressFBWarnings`-аналоги только при реальных срабатываниях.
- [Загрузка JavaFX-артефактов из Maven Central увеличивает сборку] → приемлемо.
- [Изменение поведения `gradlew run`] → зафиксировано в proposal; README обновить.
