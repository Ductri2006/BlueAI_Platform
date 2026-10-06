-- Append-only: do not change checksums of migrations already deployed.
ALTER TABLE ai_task_ledger ADD COLUMN expires_at TIMESTAMP(6);

-- Older synchronous workers have no durable lease. Once the upgraded backend
-- starts, recovery will terminate these claims without calling the provider.
UPDATE ai_task_ledger SET expires_at = created_at WHERE expires_at IS NULL;
ALTER TABLE ai_task_ledger MODIFY COLUMN expires_at TIMESTAMP(6) NOT NULL;
CREATE INDEX idx_ai_task_status_expiry_client ON ai_task_ledger (status, expires_at, client_id);
