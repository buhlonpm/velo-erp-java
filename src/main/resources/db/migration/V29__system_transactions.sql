-- Системные операции (созданы доменными действиями, не руками) нельзя править/удалять через финансы.
ALTER TABLE finance_transactions ADD COLUMN is_system BOOLEAN NOT NULL DEFAULT FALSE;

-- Покупки/продажи активов: связь с операцией хранится в ленте событий
UPDATE finance_transactions
SET is_system = TRUE
WHERE id IN (SELECT transaction_id FROM asset_events WHERE transaction_id IS NOT NULL);

-- Покупки/продажи GPS-трекеров и SIM-карт прямой связи не имеют — опознаём по шаблону комментария
UPDATE finance_transactions
SET is_system = TRUE
WHERE comment LIKE 'Покупка GPS-трекера:%'
   OR comment LIKE 'Покупка SIM-карты:%'
   OR comment LIKE 'Продажа GPS-трекера:%';
