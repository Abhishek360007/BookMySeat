-- V12: e-ticket check-in at the door. Set once; a second scan of the same ticket is refused.
ALTER TABLE bookings ADD COLUMN checked_in_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE bookings ADD COLUMN checked_in_by BIGINT;
ALTER TABLE bookings ADD CONSTRAINT fk_bookings_checked_in_by FOREIGN KEY (checked_in_by) REFERENCES users (id) ON DELETE SET NULL;
