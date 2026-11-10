-- V19: an outbox email can carry several attachments (the e-ticket QR code and a calendar event).
-- ON DELETE CASCADE: purging sent emails is a bulk DELETE that takes their attachments with it.
CREATE TABLE email_outbox_attachment (
    email_id     BIGINT       NOT NULL REFERENCES email_outbox (id) ON DELETE CASCADE,
    idx          INT          NOT NULL,
    filename     VARCHAR(100) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    content      BYTEA        NOT NULL,
    PRIMARY KEY (email_id, idx)
);

-- Move the one attachment of V13 into the new table. The old columns stay for now (emptied), because during a
-- zero-downtime deploy the previous version still runs for a moment and writes them; the new version reads them
-- as a fallback. A later migration can drop them.
INSERT INTO email_outbox_attachment (email_id, idx, filename, content_type, content)
SELECT id, 0, attachment_name, attachment_type, attachment_content
FROM email_outbox
WHERE attachment_content IS NOT NULL;

UPDATE email_outbox
SET attachment_name = NULL, attachment_type = NULL, attachment_content = NULL
WHERE attachment_content IS NOT NULL;
