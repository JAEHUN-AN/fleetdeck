# 021 · OPC UA 설비 연동

측정일 2026-09-22. [020-manufacturing-scope.md](020-manufacturing-scope.md) Phase 1.

MQTT 소터(`SORTER-nn`) 옆에 OPC UA 소터(`UA-SORTER-nn`)를 세우고, 백엔드가 두 프로토콜을
같은 적재 경로로 합류시킨다. **프로토콜은 어댑터에서 끝난다** — 그 지점을 데이터로
확인할 수 있게 `equipment_state_log` 에 `source` 컬럼을 넣었다.

## 왜 OPC UA 인가

제조 공고에 반복해서 나오고, 벤더에 묶이지 않는다. 공고에 같이 등장하는 BizActor·
Factova·Solace 는 상용 제품이라 구할 수도 없고 배워도 다른 회사로 안 옮겨간다.
같은 구조(설비 → 수집기 → 응용)를 공개 표준으로 만들고 대응 관계를 설명하는 쪽이 낫다.

VDA5050 을 이미 구현해 봤기 때문에 두 번째 표준은 쌌다. 그리고 표준 기반 장비 연동
경험이 1개가 아니라 2개가 된다.

## 주소공간

```
Objects/
  Equipment/                        ns=2;s=Equipment
    UA-SORTER-01/                   ns=2;s=UA-SORTER-01
      EquipmentType, Status, AlarmCode, ThroughputPerMin, PositionX, PositionY
                                    ns=2;s=Equipment/UA-SORTER-01/Status ...
```

- 네임스페이스 URI 는 `urn:fleetdeck:simulator`. **인덱스(2)는 서버가 정하므로 URI 로 찾는다.**
  인덱스를 박으면 서버가 네임스페이스를 하나 더 등록하는 순간 조용히 어긋난다.
- NodeId 를 코드에 박지 않고 **Equipment 폴더를 브라우징해서 찾는다.** 설비가 몇 대이고
  태그 이름이 무엇인지는 서버가 안다. 박아두면 설비가 늘 때마다 배포해야 한다.
- **값의 시각은 변수로 두지 않았다.** OPC UA `DataValue` 가 `sourceTimestamp` 를 이미 나른다.
  장비가 값을 만든 시각이라 수집 시각보다 정확하고, 이게 없으면 지연 측정이 무의미해진다.

## 실제로 걸린 것 셋

문서에 적어 두는 이유는 전부 **컴파일도 되고 유닛 테스트도 통과한 뒤에** 드러났기 때문이다.

### 1. BrowseName 의 네임스페이스가 NodeId 와 달랐다

asyncua 에서 `add_folder(NodeId("Equipment", idx), "Equipment")` 처럼 BrowseName 을 맨
문자열로 주면 NodeId 는 ns=2 인데 BrowseName 은 ns=0 으로 붙는다. 서버는 정상으로 뜨고
폴더도 보이지만, 클라이언트가 `2:Equipment` 로 찾으면 `BadNoMatch` 가 난다.

```python
await folder.add_object(ua.NodeId(equipment_id, idx), f"{idx}:{equipment_id}")
```

BrowseName 도 NodeId 와 같은 네임스페이스에 둔다.

### 2. 정수가 Int64 로 온다

`ThroughputPerMin` 을 파이썬 `int` 로 쓰면 Variant 타입이 **Int64** 가 된다(VariantType 8).
Java 에서 `(Integer) value` 로 받으면 `ClassCastException` 이다. `Number.intValue()` 로 받는다.

측정으로 확인한 타입: String=12, Int64=8, Double=11, `sourceTimestamp` 존재.

### 3. 서버가 광고하는 endpoint URL ≠ 접속 주소

GetEndpoints 가 돌려주는 URL 은 **서버가 자기를 부르는 이름**이다. 컨테이너·NAT·다중 NIC
환경에서는 그 이름이 클라이언트 쪽에서 안 풀린다. 시뮬레이터가 `0.0.0.0` 으로 광고하면
Milo 가 그대로 `0.0.0.0` 에 붙으러 간다.

보안 설정만 서버가 말한 것을 따르고 **접속 주소는 우리가 설정한 것으로 되돌린다**
(`EndpointUtil.updateUrl`). compose 에서는 `SIM_OPCUA_HOST=simulator` 로 광고 이름도 맞춰 둔다.

## 알림 병합 — 이 구현의 핵심 결정

OPC UA 구독은 **변수 단위**로 알림이 온다. 위치만 바뀌면 위치 하나만 온다.
조각마다 설비 상태를 만들어 적재하면 설비 2대 × 변수 6개 기준 **초당 12행**이 쌓이고,
그 행들은 대부분 필드가 빈 채로 남는다.

그래서 변수별 콜백(`setDataValueListener`) 을 쓰지 않고 **subscription 단위 콜백**
(`SubscriptionListener.onDataReceived`) 으로 한 publish 를 통째로 받는다. 받은 값을
설비별 캐시에 병합하고, 상태값이 찬 스냅샷만 한 번 내보낸다.

| | 초당 적재 건수 |
|---|---|
| 변수별로 그대로 흘릴 때 (이론) | 12 |
| **설비 단위로 병합 (실측)** | **1.98** |

설비 2대가 1Hz 로 갱신되므로 이상적인 값은 2다. 6.1초간 12건 = 1.98/s.
`OpcUaCollectorIT.notificationsAreCoalescedIntoOneStatePerEquipment` 가 이 성질을 지킨다.

## 검증 방법

실제 asyncua 서버를 상대로 테스트한다. 인프로세스 가짜 서버를 쓰면 주소공간을 우리가
만들게 되므로 **위 세 가지가 전부 재현되지 않는다.**

```bash
docker compose up -d simulator
```

```bash
cd backend && ./gradlew integrationTest --tests '*OpcUaCollectorIT'
```

서버가 없으면 건너뛴다(`Assumptions`). 확인하는 것:

- 탐색이 첫 설비만이 아니라 **전부** 찾는가
- 상태값 없는 **부분 스냅샷이 새어 나가지 않는가**
- `sourceTimestamp` 가 실려 오는가
- 폴링 모드도 같은 모양을 내는가
- 알림이 설비 단위로 병합되는가

## 설정

| 키 | 환경변수 | 기본값 | 내용 |
|----|----------|--------|------|
| `fleetdeck.opcua.enabled` | `FLEETDECK_OPCUA_ENABLED` | true | OPC UA 수집 on/off |
| `fleetdeck.opcua.endpoint-url` | `FLEETDECK_OPCUA_URL` | `opc.tcp://localhost:4840/fleetdeck/server/` | 설비 서버 |
| `fleetdeck.opcua.namespace-uri` | `FLEETDECK_OPCUA_NAMESPACE` | `urn:fleetdeck:simulator` | 인덱스가 아니라 URI |
| `fleetdeck.opcua.mode` | `FLEETDECK_OPCUA_MODE` | SUBSCRIBE | SUBSCRIBE 또는 POLL |
| `fleetdeck.opcua.publishing-interval` | `FLEETDECK_OPCUA_PUBLISHING` | 1s | 구독 알림 주기 |
| `fleetdeck.opcua.poll-interval` | `FLEETDECK_OPCUA_POLL` | 1s | 폴링 주기 |
| `SIM_OPCUA_SORTER_COUNT` | — | 2 | OPC UA 전용 소터 수 |

## 아직 재지 않은 것

정직하게 남긴다. 아래는 compose 스택 전체가 떠야 잴 수 있고, 이 작업 시점에 Docker
데몬이 올라오지 않아 미측정이다.

- **구독 vs 폴링의 부하·지연 비교.** 둘 다 구현했고 같은 매핑·같은 캐시를 쓰므로
  모드만 바꿔 재면 된다. 볼 것은 설비 수를 늘렸을 때 갈라지는 지점이다
  (폴링은 설비 × 변수에 비례해 요청이 늘고, 구독은 변한 값만 온다)
- **`source` 컬럼 왕복.** V2 마이그레이션과 `insertEquipmentState(..., source)` 는
  유닛 테스트로 계약만 고정했고 실제 DB 에 넣어보지 못했다
- **연결 단절 후 재구독 복구 시간.** `onTransferFailed`/`onWatchdogTimerElapsed` 에서
  구독을 다시 만드는 경로는 코드만 있고 실측이 없다
- 설비 수를 늘렸을 때의 한계. 지금은 2대로만 확인했다

## 다음

Phase 2 (FDC 이상 감지). 지금 설비 payload 는 상태·처리량뿐이라 이산값이고 통계를 걸 수
없다. 연속 센서 채널(모터 전류·진동·온도)을 먼저 넣어야 한다 — OPC UA 변수로 추가하면
여기 만든 수집 경로를 그대로 탄다.
