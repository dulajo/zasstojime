-- Trasa linky z GTFS `route_long_name` pro popisky v žebříčcích, např. „Praha - Milovice“.
-- Doplní ji nejbližší import jízdního řádu.
ALTER TABLE routes ADD COLUMN long_name TEXT;
