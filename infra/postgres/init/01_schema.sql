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
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_mission_status ON mission (status, created_at);
