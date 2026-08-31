-- Статус CANCELLED упразднён: отмены больше нет, ошибочный черновик просто удаляется.
-- Стираем ранее отменённые аренды бесследно (тот же порядок, что в RentalService.wipe):
-- лента событий (FK на операции) → операции → продления → позиции/график → аренда.

DELETE FROM rental_events WHERE rental_id IN (SELECT id FROM rentals WHERE status = 'CANCELLED');
DELETE FROM finance_transactions WHERE rental_id IN (SELECT id FROM rentals WHERE status = 'CANCELLED');
DELETE FROM rental_extensions WHERE rental_id IN (SELECT id FROM rentals WHERE status = 'CANCELLED');
DELETE FROM rental_schedule_items WHERE rental_id IN (SELECT id FROM rentals WHERE status = 'CANCELLED');
DELETE FROM rental_items WHERE rental_id IN (SELECT id FROM rentals WHERE status = 'CANCELLED');
DELETE FROM rentals WHERE status = 'CANCELLED';
