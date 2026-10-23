CREATE TABLE studio_bot_api_settings (
    bot_id CHAR(36) NOT NULL,
    requests_per_minute INTEGER NOT NULL,
    CONSTRAINT pk_studio_bot_api_settings PRIMARY KEY (bot_id),
    CONSTRAINT fk_studio_api_settings_bot FOREIGN KEY (bot_id) REFERENCES studio_bots(id) ON DELETE CASCADE
);

CREATE TABLE studio_bot_api_keys (
    id CHAR(36) NOT NULL,
    bot_id CHAR(36) NOT NULL,
    credential_hash CHAR(64) NOT NULL,
    credential_cipher VARCHAR(512) NOT NULL,
    requests_per_minute INTEGER NOT NULL,
    published_release_id CHAR(36) NOT NULL,
    rate_window_start TIMESTAMP(6),
    rate_count INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT pk_studio_bot_api_keys PRIMARY KEY (id),
    CONSTRAINT uk_studio_bot_api_key_bot UNIQUE (bot_id),
    CONSTRAINT uk_studio_bot_api_key_hash UNIQUE (credential_hash),
    CONSTRAINT fk_studio_bot_api_key_bot FOREIGN KEY (bot_id) REFERENCES studio_bots(id) ON DELETE CASCADE
);

CREATE TABLE studio_bot_api_sessions (
    context_hash CHAR(64) NOT NULL,
    api_key_id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    token_cipher VARCHAR(512) NOT NULL,
    CONSTRAINT pk_studio_bot_api_sessions PRIMARY KEY (context_hash),
    CONSTRAINT fk_studio_api_session_key FOREIGN KEY (api_key_id) REFERENCES studio_bot_api_keys(id) ON DELETE CASCADE,
    CONSTRAINT fk_studio_api_session_runtime FOREIGN KEY (session_id) REFERENCES studio_bot_sessions(id) ON DELETE CASCADE
);
