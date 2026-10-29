CREATE TABLE item_note (
    item_id INTEGER NOT NULL REFERENCES item (id),
    note    TEXT NOT NULL
) STRICT;
