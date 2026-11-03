-- V14: seat categories with their own prices, and aisle gaps in the seat map.
-- Every existing seat becomes REGULAR, which costs shows.price as before, so existing shows don't change.
ALTER TABLE seats ADD COLUMN category VARCHAR(20) NOT NULL DEFAULT 'REGULAR';
ALTER TABLE seats ADD CONSTRAINT ck_seats_category CHECK (category IN ('REGULAR', 'PREMIUM', 'RECLINER'));

-- Prices of the other categories; a category without a row here costs the show's (regular) price
CREATE TABLE show_category_prices (
    show_id  BIGINT         NOT NULL,
    category VARCHAR(20)    NOT NULL,
    price    NUMERIC(10, 2) NOT NULL,
    CONSTRAINT pk_show_category_prices PRIMARY KEY (show_id, category),
    CONSTRAINT fk_show_category_prices_show FOREIGN KEY (show_id) REFERENCES shows (id) ON DELETE CASCADE,
    CONSTRAINT ck_show_category_prices_category CHECK (category IN ('PREMIUM', 'RECLINER')),
    CONSTRAINT ck_show_category_prices_price CHECK (price >= 0)
);

-- Seat numbers after which the seat map shows an aisle, e.g. '4,8'
ALTER TABLE shows ADD COLUMN aisle_after VARCHAR(50);
