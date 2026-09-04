# fleetdeck

물류 현장의 **AMR(자율이동로봇)과 분류 설비(소터·컨베이어)를 하나의 화면에서 관제**하는
RCS(Robot Control System) 학습 프로젝트입니다.

실제 로봇 없이 VDA5050 규격을 따르는 시뮬레이터가 MQTT로 텔레메트리를 발행하고,
Spring Boot 관제 서버가 이를 수집·저장·WebSocket 브로드캐스트하며,
React 대시보드가 2D 맵 위에 실시간으로 그립니다.

```
┌──────────────┐  MQTT (VDA5050 state)   ┌──────────────────┐  STOMP/WS   ┌──────────────┐
│  simulator   │ ──────────────────────▶ │  backend (RCS)   │ ──────────▶ │  frontend    │
│  AMR x N     │ ◀────────────────────── │  Spring Boot     │ ◀────────── │  React + TS  │
│  sorter x M  │  MQTT (VDA5050 order)   │  + TimescaleDB   │  REST       │  2D map      │
└──────────────┘                         └──────────────────┘             └──────────────┘
                    ▲
             ┌──────┴──────┐
             │  mosquitto  │
             └─────────────┘
```

## 구성

| 폴더 | 역할 | 스택 |
|------|------|------|
| `simulator/` | 가상 AMR·소터 텔레메트리 발행, order 수신 | Python 3.12, paho-mqtt, uv |
| `backend/` | MQTT 구독 → 저장 → WebSocket 중계, 미션 API, 디스패처 | Spring Boot 3.5, Spring Integration MQTT, JDBC |
| `frontend/` | 실시간 대시보드 (2D 맵, 로봇/설비 패널, 미션) | React 18, Vite, TypeScript, @stomp/stompjs |
| `infra/` | Mosquitto 설정, DB 초기 스키마 | eclipse-mosquitto 2, TimescaleDB (PG16) |
| `docs/` | 범위, 설계 메모, 주차별 진행 기록 | Markdown |

## 빠른 시작

```bash
# 1. 인프라 + 시뮬레이터 + 백엔드
cp .env.example .env
docker compose up -d --build

# 2. 프론트엔드 (로컬 dev 서버, /api 와 /ws 는 호스트 8081 의 backend 로 프록시)
cd frontend
npm install
npm run dev        # http://localhost:5173
```

개별 실행:

```bash
# 시뮬레이터만
cd simulator && uv sync && uv run fleetsim

# 백엔드만 (mosquitto, db 는 compose 로 먼저 띄울 것)
cd backend && ./gradlew bootRun
```

## MQTT 토픽

| 토픽 | 방향 | 내용 |
|------|------|------|
| `uagv/v2/{manufacturer}/{serial}/state` | robot → RCS | VDA5050 state (위치, 배터리, 주행 여부, 에러) |
| `uagv/v2/{manufacturer}/{serial}/order` | RCS → robot | VDA5050 order (노드·엣지 경로) |
| `uagv/v2/{manufacturer}/{serial}/instantActions` | RCS → robot | 즉시 명령 (정지, 재시작 등) |
| `fleetdeck/equipment/{id}/state` | equipment → RCS | 소터·컨베이어 상태 (가동/정지/알람, 처리량) |

## 로드맵

- [x] W1 레포 골격, Compose, VDA5050 state 발행/구독
- [ ] W2 TimescaleDB 적재, 로봇/설비 REST 조회
- [ ] W3 STOMP 브로드캐스트, React 2D 맵
- [ ] W4 설비 상태 패널, 알람 표시
- [ ] W5 미션 API, 디스패처, WMS 주문 모의
- [ ] W6 Three.js 3D 뷰, 히스토리 차트
- [ ] W7 로봇 20대 부하 테스트, 재접속·유실 처리
- [ ] W8 문서, 데모 GIF, 아키텍처 다이어그램

자세한 범위와 판단 근거는 [docs/000-scope.md](docs/000-scope.md) 참고.
