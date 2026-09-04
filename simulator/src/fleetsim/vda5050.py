"""VDA5050 v2 메시지 빌더/파서. 스펙: https://github.com/VDA5050/VDA5050

전체 스키마 중 관제 대시보드와 주문 수행에 필요한 부분만 다룬다.
"""

from __future__ import annotations

from datetime import datetime, timezone

from fleetsim.robot import SPEED_M_PER_S, RobotState, Waypoint

VDA_VERSION = "2.0.0"
TOPIC_PREFIX = "uagv/v2"


def state_topic(manufacturer: str, serial: str) -> str:
    return f"{TOPIC_PREFIX}/{manufacturer}/{serial}/state"


def order_topic(manufacturer: str, serial: str) -> str:
    return f"{TOPIC_PREFIX}/{manufacturer}/{serial}/order"


def instant_actions_topic(manufacturer: str, serial: str) -> str:
    return f"{TOPIC_PREFIX}/{manufacturer}/{serial}/instantActions"


def serial_from_topic(topic: str) -> str:
    """uagv/v2/{manufacturer}/{serial}/order -> serial"""
    parts = topic.split("/")
    return parts[3] if len(parts) >= 5 else ""


def parse_order(body: dict) -> tuple[str, tuple[Waypoint, ...]]:
    """order 메시지에서 orderId 와 경유지 목록을 뽑는다.

    nodePosition 이 없는 노드는 주행할 수 없으므로 건너뛴다.
    released=False 인 노드는 아직 주행 허가가 안 난 것이라 제외한다(VDA5050 horizon).
    """
    order_id = str(body.get("orderId", ""))
    waypoints: list[Waypoint] = []

    for node in body.get("nodes") or []:
        if not node.get("released", True):
            continue
        pos = node.get("nodePosition")
        if not pos or "x" not in pos or "y" not in pos:
            continue
        waypoints.append(
            Waypoint(
                node_id=str(node.get("nodeId", "")),
                x=float(pos["x"]),
                y=float(pos["y"]),
                sequence_id=int(node.get("sequenceId", 0)),
            )
        )

    waypoints.sort(key=lambda w: w.sequence_id)
    return order_id, tuple(waypoints)


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
        # 남은 경유지. 비어 있고 driving=false 면 주문 완료로 본다.
        "nodeStates": [
            {
                "nodeId": w.node_id,
                "sequenceId": w.sequence_id,
                "released": True,
                "nodePosition": {"x": w.x, "y": w.y, "mapId": map_id},
            }
            for w in robot.waypoints
        ],
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
        "velocity": {"vx": SPEED_M_PER_S if robot.driving else 0.0, "vy": 0.0, "omega": 0.0},
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
