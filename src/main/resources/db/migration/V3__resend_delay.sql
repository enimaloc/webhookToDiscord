-- pause applied after sending a request requeued from the history, before the next one of the entry
ALTER TABLE requests ADD COLUMN resend_delay_ms integer NOT NULL DEFAULT 0;
