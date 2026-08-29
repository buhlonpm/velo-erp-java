-- Дочерние позиции аренды (комплект: АКБ, смонтированная на велосипеде-родителе)
ALTER TABLE rental_items ADD COLUMN parent_item_id UUID REFERENCES rental_items (id);
