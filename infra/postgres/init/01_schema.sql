-- fleetdeck 초기 스키마. TimescaleDB 이미지에서 실행됨.
CREATE EXTENSION IF NOT EXISTS timescaledb;

-- 로봇 텔레메트리 (VDA5050 state 요약 + 원문)
CREATE TABLE IF NOT EXISTS robot_state_log (
    ts              TIMESTAMPTZ      NOT NULL,
    serial_number   TEXT             NOT NULL,
    manufacturer    TEXT             NOT NULL,
    x               DOUBLE PRECISION,
    y               DOUBLE PRECISION,
    theta           DOUBLE PRECISION,
    battery_charge  DOUBLE PRECISION,
    driving         BOOLEAN,
    operating_mode  TEXT,
    order_id        TEXT,
    error_count     INTEGER          NOT NULL DEFAULT 0,
    payload         JSONB            NOT NULL
);
SELECT create_hypertable('robot_state_log', 'ts', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_robot_state_serial_ts ON robot_state_log (serial_number, ts DESC);

-- 설비(소터·컨베이어) 상태
CREATE TABLE IF NOT EXISTS equipment_state_log (
    ts                  TIMESTAMPTZ NOT NULL,
    equipment_id        TEXT        NOT NULL,
    equipment_type      TEXT        NOT NULL,
    status              TEXT        NOT NULL,   -- RUNNING / STOPPED / ALARM
    throughput_per_min  INTEGER,
    alarm_code          TEXT,
    payload             JSONB       NOT NULL
);
SELECT create_hypertable('equipment_state_log', 'ts', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_equipment_state_id_ts ON equipment_state_log (equipment_id, ts DESC);

-- 미션 (WMS 주문 → RCS 미션 → 로봇 배정)
CREATE TABLE IF NOT EXISTS mission (
    id              BIGSERIAL PRIMARY KEY,
    mission_type    TEXT        NOT NULL,   -- TRANSPORT / CHARGE / PARK
    from_node       TEXT        NOT NULL,
    to_node         TEXT        NOT NULL,
    status          TEXT        NOT NULL DEFAULT 'PENDING',  -- PENDING / ASSIGNED / RUNNING / DONE / FAILED
    assigned_robot  TEXT,
    source_ref      TEXT,                   -- WMS 주문번호 등 상위 시스템 참조
    retry_count     INTEGER     NOT NULL DEFAULT 0,  -- 회수되어 재배정된 횟수
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mission_status ON mission (status, created_at);

-- 마이그레이션 도구가 없어 이 파일을 기존 DB 에 다시 돌릴 수 있도록 멱등하게 둔다.
-- (init 디렉터리는 볼륨이 비어 있을 때만 자동 실행되므로 기존 DB 에는 수동 적용이 필요하다)
ALTER TABLE mission ADD COLUMN IF NOT EXISTS retry_count INTEGER NOT NULL DEFAULT 0;
