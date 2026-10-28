-- Current configuration only. No changes to M1 definitions or published checksums.
CREATE TABLE IF NOT EXISTS or_studio_fact_provider (
    provider_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    revision BIGINT NOT NULL,
    document_json MEDIUMTEXT NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CONSTRAINT chk_fact_provider_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS or_studio_fact_catalog (
    fact_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    path VARCHAR(512) COLLATE utf8mb4_0900_bin NOT NULL,
    provider_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    revision BIGINT NOT NULL,
    document_json MEDIUMTEXT NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_studio_fact_path (path),
    KEY idx_studio_fact_provider (provider_id),
    CONSTRAINT fk_studio_fact_provider FOREIGN KEY (provider_id) REFERENCES or_studio_fact_provider(provider_id),
    CONSTRAINT chk_fact_catalog_revision CHECK (revision > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
