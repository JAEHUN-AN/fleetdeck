"""소터를 OPC UA 장비로도 노출한다.

MQTT 소터(`SORTER-nn`)와 별개로 `UA-SORTER-nn` 을 두고 OPC UA 로만 내보낸다.
한 화면에 두 프로토콜의 설비가 같이 올라오고, 백엔드는 같은 테이블에 합류시킨다.

주소공간:

    Objects/
      Equipment/                          (FolderType)
        UA-SORTER-01/                     (BaseObjectType)
          EquipmentType, Status, AlarmCode, ThroughputPerMin, PositionX, PositionY

NodeId 는 `ns=<fleetdeck>;s=Equipment/UA-SORTER-01/Status` 처럼 문자열 식별자로 고정한다.
값의 시각은 변수로 따로 두지 않는다 — OPC UA DataValue 가 sourceTimestamp 를 이미 나른다.
"""

from __future__ import annotations

import asyncio
import logging
import threading
from typing import Any

from fleetsim import sensors
from fleetsim.sorter import SorterState

log = logging.getLogger("fleetsim.opcua")

NAMESPACE_URI = "urn:fleetdeck:simulator"
EQUIPMENT_FOLDER = "Equipment"
SERVER_NAME = "fleetdeck simulator"

# 백엔드 EquipmentStateMessage 의 필드와 1:1 로 맞춘다.
VARIABLES = (
    "EquipmentType",
    "Status",
    "AlarmCode",
    "ThroughputPerMin",
    "PositionX",
    "PositionY",
)

# 연속 센서 채널. 상태 변수와 같은 객체 밑에 나란히 둔다 - 수집기가 브라우징으로
# 찾으므로 채널이 늘어도 백엔드는 손대지 않는다.
SENSOR_VARIABLES = tuple(c.name for c in sensors.CHANNELS)
ALL_VARIABLES = VARIABLES + SENSOR_VARIABLES


def equipment_ids(count: int) -> list[str]:
    return [f"UA-SORTER-{i + 1:02d}" for i in range(count)]


def node_identifier(equipment_id: str, variable: str) -> str:
    return f"{EQUIPMENT_FOLDER}/{equipment_id}/{variable}"


def variable_values(state: SorterState,
                    readings: dict[str, float] | None = None) -> dict[str, Any]:
    """SorterState → 변수별 값. 타입은 변수마다 고정이어야 한다(아래 AlarmCode 주석)."""
    values: dict[str, Any] = {
        "EquipmentType": state.equipment_type,
        "Status": state.status.value,
        # None 을 실으면 Variant 타입이 Null 로 흔들려 클라이언트 쪽 매핑이 깨진다.
        "AlarmCode": state.alarm_code or "",
        "ThroughputPerMin": int(state.throughput_per_min),
        "PositionX": float(state.x),
        "PositionY": float(state.y),
    }
    for name in SENSOR_VARIABLES:
        values[name] = float((readings or {}).get(name, 0.0))
    return values


class SorterOpcUaServer:
    """asyncua 서버를 전용 스레드에서 돌린다.

    시뮬레이터 본체는 동기 틱 루프다. 여기서 이벤트 루프를 소유하고,
    `publish()` 는 루프 바깥(틱 스레드)에서 호출돼 안전하게 넘겨준다.
    """

    def __init__(self, endpoint: str, equipment: list[str]) -> None:
        self._endpoint = endpoint
        self._equipment = list(equipment)
        self._loop: asyncio.AbstractEventLoop | None = None
        self._thread: threading.Thread | None = None
        self._ready = threading.Event()
        self._stopping = threading.Event()
        self._nodes: dict[tuple[str, str], Any] = {}
        self._server: Any = None

    def start(self, timeout: float = 20.0) -> None:
        self._thread = threading.Thread(target=self._run_loop, name="opcua-server", daemon=True)
        self._thread.start()
        if not self._ready.wait(timeout):
            raise RuntimeError(f"OPC UA 서버가 {timeout}s 안에 뜨지 않았다: {self._endpoint}")
        log.info("OPC UA server on %s (%d equipment)", self._endpoint, len(self._equipment))

    def publish(self, states: list[SorterState],
                readings: dict[str, dict[str, float]] | None = None) -> None:
        """틱 스레드에서 호출한다. 이벤트 루프에 넘기고 기다리지 않는다."""
        if self._loop is None or self._stopping.is_set():
            return
        readings = readings or {}
        snapshot = {
            s.equipment_id: variable_values(s, readings.get(s.equipment_id)) for s in states
        }
        asyncio.run_coroutine_threadsafe(self._write_all(snapshot), self._loop)

    def stop(self, timeout: float = 5.0) -> None:
        self._stopping.set()
        if self._loop is not None:
            self._loop.call_soon_threadsafe(self._loop.stop)
        if self._thread is not None:
            self._thread.join(timeout)

    # --- 이벤트 루프 안쪽 ---

    def _run_loop(self) -> None:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        self._loop = loop
        try:
            loop.run_until_complete(self._start_server())
            loop.run_forever()
        except Exception:
            log.exception("OPC UA 서버 스레드가 죽었다")
        finally:
            loop.run_until_complete(self._stop_server())
            loop.close()

    async def _start_server(self) -> None:
        from asyncua import Server, ua  # 서버 경로에서만 필요하다

        server = Server()
        await server.init()
        server.set_endpoint(self._endpoint)
        server.set_server_name(SERVER_NAME)
        # 학습용 로컬 스택이라 익명·무암호화로 연다. 실제 현장은 Basic256Sha256 + 인증서.
        server.set_security_policy([ua.SecurityPolicyType.NoSecurity])

        idx = await server.register_namespace(NAMESPACE_URI)
        # BrowseName 도 NodeId 와 같은 네임스페이스에 둔다. 0 으로 새면 클라이언트가
        # 표준 네임스페이스에서 찾게 되어 탐색이 어긋난다.
        folder = await server.nodes.objects.add_folder(
            ua.NodeId(EQUIPMENT_FOLDER, idx), f"{idx}:{EQUIPMENT_FOLDER}"
        )
        for equipment_id in self._equipment:
            obj = await folder.add_object(ua.NodeId(equipment_id, idx), f"{idx}:{equipment_id}")
            for name in ALL_VARIABLES:
                node = await obj.add_variable(
                    ua.NodeId(node_identifier(equipment_id, name), idx),
                    f"{idx}:{name}",
                    _initial(name),
                )
                self._nodes[(equipment_id, name)] = node

        self._server = server
        await server.start()
        self._ready.set()

    async def _write_all(self, snapshot: dict[str, dict[str, Any]]) -> None:
        for equipment_id, values in snapshot.items():
            for name, value in values.items():
                node = self._nodes.get((equipment_id, name))
                if node is None:
                    continue
                try:
                    await node.write_value(value)
                except Exception as exc:  # noqa: BLE001 - 한 변수 실패가 틱 전체를 멈추면 안 된다
                    log.warning("write %s/%s 실패: %s", equipment_id, name, exc)

    async def _stop_server(self) -> None:
        if self._server is not None:
            try:
                await self._server.stop()
            except Exception:
                log.warning("OPC UA 서버 종료 중 오류", exc_info=True)


def _initial(variable: str) -> Any:
    """변수 타입을 첫 쓰기 전에 고정한다."""
    if variable == "ThroughputPerMin":
        return 0
    if variable in ("PositionX", "PositionY") or variable in SENSOR_VARIABLES:
        return 0.0
    return ""
