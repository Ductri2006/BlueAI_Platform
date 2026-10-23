CREATE TABLE ai_integration_bot_grants (
    client_id CHAR(36) NOT NULL,
    bot_id CHAR(36) NOT NULL,
    CONSTRAINT pk_ai_integration_bot_grants PRIMARY KEY (client_id, bot_id),
    CONSTRAINT fk_ai_bot_grant_client FOREIGN KEY (client_id) REFERENCES ai_integration_clients(id),
    CONSTRAINT fk_ai_bot_grant_bot FOREIGN KEY (bot_id) REFERENCES studio_bots(id) ON DELETE CASCADE
);

CREATE TABLE ai_integration_bot_sessions (
    session_id CHAR(36) NOT NULL,
    client_id CHAR(36) NOT NULL,
    creation_key ${idempotency_key_type} NOT NULL,
    request_digest CHAR(64) NOT NULL,
    binding_json ${text_type} NOT NULL,
    token_cipher VARCHAR(512) NOT NULL,
    response_json ${text_type} NOT NULL,
    CONSTRAINT pk_ai_integration_bot_sessions PRIMARY KEY (session_id),
    CONSTRAINT uk_ai_bot_session_creation UNIQUE (client_id, creation_key),
    CONSTRAINT fk_ai_bot_session_client FOREIGN KEY (client_id) REFERENCES ai_integration_clients(id),
    CONSTRAINT fk_ai_bot_session_runtime FOREIGN KEY (session_id) REFERENCES studio_bot_sessions(id) ON DELETE CASCADE
);
