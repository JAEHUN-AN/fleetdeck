# fleetdeck

물류 현장의 **AMR(자율이동로봇)과 분류 설비(소터·컨베이어)를 한 화면에서 관제**하는
RCS(Robot Control System)입니다.

실제 로봇 없이, VDA5050 규격을 따르는 시뮬레이터가 MQTT로 텔레메트리를 발행하고
Spring Boot 관제 서버가 수집·저장·중계하며 React 대시보드가 실시간으로 그립니다.
**WMS 주문이 들어와 로봇이 주행하고 완료 보고가 돌아오기까지 제어 루프가 닫혀 있습니다.**

```
                              ┌───────────────┐
                              │   mosquitto   │
                              └───┬───────┬───┘
        MQTT state / equipment    │       │   MQTT order (QoS 1)
                    ┌─────────────┘       └─────────────┐
                    ▼                                   │
┌──────────────────────────────┐                        │
│  backend (RCS)               │                        │
│  Spring Integration MQTT     │                        │
│    → TimescaleDB 적재         │                        │
│    → 미션 상태 전이            │                        │
│    → 디스패처 ────────────────┼────────────────────────┘
│  Spring Boot 3.5 / Java 17   │
└──────┬────────────────┬──────┘                ┌──────────────┐
       │ STOMP/WS       │ REST                  │  simulator   │
       ▼                ▼                       │  AMR x N     │
┌──────────────────────────────┐                │  sorter x M  │
│  frontend  React 18 + TS     │                │  Python 3.12 │
│  SVG 2D 맵 / 미션·로봇·설비    │                └──────────────┘
└──────────────────────────────┘
```

## 미션 생명주기

이 프로젝트의 핵심입니다. 상위 시스템의 출고 주문이 로봇 주행으로 이어지고
완료가 되돌아오는 전 구간이 동작합니다.

```
WMS 주문 ──▶ PENDING ──▶ ASSIGNED ──▶ RUNNING ──▶ DONE
              ▲            │            │
              │            └─ VDA5050 order 발행 (nodePosition 포함)
              │                         │
              └── 회수 ◀── 60초 진전 없음 ─┘        재시도 3회 초과 ──▶ FAILED
```

| 전이 | 판단 근거 |
|------|-----------|
| PENDING → ASSIGNED | 유휴이고 예약되지 않은 로봇 중 배터리 최대 |
| ASSIGNED → RUNNING | 로봇 state 의 `nodeStates` 가 차거나 `driving=true` |
| RUNNING → DONE | `nodeStates` 가 비고 `driving=false` (VDA5050 order 완료 조건) |
| → FAILED | 로봇 에러 보고, 또는 재시도 상한 초과 |
| ASSIGNED/RUNNING → PENDING | 60초간 진전 없음. 단 배정 로봇이 해당 order 수행 중이면 제외 |

**중복 배정 차단** — `RobotRegistry` 는 텔레메트리가 올 때만 갱신되므로, 한 번의 디스패치
패스에서 같은 로봇이 연속으로 뽑힐 수 있습니다(실제로 발생했던 버그). order 발행 전에
로봇을 예약해 후보에서 제외하고, 로봇이 해당 `orderId` 를 보고하면 해제합니다.

## 구성

| 폴더 | 역할 | 스택 |
|------|------|------|
| `simulator/` | 가상 AMR(order 수행)·소터 상태 발행 | Python 3.12, paho-mqtt, uv |
| `backend/` | MQTT 수집 → 적재 → 중계, 미션 API·디스패처·생명주기 | Spring Boot 3.5, Spring Integration, JDBC, Flyway |
| `frontend/` | 실시간 대시보드 (2D 맵, 미션·로봇·설비 패널) | React 18, Vite, TypeScript, @stomp/stompjs |
| `infra/` | Mosquitto 설정 | eclipse-mosquitto 2 |
| `docs/` | 범위, 판단 근거, 측정 기록 | Markdown |

DB 스키마는 `backend/src/main/resources/db/migration/` 의 Flyway 마이그레이션이 관리합니다.

## 빠른 시작

```bash
cp .env.example .env
```

```bash
docker compose up -d --build
```

```bash
cd frontend && npm install && npm run dev
```

대시보드는 http://localhost:5173 입니다 (포트가 사용 중이면 Vite 가 5174 등으로 올립니다).
백엔드는 호스트 **8081** 에 노출됩니다 — 8080 을 다른 프로젝트가 쓰는 경우가 있어서입니다.
`BACKEND_PORT` 로 바꿀 수 있습니다.

기본 설정에서는 모의 WMS 생성기가 12초마다 출고 주문을 만들어 넣으므로,
띄우자마자 로봇이 움직이는 것을 볼 수 있습니다. 대시보드의 `+ 출고 주문` 버튼으로
직접 만들 수도 있습니다. 실제 상위 시스템을 붙일 때는 `FLEETDECK_WMS_ENABLED=false` 로 끕니다.

## API

| 메서드 | 경로 | 내용 |
|--------|------|------|
| GET | `/api/robots` | 로봇 최신 상태 전체 |
| GET | `/api/robots/{serial}` | 로봇 하나 |
| GET | `/api/equipment` | 설비 최신 상태 |
| GET | `/api/missions` | 최근 미션 100건 |
| POST | `/api/missions` | 미션 생성 (`{type, fromNode, toNode, sourceRef}`) |
| GET | `/api/map/nodes` | 창고 노드 좌표 |
| WS | `/ws` | STOMP. `/topic/robots`, `/topic/equipment`, `/topic/missions` 구독 |

## MQTT 토픽

| 토픽 | 방향 | QoS | 내용 |
|------|------|-----|------|
| `uagv/v2/{manufacturer}/{serial}/state` | robot → RCS | 0 | VDA5050 state (위치, 배터리, `nodeStates`, 에러) |
| `uagv/v2/{manufacturer}/{serial}/order` | RCS → robot | 1 | VDA5050 order (노드·엣지, `nodePosition` 포함) |
| `uagv/v2/{manufacturer}/{serial}/instantActions` | RCS → robot | 1 | 즉시 명령 (미구현) |
| `fleetdeck/equipment/{id}/state` | equipment → RCS | 0 | 소터·컨베이어 상태 (가동/정지/알람, 처리량) |

## 측정 결과

단일 노트북(8 vCPU / 15.4 GB, Docker Desktop WSL2)에서 compose 스택 전체를 띄운 채 측정했습니다.

| 구성 | 처리량 | 백엔드 CPU | DB CPU | 백엔드 메모리 |
|------|--------|-----------|--------|--------------|
| 로봇 5 / 소터 2 / 1 Hz | 초당 7건 | 1.7 % | 3.9 % | 349 MB |
| 로봇 30 / 소터 6 / 1 Hz | 초당 36건 | 2.6 % | 1.7 % | 355 MB |
| 로봇 50 / 소터 6 / 5 Hz | **초당 282건** | 12.5 % | 12.2 % | 355 MB |

기대치와 실측이 일치하고(252 vs 250) CPU 12 % 대에 머물러 **처리 경로에서는 병목을
찾지 못했습니다.** MQTT QoS 0 유실은 `headerId` 연속성으로 실측해
3분간 44,919건 중 17건(**0.038 %**)으로 확인했습니다.

자세한 방법과 재현 절차는 [docs/010-load-test.md](docs/010-load-test.md) 참고.

## 설정

| 키 | 환경변수 | 기본값 | 내용 |
|----|----------|--------|------|
| `fleetdeck.dispatch.interval` | `FLEETDECK_DISPATCH_INTERVAL` | 3s | PENDING 재배정 주기 |
| `fleetdeck.dispatch.stale-after` | `FLEETDECK_STALE_AFTER` | 60s | 회수 기준 (로봇 예약 TTL 겸용) |
| `fleetdeck.dispatch.max-retries` | `FLEETDECK_MAX_RETRIES` | 3 | 재시도 상한. 초과 시 FAILED |
| `fleetdeck.wms.enabled` | `FLEETDECK_WMS_ENABLED` | true | 모의 WMS 주문 생성기 |
| `fleetdeck.wms.interval` | `FLEETDECK_WMS_INTERVAL` | 12s | 주문 생성 주기 |
| `SIM_ROBOT_COUNT` / `SIM_SORTER_COUNT` | — | 8 / 3 | 시뮬레이터 규모 |

## 테스트

```bash
cd backend && ./gradlew test
```

```bash
cd simulator && uv run pytest
```

백엔드 33개, 시뮬레이터 17개. 상태 전이·로봇 선정·재시도 판정은 순수 함수로 분리해
단위 테스트로 덮었습니다. MQTT → DB → WebSocket 경로는 아직 수동 확인에 의존합니다.

## 알려진 한계

- **로봇 OFFLINE 상태가 없습니다.** 보고가 끊긴 로봇이 레지스트리에 영구히 남아
  디스패처가 죽은 로봇에 배정할 수 있습니다. 재시도 상한이 있어 미션이 갇히지는
  않지만 매번 상한까지 낭비합니다. 부하 테스트에서 실측으로 확인했습니다.
- **경로 계획이 없습니다.** `from → to` 직행이고 통로 경유점·교통 제어·충돌 회피는
  다루지 않습니다.
- **인증·권한이 없습니다.** 단일 사용자 로컬 도구를 전제로 합니다.
  Mosquitto 익명 접속 허용, WebSocket 오리진 전체 허용 상태입니다.
- **통합 테스트가 없습니다.** Testcontainers 로 MQTT·DB 를 띄우는 검증이 필요합니다.

## 로드맵

- [x] 레포 골격, Compose, VDA5050 state 발행/구독
- [x] TimescaleDB 적재, 로봇/설비 REST 조회
- [x] STOMP 브로드캐스트, React 2D 맵
- [x] 설비 상태 패널, 알람 표시
- [x] 미션 API, 디스패처, 모의 WMS 주문
- [x] 미션 생명주기 (주문 → 배정 → 주행 → 완료)
- [x] 중복 배정 차단, 정체 회수, 재시도 상한
- [x] Flyway 스키마 관리
- [x] 부하 테스트 (초당 282 메시지)
- [ ] 로봇 OFFLINE 상태
- [ ] Testcontainers 통합 테스트
- [ ] Three.js 3D 뷰, 히스토리 차트

범위와 판단 근거는 [docs/000-scope.md](docs/000-scope.md) 참고.
