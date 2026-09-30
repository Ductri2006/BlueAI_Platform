CREATE TABLE gemini_usage_events (
    id CHAR(36) NOT NULL,
    model VARCHAR(120) NOT NULL,
    prompt_tokens BIGINT NOT NULL,
    completion_tokens BIGINT NOT NULL,
    total_tokens BIGINT NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_gemini_usage_events PRIMARY KEY (id)
);
CREATE INDEX idx_gemini_usage_recorded_at ON gemini_usage_events (recorded_at);
