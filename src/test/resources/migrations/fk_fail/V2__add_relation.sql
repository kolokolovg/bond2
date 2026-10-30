-- Родитель и потомок со ссылкой на него: применяются успешно
CREATE TABLE parent (
    id INTEGER PRIMARY KEY
) STRICT;

CREATE TABLE child (
    id        INTEGER PRIMARY KEY,
    parent_id INTEGER NOT NULL REFERENCES parent (id)
) STRICT;
