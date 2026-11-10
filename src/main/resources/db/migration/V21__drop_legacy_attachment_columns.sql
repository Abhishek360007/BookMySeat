-- V21: the single attachment columns of V13 go. V19 moved their content into email_outbox_attachment and the
-- release after it no longer wrote them; anything still left there (an email queued by the pre-V19 version
-- during that deploy) is moved now, after the attachments it already has.
INSERT INTO email_outbox_attachment (email_id, idx, filename, content_type, content)
SELECT e.id,
       COALESCE((SELECT MAX(a.idx) + 1 FROM email_outbox_attachment a WHERE a.email_id = e.id), 0),
       e.attachment_name, e.attachment_type, e.attachment_content
FROM email_outbox e
WHERE e.attachment_content IS NOT NULL;

ALTER TABLE email_outbox DROP COLUMN attachment_name;
ALTER TABLE email_outbox DROP COLUMN attachment_type;
ALTER TABLE email_outbox DROP COLUMN attachment_content;
