"""MQTT 연결, 틱 루프, order 수신·적용."""

from __future__ import annotations

import json
import logging
import queue
import random
import signal
import time

import paho.mqtt.client as mqtt

from fleetsim import opcua as opcua_model
from fleetsim import robot as robot_model
from fleetsim import sensors as sensor_model
from fleetsim import sorter as sorter_model
from fleetsim import vda5050
from fleetsim.config import SimConfig

log = logging.getLogger("fleetsim")

PARK_ROW_Y = 2.0
PARK_MARGIN_X = 3.0

# MQTT 콜백은 별도 스레드에서 돈다. 상태를 직접 만지지 않고 큐로 넘겨
# 틱 루프에서만 적용한다 (락 없이 단일 소유권 유지).
_order_queue: queue.Queue[tuple[str, str, tuple[robot_model.Waypoint, ...]]] = queue.Queue()


def build_client(cfg: SimConfig) -> mqtt.Client:
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="fleetsim")
    client.on_connect = _on_connect
    client.on_message = _on_message
    client.connect(cfg.mqtt_host, cfg.mqtt_port, keepalive=30)
    return client


def _on_connect(client: mqtt.Client, userdata, flags, reason_code, properties) -> None:
    if reason_code.is_failure:
        log.error("MQTT connect failed: %s", reason_code)
        return
    log.info("MQTT connected")
    client.subscribe(f"{vda5050.TOPIC_PREFIX}/+/+/order", qos=1)
    client.subscribe(f"{vda5050.TOPIC_PREFIX}/+/+/instantActions", qos=1)


def _on_message(client: mqtt.Client, userdata, msg: mqtt.MQTTMessage) -> None:
    try:
        body = json.loads(msg.payload)
    except json.JSONDecodeError:
        log.warning("non-JSON payload on %s", msg.topic)
        return

    if not msg.topic.endswith("/order"):
        log.info("instantActions on %s (미구현)", msg.topic)
        return

    serial = body.get("serialNumber") or vda5050.serial_from_topic(msg.topic)
    order_id, waypoints = vda5050.parse_order(body)
    if not serial or not waypoints:
        log.warning("order %s 무시: serial=%r waypoints=%d", order_id, serial, len(waypoints))
        return

    _order_queue.put((serial, order_id, waypoints))
    log.info("order %s -> %s (%d nodes)", order_id, serial, len(waypoints))


def run() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    cfg = SimConfig.from_env()
    rng = random.Random()
    client = build_client(cfg)
    client.loop_start()

    fleet = _spawn_fleet(cfg, rng)
    sorters = [
        sorter_model.spawn(f"SORTER-{i + 1:02d}", x=5.0 + i * 12.0, y=cfg.map_height - 3.0)
        for i in range(cfg.sorter_count)
    ]
    ua_sorters, ua_server = _start_opcua(cfg)
    banks = {
        s.equipment_id: sensor_model.spawn(s.equipment_id, cfg.fault_probability)
        for s in sorters + ua_sorters
    }

    running = {"value": True}
    signal.signal(signal.SIGINT, lambda *_: running.__setitem__("value", False))
    signal.signal(signal.SIGTERM, lambda *_: running.__setitem__("value", False))

    log.info("simulating %d robots, %d sorters, tick=%.1fs", len(fleet), len(sorters), cfg.tick_sec)
    while running["value"]:
        started = time.monotonic()
        _apply_pending_orders(fleet)
        for serial, state in fleet.items():
            fleet[serial] = robot_model.step(state, cfg.tick_sec)
        sorters = [sorter_model.step(s, rng) for s in sorters]
        ua_sorters = [sorter_model.step(s, rng) for s in ua_sorters]
        banks, readings, fault_events = _step_sensors(banks, rng)
        _publish_all(client, cfg, fleet, sorters, readings)
        _publish_faults(client, fault_events)
        if ua_server is not None:
            ua_server.publish(ua_sorters, readings)
        elapsed = time.monotonic() - started
        time.sleep(max(0.0, cfg.tick_sec - elapsed))

    if ua_server is not None:
        ua_server.stop()
    client.loop_stop()
    client.disconnect()
    log.info("stopped")


def _start_opcua(
    cfg: SimConfig,
) -> tuple[list[sorter_model.SorterState], opcua_model.SorterOpcUaServer | None]:
    """OPC UA 전용 소터를 세우고 서버를 띄운다.

    서버가 뜨지 않아도 시뮬레이터 본체(MQTT)는 계속 돌아야 한다 — 프로토콜 하나가
    죽었다고 나머지 관제가 멎으면 안 된다.
    """
    if not cfg.opcua_enabled or cfg.opcua_sorter_count <= 0:
        return [], None

    ids = opcua_model.equipment_ids(cfg.opcua_sorter_count)
    states = [
        sorter_model.spawn(eid, x=8.0 + i * 12.0, y=cfg.map_height - 8.0, equipment_type="TILT_TRAY_SORTER")
        for i, eid in enumerate(ids)
    ]
    server = opcua_model.SorterOpcUaServer(cfg.opcua_endpoint, ids)
    try:
        server.start()
    except Exception:
        log.exception("OPC UA 서버 기동 실패 — MQTT 경로만으로 계속한다")
        return [], None
    return states, server


def _spawn_fleet(cfg: SimConfig, rng: random.Random) -> dict[str, robot_model.RobotState]:
    """대기 열에 겹치지 않게 고르게 세운다. 이 자리가 각 로봇의 복귀 슬롯이 된다."""
    fleet: dict[str, robot_model.RobotState] = {}
    for i in range(cfg.robot_count):
        serial = f"AMR-{i + 1:03d}"
        fleet[serial] = robot_model.spawn(
            serial, x=_park_x(i, cfg.robot_count, cfg.map_width), y=PARK_ROW_Y, rng=rng
        )
    return fleet


def _park_x(index: int, count: int, map_width: float) -> float:
    usable = map_width - 2 * PARK_MARGIN_X
    if count <= 1:
        return PARK_MARGIN_X + usable / 2
    return PARK_MARGIN_X + usable * index / (count - 1)


def _apply_pending_orders(fleet: dict[str, robot_model.RobotState]) -> None:
    while True:
        try:
            serial, order_id, waypoints = _order_queue.get_nowait()
        except queue.Empty:
            return
        current = fleet.get(serial)
        if current is None:
            log.warning("order %s: 알 수 없는 로봇 %s", order_id, serial)
            continue
        fleet[serial] = robot_model.assign_order(current, order_id, waypoints)


def _step_sensors(
    banks: dict[str, sensor_model.SensorBank], rng: random.Random
) -> tuple[dict[str, sensor_model.SensorBank], dict[str, dict[str, float]],
           list[sensor_model.FaultEvent]]:
    """모든 설비의 센서를 한 틱 돌린다. 주입 사건은 정답 라벨로 모아 돌려준다."""
    next_banks: dict[str, sensor_model.SensorBank] = {}
    readings: dict[str, dict[str, float]] = {}
    events: list[sensor_model.FaultEvent] = []
    for equipment_id, bank in banks.items():
        next_banks[equipment_id], readings[equipment_id], new_events = sensor_model.step(bank, rng)
        events.extend(new_events)
    return next_banks, readings, events


def _publish_faults(client: mqtt.Client, events: list[sensor_model.FaultEvent]) -> None:
    """주입 사건은 QoS 1. 유실되면 그 구간을 채점에서 못 쓴다."""
    for event in events:
        client.publish(
            f"fleetdeck/equipment/{event.equipment_id}/fault",
            json.dumps({
                "equipmentId": event.equipment_id,
                "channel": event.channel,
                "kind": event.kind.value,
                "magnitude": round(event.magnitude, 4),
                "phase": event.phase,
                "timestamp": vda5050._now_iso(),
            }),
            qos=1,
        )
        log.info("fault %s %s %s/%s", event.phase, event.kind.value, event.equipment_id,
                 event.channel)


def _publish_all(
    client: mqtt.Client,
    cfg: SimConfig,
    fleet: dict[str, robot_model.RobotState],
    sorters: list[sorter_model.SorterState],
    readings: dict[str, dict[str, float]],
) -> None:
    for r in fleet.values():
        client.publish(
            vda5050.state_topic(cfg.manufacturer, r.serial),
            json.dumps(vda5050.to_state_message(r, cfg.manufacturer, cfg.map_id)),
            qos=0,
        )
    for s in sorters:
        client.publish(sorter_model.topic(s.equipment_id),
                       json.dumps(sorter_model.to_message(s, readings.get(s.equipment_id))), qos=0)


if __name__ == "__main__":
    run()