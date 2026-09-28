CREATE TABLE IF NOT EXISTS short_link
(
    short_code  CHAR(8) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL,
    original_url VARCHAR(4096) NOT NULL,
    created_at  DATETIME(3) NOT NULL,
    expires_at  DATETIME(3) NULL,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (short_code)
) ENGINE = InnoDB
  DEFAULT CHARACTER SET = utf8mb4
  COLLATE = utf8mb4_bin;
