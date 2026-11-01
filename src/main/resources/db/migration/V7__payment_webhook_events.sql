-- V7: Razorpay webhook events already processed. Razorpay delivers at least once and retries,
-- so the same event id can arrive again; its row here turns the repeat into a no-op.
CREATE TABLE payment_webhook_events (
    event_id    VARCHAR(100)                NOT NULL,
    event_type  VARCHAR(50)                 NOT NULL,
    received_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_payment_webhook_events PRIMARY KEY (event_id)
);
