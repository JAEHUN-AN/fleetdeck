"""VDA5050 v2 state 메시지 빌더. 스펙: https://github.com/VDA5050/VDA5050

전체 스키마 중 관제 대시보드에 필요한 필드만 채운다. 나머지는 W5 이후 order 처리 때 확장.
"""

from __future__ import annotations

from datetime import datetime, timezone

from fleetsim.robot import RobotState

VDA_VERSION = "2.0.0"
TOPIC_PREFIX = "uagv/v2"


def state_topic(manufacturer: str, serial: str) -> str:
    return f"{TOPIC_PREFIX}/{manufacturer}/{serial}/state"


def order_topic(manufacturer: str, serial: str) -> str:
    return f"{TOPIC_PREFIX}/{manufacturer}/{serial}/order"


def instant_actions_topic(manufacturer: str, serial: str) -> str:
    return f"{TOPIC_PREFIX}/{manufacturer}/{serial}/instantActions"


def to_state_message(robot: RobotState, manufacturer: str, map_id: str) -> dict:
    return {
        "headerId": robot.header_id,
        "timestamp": _now_iso(),
        "version": VDA_VERSION,
        "manufacturer": manufacturer,
        "serialNumber": robot.serial,
        "orderId": robot.order_id,
        "orderUpdateId": 0,
        "lastNodeId": robot.last_node_id,
        "lastNodeSequenceId": 0,
        "nodeStates": [],
        "edgeStates": [],
        "driving": robot.driving,
        "paused": False,
        "agvPosition": {
            "x": round(robot.x, 3),
            "y": round(robot.y, 3),
            "theta": round(robot.theta, 4),
            "mapId": map_id,
            "positionInitialized": True,
        },
        "velocity": {"vx": 1.2 if robot.driving else 0.0, "vy": 0.0, "omega": 0.0},
        "loads": [],
        "actionStates": [],
        "batteryState": {
            "batteryCharge": round(robot.battery, 1),
            "charging": robot.charging,
        },
        "operatingMode": "AUTOMATIC",
        "errors": [],
        "safetyState": {"eStop": "NONE", "fieldViolation": False},
    }


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
