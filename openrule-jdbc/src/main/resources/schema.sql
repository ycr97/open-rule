CREATE TABLE IF NOT EXISTS or_flow (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    flow_id         VARCHAR(100) NOT NULL,
    flow_name       VARCHAR(200) NOT NULL,
    version         INT          NOT NULL DEFAULT 1,
    enabled         TINYINT      NOT NULL DEFAULT 0,
    aggregate_policy VARCHAR(32) NOT NULL DEFAULT 'PRIORITY',
    definition_json MEDIUMTEXT   NOT NULL,
    checksum        VARCHAR(64)  NOT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_flow_version (flow_id, version),
    KEY idx_flow_enabled (flow_id, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS or_execute_log (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_id      VARCHAR(128) NOT NULL,
    flow_id         VARCHAR(100) NOT NULL,
    flow_version    INT          NOT NULL,
    biz_id          VARCHAR(200) NOT NULL,
    decision        VARCHAR(20)  NOT NULL,
    reason          VARCHAR(512),
    total_score     INT          NOT NULL DEFAULT 0,
    cost_millis     INT          NOT NULL,
    hit_nodes       JSON,
    node_results    MEDIUMTEXT,
    facts_snapshot  MEDIUMTEXT,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_request (request_id),
    KEY idx_biz (biz_id),
    KEY idx_flow_time (flow_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
