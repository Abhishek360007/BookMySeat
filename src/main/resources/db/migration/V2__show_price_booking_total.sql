-- V2: booking total snapshot, plus a CHECK on show price.
-- shows.price already exists (NOT NULL) since V1, so it only gets the CHECK here.

ALTER TABLE shows ADD CONSTRAINT ck_shows_price CHECK (price >= 0);

-- Safe pattern for a NOT NULL column on a table that may have rows:
-- 1) add as nullable, 2) backfill, 3) enforce
ALTER TABLE bookings ADD COLUMN total_amount NUMERIC(10, 2);
UPDATE bookings b
SET total_amount = s.price * (SELECT COUNT(*) FROM booking_seats bs WHERE bs.booking_id = b.id)
FROM shows s
WHERE s.id = b.show_id AND b.total_amount IS NULL;
ALTER TABLE bookings ALTER COLUMN total_amount SET NOT NULL;
ALTER TABLE bookings ADD CONSTRAINT ck_bookings_total CHECK (total_amount >= 0);
