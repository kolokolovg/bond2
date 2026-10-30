-- Намеренный сбой: строка-сирота нарушает внешний ключ
INSERT INTO child (id, parent_id) VALUES (1, 999);
