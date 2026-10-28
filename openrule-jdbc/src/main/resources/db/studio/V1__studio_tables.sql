-- Studio v2 is isolated from legacy or_flow and its enabled pointer.
CREATE TABLE IF NOT EXISTS or_studio_flow (
    flow_id VARCHAR(64) PRIMARY KEY,
    flow_name MEDIUMTEXT NOT NULL,
    next_version BIGINT NOT NULL,
    current_draft_version BIGINT NULL,
    latest_published_version BIGINT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS or_studio_version (
    flow_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    flow_name MEDIUMTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    revision BIGINT NOT NULL,
    document_json MEDIUMTEXT NOT NULL,
    definition_checksum VARCHAR(71) NULL,
    change_note MEDIUMTEXT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (flow_id, version),
    CONSTRAINT fk_studio_version_flow FOREIGN KEY (flow_id) REFERENCES or_studio_flow(flow_id),
    KEY idx_studio_flow_status (flow_id, status, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
