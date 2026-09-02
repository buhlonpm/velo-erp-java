-- У зарядника убрано поле «разъём» — лишняя характеристика
ALTER TABLE asset_charger_details DROP COLUMN connector;
