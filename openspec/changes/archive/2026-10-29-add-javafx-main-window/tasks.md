# Tasks

## 1. Сборка

- [x] 1.1 Добавить версию JavaFX и плагин `org.openjfx.javafxplugin` в `gradle/libs.versions.toml` и `build.gradle.kts` (модуль `javafx.controls`); проверка: `./gradlew compileJava` проходит.

## 2. Главное окно

- [x] 2.1 Создать `MainWindowApp extends Application` с `BorderPane`, `MenuBar`, меню «Файл» и пунктом «Выход» (`Platform.exit()`), заголовком окна; проверка: `./gradlew compileJava spotlessCheck` проходит (NullAway, Error Prone).
- [x] 2.2 Изменить `Main.main` на запуск `Application.launch(MainWindowApp.class, args)`, удалить `Main.run` и консольный вывод, удалить `MainTest`; проверка: `./gradlew compileJava test` проходит, тесты `Coupon*` зелёные.

## 3. Тесты и проверки

- [x] 3.1 Добавить тест структуры меню (меню «Файл» содержит пункт «Выход», действие вызывает выход) с учётом отсутствия дисплея; проверка: `./gradlew test` проходит.
- [x] 3.2 При необходимости скорректировать исключения PIT/JaCoCo для UI-класса с обоснованием в комментарии; проверка: `./gradlew check` проходит полностью.
- [x] 3.3 Вручную запустить `./gradlew run`: окно открывается, «Файл → Выход» и кнопка закрытия завершают процесс; обновить README про запуск GUI.
