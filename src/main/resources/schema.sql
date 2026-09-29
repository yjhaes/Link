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
