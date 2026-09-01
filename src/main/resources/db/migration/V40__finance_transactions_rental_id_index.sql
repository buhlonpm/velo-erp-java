-- Индекс на rental_id: paidSumByRentalId/refundedSumByRentalId вызываются на каждую аренду
-- в списке аренд и дашборде — без индекса каждый SUM делал seq-scan всей таблицы операций.
CREATE INDEX idx_finance_transactions_rental_id ON finance_transactions (rental_id);
