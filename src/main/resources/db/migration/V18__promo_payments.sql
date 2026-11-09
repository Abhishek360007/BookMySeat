-- V18: promo codes with online payment. A checkout reserves one use of the code for its Razorpay order (RESERVED);
-- the use becomes REDEEMED when the payment books the seats, and is RELEASED when the order expires unpaid or the
-- money goes back. The payment keeps the code and the discount, like the booking does.
ALTER TABLE payments ADD COLUMN discount_amount NUMERIC(10, 2) NOT NULL DEFAULT 0;
ALTER TABLE payments ADD COLUMN promo_code VARCHAR(20);
ALTER TABLE payments ADD CONSTRAINT ck_payments_discount CHECK (discount_amount >= 0);

ALTER TABLE promo_redemptions ADD COLUMN payment_id BIGINT;
ALTER TABLE promo_redemptions ADD CONSTRAINT fk_promo_redemptions_payment
    FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE CASCADE;
ALTER TABLE promo_redemptions ADD CONSTRAINT uk_promo_redemptions_payment UNIQUE (payment_id);
ALTER TABLE promo_redemptions DROP CONSTRAINT ck_promo_redemptions_status;
ALTER TABLE promo_redemptions ADD CONSTRAINT ck_promo_redemptions_status
    CHECK (status IN ('RESERVED', 'REDEEMED', 'RELEASED'));
