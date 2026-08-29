-- Привязка системных операций покупки к SIM-картам и GPS-трекерам (для синхронизации при правке цены/даты)
ALTER TABLE finance_transactions ADD COLUMN sim_card_id UUID REFERENCES sim_cards (id) ON DELETE SET NULL;
ALTER TABLE finance_transactions ADD COLUMN gps_tracker_id UUID REFERENCES gps_trackers (id) ON DELETE SET NULL;
