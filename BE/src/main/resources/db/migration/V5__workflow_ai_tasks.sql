CREATE TABLE ai_integration_clients (
    id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    credential_hash CHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    rate_window_start TIMESTAMP(6),
    rate_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_ai_integration_clients PRIMARY KEY (id),
    CONSTRAINT uk_ai_integration_credential UNIQUE (credential_hash),
    CONSTRAINT fk_ai_integration_owner FOREIGN KEY (owner_id) REFERENCES app_users (id)
);
CREATE INDEX idx_ai_client_owner_active ON ai_integration_clients (owner_id, active);

CREATE TABLE ai_integration_capabilities (
    client_id CHAR(36) NOT NULL,
    capability VARCHAR(20) NOT NULL,
    CONSTRAINT pk_ai_integration_capabilities PRIMARY KEY (client_id, capability),
    CONSTRAINT fk_ai_capability_client FOREIGN KEY (client_id) REFERENCES ai_integration_clients (id)
);
CREATE TABLE ai_integration_document_grants (
    client_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    CONSTRAINT pk_ai_document_grant PRIMARY KEY (client_id, document_id),
    CONSTRAINT fk_ai_document_client FOREIGN KEY (client_id) REFERENCES ai_integration_clients (id),
    CONSTRAINT fk_ai_document_document FOREIGN KEY (document_id) REFERENCES rag_documents (id) ON DELETE CASCADE
);
CREATE INDEX idx_ai_document_grant_document ON ai_integration_document_grants (document_id);

CREATE TABLE ai_task_ledger (
    id CHAR(36) NOT NULL,
    client_id CHAR(36) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    capability VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    response_json ${text_type},
    error_code VARCHAR(50),
    created_at TIMESTAMP(6) NOT NULL,
    completed_at TIMESTAMP(6),
    CONSTRAINT pk_ai_task_ledger PRIMARY KEY (id),
    CONSTRAINT uk_ai_task_idempotency UNIQUE (client_id, idempotency_key),
    CONSTRAINT fk_ai_task_client FOREIGN KEY (client_id) REFERENCES ai_integration_clients (id)
);
CREATE INDEX idx_ai_task_client_created ON ai_task_ledger (client_id, created_at);
CREATE INDEX idx_ai_task_status_created ON ai_task_ledger (status, created_at);
CREATE TABLE ai_task_evidence (
    task_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    CONSTRAINT pk_ai_task_evidence PRIMARY KEY (task_id, document_id),
    CONSTRAINT fk_ai_task_evidence_task FOREIGN KEY (task_id) REFERENCES ai_task_ledger (id) ON DELETE CASCADE
    -- Keep the selected document id after hard purge so replay authorization
    -- can fail closed instead of mistaking missing evidence for no evidence.
);
CREATE INDEX idx_ai_task_evidence_document ON ai_task_evidence (document_id);
