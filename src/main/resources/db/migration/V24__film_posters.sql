-- V24: film posters. The image (always a 480 x 720 JPEG, see PosterImages) is kept in its own table so listing films
-- never reads it; films.poster_version says whether there is one and puts its version in the poster's URL.
CREATE TABLE film_posters (
    film_id    BIGINT PRIMARY KEY REFERENCES films (id) ON DELETE CASCADE,
    content    BYTEA                       NOT NULL,
    version    VARCHAR(16)                 NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
ALTER TABLE films ADD COLUMN poster_version VARCHAR(16);
