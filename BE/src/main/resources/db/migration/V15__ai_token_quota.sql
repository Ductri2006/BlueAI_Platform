-- Token accounting is independent of conversation transactions and login sessions.
CREATE TABLE ai_quota_accounts (
    user_id VARCHAR(36) NOT NULL PRIMARY KEY,
    five_start TIMESTAMP(6),
    week_start TIMESTAMP(6),
    five_used BIGINT NOT NULL DEFAULT 0,
    week_used BIGINT NOT NULL DEFAULT 0,
    five_reserved BIGINT NOT NULL DEFAULT 0,
    week_reserved BIGINT NOT NULL DEFAULT 0,
    five_uncertain BIGINT NOT NULL DEFAULT 0,
    week_uncertain BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE ai_token_operations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    user_id VARCHAR(36),
    operation_kind VARCHAR(32) NOT NULL,
    model VARCHAR(128) NOT NULL,
    status VARCHAR(24) NOT NULL,
    reserved_tokens BIGINT NOT NULL,
    reserved_input BIGINT NOT NULL,
    prompt_tokens BIGINT,
    completion_tokens BIGINT,
    total_tokens BIGINT,
    measurement VARCHAR(24),
    five_start TIMESTAMP(6),
    week_start TIMESTAMP(6),
    minute_start TIMESTAMP(6) NOT NULL,
    day_start TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    settled_at TIMESTAMP(6)
);
CREATE INDEX idx_ai_token_operations_pending ON ai_token_operations(user_id, status, expires_at);
CREATE INDEX idx_ai_token_operations_recorded ON ai_token_operations(created_at, model);

CREATE TABLE ai_project_windows (
    model VARCHAR(128) NOT NULL,
    window_kind VARCHAR(8) NOT NULL,
    window_start TIMESTAMP(6) NOT NULL,
    requests BIGINT NOT NULL DEFAULT 0,
    input_tokens BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (model, window_kind, window_start)
);
