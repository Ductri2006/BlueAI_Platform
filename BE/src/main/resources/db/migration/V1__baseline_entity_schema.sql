-- Baseline schema for the entity model present when Flyway was introduced.
-- Keep this migration MySQL/H2 compatible; later agent-owned changes belong in V2+.

CREATE TABLE app_users (
    id CHAR(36) NOT NULL,
    email VARCHAR(190) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_app_users PRIMARY KEY (id),
    CONSTRAINT uk_app_users_email UNIQUE (email)
);

CREATE TABLE refresh_tokens (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    token_hash CHAR(64) NOT NULL,
    family_id CHAR(36) NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    replaced_by_token_id CHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES app_users (id)
);

CREATE TABLE rag_conversations (
    id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    title VARCHAR(150) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    dialog_state_json ${long_text_type},
    context_mode VARCHAR(32) NOT NULL DEFAULT 'CURRENT_CONVERSATION',
    CONSTRAINT pk_rag_conversations PRIMARY KEY (id),
    CONSTRAINT fk_rag_conversation_owner FOREIGN KEY (owner_id) REFERENCES app_users (id)
);
CREATE INDEX idx_rag_conversation_owner_updated ON rag_conversations (owner_id, updated_at);

CREATE TABLE rag_conversation_messages (
    id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content ${text_type} NOT NULL,
    evidence_json ${long_text_type},
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_rag_conversation_messages PRIMARY KEY (id),
    CONSTRAINT fk_rag_message_conversation FOREIGN KEY (conversation_id) REFERENCES rag_conversations (id)
);
CREATE INDEX idx_rag_message_conversation_created ON rag_conversation_messages (conversation_id, created_at);

CREATE TABLE rag_conversation_summaries (
    id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    content ${text_type} NOT NULL,
    covered_message_count INT NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT pk_rag_conversation_summaries PRIMARY KEY (id),
    CONSTRAINT uk_rag_summary_conversation UNIQUE (conversation_id),
    CONSTRAINT fk_rag_summary_conversation FOREIGN KEY (conversation_id) REFERENCES rag_conversations (id)
);

CREATE TABLE rag_conversation_links (
    id CHAR(36) NOT NULL,
    target_conversation_id CHAR(36) NOT NULL,
    source_conversation_id CHAR(36) NOT NULL,
    source_snapshot_message_id VARCHAR(36),
    source_snapshot_content ${text_type} NOT NULL,
    source_snapshot_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_rag_conversation_links PRIMARY KEY (id),
    CONSTRAINT uk_rag_conversation_link_target_source UNIQUE (target_conversation_id, source_conversation_id),
    CONSTRAINT fk_rag_conversation_link_target FOREIGN KEY (target_conversation_id) REFERENCES rag_conversations (id),
    CONSTRAINT fk_rag_conversation_link_source FOREIGN KEY (source_conversation_id) REFERENCES rag_conversations (id)
);
CREATE INDEX idx_rag_conversation_link_target ON rag_conversation_links (target_conversation_id);
CREATE INDEX idx_rag_conversation_link_source ON rag_conversation_links (source_conversation_id);

CREATE TABLE rag_documents (
    id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    storage_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL,
    requested_chunk_size INT NOT NULL,
    requested_chunk_overlap INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    produced_chunk_count INT,
    processing_started_at TIMESTAMP(6),
    processed_at TIMESTAMP(6),
    failure_reason VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_rag_documents PRIMARY KEY (id),
    CONSTRAINT uk_rag_documents_storage_key UNIQUE (storage_key),
    CONSTRAINT fk_rag_document_owner FOREIGN KEY (owner_id) REFERENCES app_users (id)
);

CREATE TABLE rag_user_memories (
    id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    memory_key VARCHAR(100) NOT NULL,
    content ${text_type} NOT NULL,
    scope VARCHAR(20) NOT NULL DEFAULT 'USER',
    conversation_id CHAR(36),
    source_message_id CHAR(36),
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    expires_at TIMESTAMP(6),
    replaced_by_memory_key VARCHAR(100),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_rag_user_memories PRIMARY KEY (id),
    CONSTRAINT uk_rag_memory_owner_key UNIQUE (owner_id, memory_key),
    CONSTRAINT fk_rag_memory_owner FOREIGN KEY (owner_id) REFERENCES app_users (id),
    CONSTRAINT fk_rag_memory_conversation FOREIGN KEY (conversation_id) REFERENCES rag_conversations (id),
    CONSTRAINT fk_rag_memory_source_message FOREIGN KEY (source_message_id) REFERENCES rag_conversation_messages (id)
);
CREATE INDEX idx_rag_memory_owner_status_updated ON rag_user_memories (owner_id, status, updated_at);
CREATE INDEX idx_rag_memory_conversation ON rag_user_memories (conversation_id);

CREATE TABLE studio_bots (
    id CHAR(36) NOT NULL,
    owner_id CHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    locale VARCHAR(20) NOT NULL,
    timezone VARCHAR(60) NOT NULL,
    blue_ai_core_enabled BOOLEAN,
    custom_instructions VARCHAR(2000),
    identity_role VARCHAR(120),
    tone_preset VARCHAR(40),
    answer_length VARCHAR(24),
    response_structure VARCHAR(40),
    proactivity VARCHAR(24),
    citation_policy VARCHAR(40),
    no_source_policy VARCHAR(80),
    preferred_terms VARCHAR(1000),
    forbidden_terms VARCHAR(1000),
    fallback_message VARCHAR(1000),
    escalation_message VARCHAR(1000),
    response_profile VARCHAR(120),
    active BOOLEAN,
    active_release_id VARCHAR(36),
    status VARCHAR(20) NOT NULL,
    public_key VARCHAR(36) NOT NULL,
    draft_revision BIGINT NOT NULL,
    published_release_id VARCHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT pk_studio_bots PRIMARY KEY (id),
    CONSTRAINT uk_studio_bot_owner_name UNIQUE (owner_id, name),
    CONSTRAINT uk_studio_bot_public_key UNIQUE (public_key),
    CONSTRAINT fk_studio_bot_owner FOREIGN KEY (owner_id) REFERENCES app_users (id)
);
CREATE INDEX idx_studio_bot_owner_updated ON studio_bots (owner_id, updated_at);

CREATE TABLE studio_bot_releases (
    id CHAR(36) NOT NULL,
    bot_id CHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    configuration_snapshot ${text_type} NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_studio_bot_releases PRIMARY KEY (id),
    CONSTRAINT uk_studio_release_revision UNIQUE (bot_id, revision),
    CONSTRAINT fk_studio_release_bot FOREIGN KEY (bot_id) REFERENCES studio_bots (id)
);
CREATE INDEX idx_studio_release_bot_created ON studio_bot_releases (bot_id, created_at);

CREATE TABLE studio_bot_dialog_nodes (
    id CHAR(36) NOT NULL,
    bot_id CHAR(36) NOT NULL,
    node_name VARCHAR(120) NOT NULL,
    parent_node VARCHAR(120),
    node_type VARCHAR(20) NOT NULL,
    match_intent VARCHAR(100),
    match_entity_name VARCHAR(100),
    match_entity_value VARCHAR(150),
    required_entity_name VARCHAR(100),
    response_text ${text_type} NOT NULL,
    missing_entity_prompt VARCHAR(1000),
    next_step VARCHAR(30),
    jump_to_node VARCHAR(120),
    irrelevant_response_text VARCHAR(1000),
    priority INT NOT NULL,
    CONSTRAINT pk_studio_bot_dialog_nodes PRIMARY KEY (id),
    CONSTRAINT fk_studio_dialog_bot FOREIGN KEY (bot_id) REFERENCES studio_bots (id)
);
CREATE INDEX idx_studio_dialog_bot_priority ON studio_bot_dialog_nodes (bot_id, priority);

CREATE TABLE studio_bot_entities (
    id CHAR(36) NOT NULL,
    bot_id CHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    CONSTRAINT pk_studio_bot_entities PRIMARY KEY (id),
    CONSTRAINT uk_studio_entity_bot_name UNIQUE (bot_id, name),
    CONSTRAINT fk_studio_entity_bot FOREIGN KEY (bot_id) REFERENCES studio_bots (id)
);
CREATE INDEX idx_studio_entity_bot ON studio_bot_entities (bot_id);

CREATE TABLE studio_bot_intents (
    id CHAR(36) NOT NULL,
    bot_id CHAR(36) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    CONSTRAINT pk_studio_bot_intents PRIMARY KEY (id),
    CONSTRAINT uk_studio_intent_bot_name UNIQUE (bot_id, name),
    CONSTRAINT fk_studio_intent_bot FOREIGN KEY (bot_id) REFERENCES studio_bots (id)
);
CREATE INDEX idx_studio_intent_bot ON studio_bot_intents (bot_id);

CREATE TABLE studio_bot_entity_values (
    id CHAR(36) NOT NULL,
    entity_id CHAR(36) NOT NULL,
    canonical_value VARCHAR(150) NOT NULL,
    CONSTRAINT pk_studio_bot_entity_values PRIMARY KEY (id),
    CONSTRAINT uk_studio_entity_value UNIQUE (entity_id, canonical_value),
    CONSTRAINT fk_studio_entity_value_entity FOREIGN KEY (entity_id) REFERENCES studio_bot_entities (id)
);
CREATE INDEX idx_studio_entity_value_entity ON studio_bot_entity_values (entity_id);

CREATE TABLE studio_bot_entity_synonyms (
    entity_value_id CHAR(36) NOT NULL,
    synonym VARCHAR(150) NOT NULL,
    CONSTRAINT pk_studio_bot_entity_synonyms PRIMARY KEY (entity_value_id, synonym),
    CONSTRAINT fk_studio_synonym_value FOREIGN KEY (entity_value_id) REFERENCES studio_bot_entity_values (id)
);

CREATE TABLE studio_bot_intent_examples (
    intent_id CHAR(36) NOT NULL,
    phrase VARCHAR(500) NOT NULL,
    CONSTRAINT pk_studio_bot_intent_examples PRIMARY KEY (intent_id, phrase),
    CONSTRAINT fk_studio_intent_example_intent FOREIGN KEY (intent_id) REFERENCES studio_bot_intents (id)
);

CREATE TABLE studio_bot_sessions (
    id CHAR(36) NOT NULL,
    bot_id CHAR(36) NOT NULL,
    release_id VARCHAR(36) NOT NULL,
    access_token_hash VARCHAR(64) NOT NULL,
    state_json ${text_type} NOT NULL,
    expires_at TIMESTAMP(6) NOT NULL,
    turn_count INT NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_studio_bot_sessions PRIMARY KEY (id),
    CONSTRAINT fk_studio_session_bot FOREIGN KEY (bot_id) REFERENCES studio_bots (id)
);
CREATE INDEX idx_studio_session_bot_updated ON studio_bot_sessions (bot_id, updated_at);

CREATE TABLE studio_bot_turns (
    id CHAR(36) NOT NULL,
    session_id CHAR(36) NOT NULL,
    request_id VARCHAR(80) NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    response_json ${text_type} NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT pk_studio_bot_turns PRIMARY KEY (id),
    CONSTRAINT uk_studio_turn_request UNIQUE (session_id, request_id),
    CONSTRAINT fk_studio_turn_session FOREIGN KEY (session_id) REFERENCES studio_bot_sessions (id)
);
