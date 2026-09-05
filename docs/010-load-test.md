# 010 · 부하 테스트

측정일 2026-09-05. 단일 노트북(Windows 11, Docker Desktop WSL2, 8 vCPU / 15.4 GB)에서
compose 스택 전체를 띄운 채 시뮬레이터 규모만 바꿔가며 측정했다.

## 결과

| 구성 | 로봇 텔레메트리 | 설비 상태 | 합계 | 백엔드 CPU | 백엔드 메모리 | DB CPU |
|------|----------------|-----------|------|-----------|--------------|--------|
| 로봇 5 / 소터 2 / 1 Hz | 5/s | 2/s | **7/s** | 1.7 % | 349 MB | 3.9 % |
| 로봇 30 / 소터 6 / 1 Hz | 30/s | 6/s | **36/s** | 2.6 % | 355 MB | 1.7 % |
| 로봇 50 / 소터 6 / 5 Hz | 252/s | 30/s | **282/s** | 12.5 % | 355 MB | 12.2 % |

각 구간은 안정화 30초 후 120초간 DB 적재 행수 증가분으로 계산했다.
기대치(로봇 수 × 1/틱)와 실측이 일치한다 — 252 vs 250, 30 vs 30.

## 메시지 유실

MQTT QoS 0 으로 발행하므로 유실이 가능하다. `payload->>'headerId'` 의 연속성으로 실측했다.

```sql
WITH s AS (
  SELECT serial_number, (payload->>'headerId')::bigint AS h,
         lag((payload->>'headerId')::bigint) OVER (
           PARTITION BY serial_number ORDER BY (payload->>'headerId')::bigint) AS prev
  FROM robot_state_log WHERE ts > now() - interval '3 minutes'
)
SELECT count(*) AS gap_events, coalesce(sum(h - prev - 1), 0) AS missing
FROM s WHERE prev IS NOT NULL AND h - prev > 1;
```

초당 282 메시지 구간에서 **3분간 44,919건 중 17건 누락 (0.038 %)**.
위치·배터리 같은 주기 상태값은 다음 틱에 갱신되므로 관제 화면에는 영향이 없다.
누락되면 안 되는 것(주문 수령, 완료 보고)은 QoS 1 로 올리는 것이 맞다 —
order 발행은 이미 QoS 1 이고, state 는 QoS 0 이다.

## 병목은 어디였나

- **처리 경로는 병목이 아니다.** 초당 282 메시지에서도 백엔드 12.5 %, DB 12.2 % 로
  단일 노트북에서 여유가 남았다. 한계를 찾으려면 더 밀어야 한다.
- **미션 처리량(분당 6건)은 시스템 한계가 아니다.** 모의 WMS 생성기가
  12초 주기 + 진행 중 8건 상한으로 스스로 조절하고 있다. 로봇을 5대에서 30대로
  늘려도 완료율이 그대로였던 이유다. 실제 처리 능력을 재려면
  `fleetdeck.wms.interval` 과 `max-open-missions` 를 먼저 풀어야 한다.

## 부하 테스트가 찾아낸 결함

로봇 수를 50 → 8 로 줄인 뒤 `GET /api/robots` 가 계속 50대를 반환했다.
`RobotRegistry` 는 한 번 본 로봇을 영구히 들고 있어, 보고가 끊긴 로봇이 사라지지 않는다.
30초 넘게 보고 없는 42대가 유령으로 남아 있었다.

당장은 백엔드 재시작으로 정리했으나, 실제 현장에서는 통신이 끊긴 로봇이
계속 "유휴"로 보여 **디스패처가 죽은 로봇에 미션을 배정하는** 문제가 된다.
로봇에 `OFFLINE` 상태를 두고 일정 시간 보고가 없으면 전환하는 것이 정석이다.
(재시도 상한과 회수가 있어 미션이 영구히 갇히지는 않지만, 매번 상한까지 낭비한다)

## 재현 방법

`.env` 를 다음과 같이 바꾸고 시뮬레이터만 재기동한다.

```
SIM_ROBOT_COUNT=50
SIM_SORTER_COUNT=6
SIM_TICK_SEC=0.2
```

```bash
docker compose up -d simulator
```

```bash
docker stats --no-stream fleetdeck-backend fleetdeck-db fleetdeck-simulator
```
