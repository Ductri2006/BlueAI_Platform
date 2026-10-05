-- Additive and safe for old drafts/releases: no historical fact is reused by default.
ALTER TABLE studio_bot_dialog_nodes
    ADD COLUMN context_reuse_policy VARCHAR(30) NOT NULL DEFAULT 'NONE';
