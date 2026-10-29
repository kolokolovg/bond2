-- Тестовый набор миграций: начальная версия схемы (одинакова во всех наборах)
CREATE TABLE app_info (
    key   TEXT PRIMARY KEY,
    value TEXT NOT NULL
) STRICT;
