-- Атрибуция списаний на велосипед убрана: «Вложено» = покупка + расходы + смонтированное железо
ALTER TABLE assets DROP COLUMN written_off_attributed_to;
