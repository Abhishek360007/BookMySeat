-- V23: the shows' rating columns from V20 go. V22 moved ratings to the films, and the release that ran it no
-- longer read these columns, so no running version uses them any more.
ALTER TABLE shows DROP CONSTRAINT ck_shows_rating;
ALTER TABLE shows DROP COLUMN rating_sum;
ALTER TABLE shows DROP COLUMN rating_count;
