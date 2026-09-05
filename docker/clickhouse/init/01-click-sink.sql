-- Kafka 토픽(events)을 그대로 읽는 엔진 테이블. SELECT 시점에만 컨슈밍되는 ClickHouse 특성상
-- 실제 저장은 아래 MATERIALIZED VIEW가 담당한다.
CREATE TABLE IF NOT EXISTS click_queue
(
    event_id UUID,
    short_code String,
    ts DateTime
)
ENGINE = Kafka
SETTINGS
    kafka_broker_list = 'kafka:9092',
    kafka_topic_list = 'events',
    kafka_group_name = 'archiver',
    kafka_format = 'JSONEachRow',
    kafka_num_consumers = 1;

-- 일자별 short_code 클릭 집계 저장 테이블(uniq 상태를 보존해 나중에 merge/finalize).
CREATE TABLE IF NOT EXISTS click_daily
(
    short_code String,
    day Date,
    clicks AggregateFunction(uniq, UUID)
)
ENGINE = AggregatingMergeTree()
ORDER BY (short_code, day);

-- click_queue → click_daily 로 흘려보내는 파이프.
CREATE MATERIALIZED VIEW IF NOT EXISTS click_mv
TO click_daily
AS
SELECT
    short_code,
    toDate(ts) AS day,
    uniqState(event_id) AS clicks
FROM click_queue
GROUP BY short_code, day;
