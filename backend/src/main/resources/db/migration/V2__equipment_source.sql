-- 설비 상태가 어느 프로토콜로 들어왔는지 남긴다.
--
-- MQTT 소터와 OPC UA 소터가 같은 하이퍼테이블에 합류한다. 수집 경로를 구분할 수 없으면
-- 프로토콜별 지연·유실·처리량을 따로 잴 수 없고, 한쪽 수집기가 멎어도 눈치채지 못한다.
ALTER TABLE equipment_state_log
    ADD COLUMN IF NOT EXISTS source TEXT NOT NULL DEFAULT 'MQTT';

-- 프로토콜별 집계가 주된 조회 축이다.
CREATE INDEX IF NOT EXISTS idx_equipment_state_source_ts
    ON equipment_state_log (source, ts DESC);
