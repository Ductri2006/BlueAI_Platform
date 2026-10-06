-- Keep V1-V7 unchanged. MySQL needs a NO PAD, case-sensitive key comparison;
-- tests supply H2's naturally case-sensitive VARCHAR through the same placeholder.
ALTER TABLE ai_task_ledger MODIFY COLUMN idempotency_key ${idempotency_key_type} NOT NULL;
CREATE INDEX idx_ai_task_expiry_page ON ai_task_ledger (status, expires_at, id, client_id);
CREATE INDEX idx_ai_task_client_status_expiry ON ai_task_ledger (client_id, status, expires_at);
