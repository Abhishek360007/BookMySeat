-- V10: an admin can cancel a show. Its bookings are cancelled (paid ones refunded) and it disappears
-- from the public listings; the row stays so customers' bookings still point at it.
ALTER TABLE shows ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED';
ALTER TABLE shows ADD COLUMN cancelled_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE shows ADD CONSTRAINT ck_shows_status CHECK (status IN ('SCHEDULED', 'CANCELLED'));
