-- «В комплекте с GPS-трекером» для SIM-карт
ALTER TABLE sim_cards ADD COLUMN bundled_tracker_id UUID REFERENCES gps_trackers (id);
