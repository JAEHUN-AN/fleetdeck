-- FDC(이상 감지) 적재.
--
-- 테이블 성격이 셋 다 달라서 저장 방식도 다르게 간다.
--   센서값     초당 (설비 × 채널) 행. 하이퍼테이블.
--   경보       이상이 있을 때만. 일반 테이블 - 청크 관리 비용이 이득보다 크다.
--   주입 라벨  시뮬레이터 정답. 역시 드물다.

-- 연속 센서 측정값. 채널을 열로 펼치지 않고 좁고 긴 형태로 둔다.
-- 설비 종류마다 채널이 다르고, SPC/FDC 는 어차피 채널 단위로 계산하기 때문이다.
CREATE TABLE IF NOT EXISTS equipment_sensor_log (
    ts           TIMESTAMPTZ      NOT NULL,
    equipment_id TEXT             NOT NULL,
    channel      TEXT             NOT NULL,
    value        DOUBLE PRECISION NOT NULL,
    source       TEXT             NOT NULL   -- MQTT / OPC_UA
);
SELECT create_hypertable('equipment_sensor_log', 'ts', if_not_exists => TRUE);

-- 조회는 언제나 "이 설비의 이 채널을 최근 구간" 이다.
CREATE INDEX IF NOT EXISTS idx_sensor_equipment_channel_ts
    ON equipment_sensor_log (equipment_id, channel, ts DESC);

-- 탐지기가 올린 경보. detector 를 남겨야 어느 방식이 무엇을 잡았는지 되짚을 수 있다.
CREATE TABLE IF NOT EXISTS sensor_alarm_log (
    id           BIGSERIAL PRIMARY KEY,
    ts           TIMESTAMPTZ      NOT NULL,
    equipment_id TEXT             NOT NULL,
    channel      TEXT             NOT NULL,
    detector     TEXT             NOT NULL,   -- THRESHOLD / MOVING_SIGMA / EWMA
    value        DOUBLE PRECISION NOT NULL,
    score        DOUBLE PRECISION             -- 기준 대비 벗어난 정도(시그마 배수)
);
CREATE INDEX IF NOT EXISTS idx_alarm_equipment_ts ON sensor_alarm_log (equipment_id, ts DESC);

-- 시뮬레이터가 넣은 이상의 정답 라벨. 탐지율·오경보율·지연을 운영 데이터 위에서
-- 채점하려면 "언제부터 언제까지 이상이었는가" 가 DB 에 있어야 한다.
CREATE TABLE IF NOT EXISTS fault_injection_log (
    id           BIGSERIAL PRIMARY KEY,
    ts           TIMESTAMPTZ      NOT NULL,
    equipment_id TEXT             NOT NULL,
    channel      TEXT             NOT NULL,
    kind         TEXT             NOT NULL,   -- DRIFT / SPIKE / STEP / VARIANCE
    magnitude    DOUBLE PRECISION,
    phase        TEXT             NOT NULL    -- START / END
);
CREATE INDEX IF NOT EXISTS idx_fault_equipment_ts ON fault_injection_log (equipment_id, ts DESC);
