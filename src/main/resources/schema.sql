CREATE TABLE IF NOT EXISTS short_link
(
    short_code  VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    original_url VARCHAR(4096) NOT NULL,
    created_at  DATETIME(3) NOT NULL,
    expires_at  DATETIME(3) NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (short_code)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_bin;

ALTER TABLE short_link
    MODIFY COLUMN short_code VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL;

CREATE TABLE IF NOT EXISTS short_code_issuance
(
    id BIGINT NOT NULL AUTO_INCREMENT,
    PRIMARY KEY (id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS short_link_visit_log
(
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id BINARY(16) NOT NULL,
    short_code VARCHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    occurred_at DATETIME(3) NOT NULL,
    stat_date DATE NOT NULL,
    visitor_hash BINARY(32) NOT NULL,
    visitor_key_version SMALLINT UNSIGNED NOT NULL,
    peer_ip_network VARCHAR(49) NULL,
    user_agent VARCHAR(512) NULL,
    referer_host VARCHAR(253) NULL,
    UNIQUE KEY uq_visit_event (event_id),
    KEY idx_visit_aggregate (short_code, stat_date, visitor_key_version, visitor_hash),
    KEY idx_visit_page (short_code, occurred_at, id),
    KEY idx_visit_cleanup (stat_date, id)
) ENGINE = InnoDB DEFAULT CHARACTER SET = utf8mb4 COLLATE = utf8mb4_bin;
