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
    synced_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_products_available_price ON products (available, selling_price);
CREATE INDEX idx_products_synced_at ON products (synced_at);
