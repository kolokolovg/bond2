-- Начальная версия схемы: доменные таблицы добавляются последующими миграциями.
CREATE TABLE app_info (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) STRICT;
