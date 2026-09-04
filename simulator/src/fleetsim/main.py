"""MQTT 연결, 틱 루프, order/instantActions 수신."""

from __future__ import annotations

import json
import logging
import random
import signal
import time

import paho.mqtt.client as mqtt

from fleetsim import robot as robot_model
from fleetsim import sorter as sorter_model
from fleetsim import vda5050
from fleetsim.config import SimConfig

log = logging.getLogger("fleetsim")


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
    # W5: order 를 파싱해 로봇 target 을 바꾸는 로직이 들어갈 자리. 지금은 수신만 기록.
    try:
        body = json.loads(msg.payload)
    except json.JSONDecodeError:
        log.warning("non-JSON payload on %s", msg.topic)
        return
    log.info("received %s: orderId=%s", msg.topic, body.get("orderId"))


def run() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    cfg = SimConfig.from_env()
    rng = random.Random()
    client = build_client(cfg)
    client.loop_start()

    robots = [
        robot_model.spawn(f"AMR-{i + 1:03d}", cfg.map_width, cfg.map_height, rng)
        for i in range(cfg.robot_count)
    ]
    sorters = [
        sorter_model.spawn(f"SORTER-{i + 1:02d}", x=5.0 + i * 12.0, y=cfg.map_height - 3.0)
        for i in range(cfg.sorter_count)
    ]

    running = {"value": True}
    signal.signal(signal.SIGINT, lambda *_: running.__setitem__("value", False))
    signal.signal(signal.SIGTERM, lambda *_: running.__setitem__("value", False))

    log.info("simulating %d robots, %d sorters, tick=%.1fs", len(robots), len(sorters), cfg.tick_sec)
    while running["value"]:
        started = time.monotonic()
        robots = [robot_model.step(r, cfg.tick_sec, cfg.map_width, cfg.map_height, rng) for r in robots]
        sorters = [sorter_model.step(s, rng) for s in sorters]
        _publish_all(client, cfg, robots, sorters)
        elapsed = time.monotonic() - started
        time.sleep(max(0.0, cfg.tick_sec - elapsed))

    client.loop_stop()
    client.disconnect()
    log.info("stopped")


def _publish_all(
    client: mqtt.Client,
    cfg: SimConfig,
    robots: list[robot_model.RobotState],
    sorters: list[sorter_model.SorterState],
) -> None:
    for r in robots:
        client.publish(
            vda5050.state_topic(cfg.manufacturer, r.serial),
            json.dumps(vda5050.to_state_message(r, cfg.manufacturer, cfg.map_id)),
            qos=0,
        )
    for s in sorters:
        client.publish(sorter_model.topic(s.equipment_id), json.dumps(sorter_model.to_message(s)), qos=0)


if __name__ == "__main__":
    run()
