-- V25: following a film. Followers are emailed when it gets new showtimes (ShowAnnouncements): each show is announced
-- once, a little after it was created (so an admin can still fix or cancel it first).
CREATE TABLE film_follows (
    user_id    BIGINT                      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    film_id    BIGINT                      NOT NULL REFERENCES films (id) ON DELETE CASCADE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, film_id)
);
CREATE INDEX idx_film_follows_film ON film_follows (film_id);

ALTER TABLE shows ADD COLUMN created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT now();
-- Existing shows are not news: marked as announced, so the first run emails nobody about them
ALTER TABLE shows ADD COLUMN followers_notified_at TIMESTAMP(6) WITH TIME ZONE;
UPDATE shows SET followers_notified_at = now();
CREATE INDEX idx_shows_to_announce ON shows (film_id) WHERE followers_notified_at IS NULL;
