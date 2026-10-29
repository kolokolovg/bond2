## 1. Распознавание пустого файла

- [x] 1.1 В `Database.inspect` заменить `userTables()`-проверку пустоты на: `sqlite_master` пуст, `PRAGMA application_id = 0`, `PRAGMA user_version = 0`; наличие `flyway_schema_history` определять отдельно
- [x] 1.2 Убедиться, что `Database.initialize` и `resetToEmpty` вызываются только для файлов, признанных пустыми по новому определению; обновить Javadoc `State.EMPTY` и `inspect`
- [x] 1.3 Тесты `DatabaseTest`: файл только с view, только с trigger, только с индексом, с `sqlite_stat1`, с `user_version=5`, с `application_id` ≠ 0 → `FOREIGN` и файл не изменён
- [x] 1.4 Тесты: пустой SQLite без объектов и 0 байт → `EMPTY`, инициализация успешна; неудачная инициализация возвращает файл к пустому состоянию
- [x] 1.5 Проверить `DatabaseControllerTest`: чужой файл с views даёт «не база bonds2» без изменения файла

## 2. Каталог данных

- [x] 2.1 В `DataDirectory` добавить помощник «переменная окружения как абсолютный путь» (непустая, корректная, абсолютная)
- [x] 2.2 Linux: использовать помощник для `XDG_DATA_HOME`, иначе `~/.local/share`
- [x] 2.3 Windows: использовать `LOCALAPPDATA` через помощник, иначе `~/AppData/Local`; `APPDATA` не использовать
- [x] 2.4 Тесты `DataDirectoryTest`: абсолютный, относительный, пустой, пробельный и некорректный `XDG_DATA_HOME`; Windows с `LOCALAPPDATA`, без него, с относительным; `APPDATA` игнорируется; macOS без изменений

## 3. Документация и проверка

- [x] 3.1 README: формулировка «SQLite без объектов схемы» и описание чужой базы; таблица путей (Windows — `%LOCALAPPDATA%\bonds2`) в разделах про хранилище данных и файл настроек
- [x] 3.2 Прогнать `./gradlew check` и `./gradlew pitest`; при необходимости обновить пороги не нужно, только добавить недостающие тесты
