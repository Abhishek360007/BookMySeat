-- V13: an outbox email can carry one attachment (the e-ticket QR code on a booking confirmation).
-- Kept with the email, so a retry sends exactly what was queued with the booking.
ALTER TABLE email_outbox ADD COLUMN attachment_name VARCHAR(100);
ALTER TABLE email_outbox ADD COLUMN attachment_type VARCHAR(100);
ALTER TABLE email_outbox ADD COLUMN attachment_content BYTEA;
