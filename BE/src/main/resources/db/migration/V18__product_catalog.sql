CREATE TABLE products (
    sku VARCHAR(160) NOT NULL PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    region VARCHAR(500),
    cost_price DECIMAL(19,4),
    selling_price DECIMAL(19,4),
    currency_code VARCHAR(10),
    duration_days INT,
    volume_mb VARCHAR(100),
    payload ${text_type} NOT NULL,
    search_text ${text_type} NOT NULL,
    location_codes ${text_type} NOT NULL,
    available BOOLEAN NOT NULL,
    synced_at TIMESTAMP(6) NOT NULL,
    source_base_url VARCHAR(512),
    source_environment VARCHAR(20),
    snapshot_id VARCHAR(36)
);
CREATE INDEX idx_products_available_price ON products (available, selling_price);
CREATE INDEX idx_products_synced_at ON products (synced_at);
CREATE INDEX idx_products_snapshot ON products (snapshot_id, available);

-- Additive status column keeps evidence_json readable by the previous application image.
ALTER TABLE rag_conversation_messages ADD COLUMN answer_status VARCHAR(30) NOT NULL DEFAULT 'ANSWERED';
