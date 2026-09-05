"""MQTT 연결, 틱 루프, order 수신·적용."""

from __future__ import annotations

import json
import logging
import queue
import random
import signal
import time

import paho.mqtt.client as mqtt

from fleetsim import robot as robot_model
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
        _publish_all(client, cfg, fleet, sorters)
        elapsed = time.monotonic() - started
        time.sleep(max(0.0, cfg.tick_sec - elapsed))

    client.loop_stop()
    client.disconnect()
    log.info("stopped")


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


def _publish_all(
    client: mqtt.Client,
    cfg: SimConfig,
    fleet: dict[str, robot_model.RobotState],
    sorters: list[sorter_model.SorterState],
) -> None:
    for r in fleet.values():
        client.publish(
            vda5050.state_topic(cfg.manufacturer, r.serial),
            json.dumps(vda5050.to_state_message(r, cfg.manufacturer, cfg.map_id)),
            qos=0,
        )
    for s in sorters:
        client.publish(sorter_model.topic(s.equipment_id), json.dumps(sorter_model.to_message(s)), qos=0)


if __name__ == "__main__":
    run()
