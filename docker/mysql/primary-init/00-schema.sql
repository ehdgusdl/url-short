-- 스키마는 여기서 만든다. 앱의 ddl-auto 는 validate 라 Primary 스키마를 바꾸지 않는다.
-- Replica 는 이 문장을 복제로 받으므로 replica-init 에 다시 두지 않는다.
CREATE TABLE IF NOT EXISTS url_mapping (
    id           BIGINT       NOT NULL,
    short_code   VARCHAR(16)  NOT NULL,
    original_url TEXT         NOT NULL,
    expires_at   DATETIME(6)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_url_mapping_short_code (short_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
