-- Auth reset/audit and document retry/tombstone fields added after V1.

ALTER TABLE app_users
    ADD COLUMN deleted_at TIMESTAMP(6);
ALTER TABLE app_users
    ADD COLUMN auth_version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE rag_conversations
    ADD COLUMN deleted_at TIMESTAMP(6);

ALTER TABLE rag_documents
    ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;
ALTER TABLE rag_documents
    ADD COLUMN next_attempt_at TIMESTAMP(6);
ALTER TABLE rag_documents
    ADD COLUMN processing_attempt_id VARCHAR(36);
ALTER TABLE rag_documents
    ADD COLUMN deleted_at TIMESTAMP(6);

CREATE TABLE password_reset_tokens (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    used_at TIMESTAMP(6),
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_password_reset_tokens PRIMARY KEY (id),
    CONSTRAINT uk_password_reset_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES app_users (id) ON DELETE CASCADE
);

CREATE TABLE auth_audit_logs (
    id CHAR(36) NOT NULL,
    action VARCHAR(80) NOT NULL,
    outcome VARCHAR(20) NOT NULL,
    actor_user_id VARCHAR(36),
    target_user_id VARCHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_auth_audit_logs PRIMARY KEY (id)
);
