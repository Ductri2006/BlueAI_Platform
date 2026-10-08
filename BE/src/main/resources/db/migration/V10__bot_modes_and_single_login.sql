ALTER TABLE app_users ADD COLUMN active_session_id VARCHAR(36);
ALTER TABLE studio_bots ADD COLUMN dialog_enabled BOOLEAN DEFAULT TRUE;
ALTER TABLE studio_bots ADD COLUMN widget_document_ids VARCHAR(4000);
