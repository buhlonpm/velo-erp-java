-- Бэкфилл статуса MOUNTED: смонтированные АКБ/зарядники, созданные до появления статуса
UPDATE assets SET status = 'MOUNTED'
WHERE status = 'AVAILABLE' AND id IN (
    SELECT asset_id FROM asset_battery_details WHERE bike_id IS NOT NULL
    UNION
    SELECT asset_id FROM asset_charger_details WHERE bike_id IS NOT NULL
);
