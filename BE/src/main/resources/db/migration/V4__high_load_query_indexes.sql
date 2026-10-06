-- Cover the high-volume owner, worker-claim, and retention query shapes.
-- Keep V1's owner/updated index: MySQL currently uses it to satisfy the
-- owner foreign-key constraint, so dropping it would make this migration fail.

CREATE INDEX idx_rag_conversation_owner_deleted_updated
    ON rag_conversations (owner_id, deleted_at, updated_at);
CREATE INDEX idx_rag_conversation_deleted_at
    ON rag_conversations (deleted_at);

CREATE INDEX idx_rag_document_owner_deleted_created
    ON rag_documents (owner_id, deleted_at, created_at);
CREATE INDEX idx_rag_document_status_deleted_next_created
    ON rag_documents (status, deleted_at, next_attempt_at, created_at);
CREATE INDEX idx_rag_document_status_deleted_processing
    ON rag_documents (status, deleted_at, processing_started_at);
CREATE INDEX idx_rag_document_deleted_at
    ON rag_documents (deleted_at);

CREATE INDEX idx_password_reset_user_expires
    ON password_reset_tokens (user_id, expires_at);
CREATE INDEX idx_auth_audit_created_at
    ON auth_audit_logs (created_at);
