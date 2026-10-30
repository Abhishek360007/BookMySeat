-- V4: show search (GET /api/shows/search) filters and sorts by start time
CREATE INDEX idx_shows_start_time ON shows (start_time);
