from fleetsim import vda5050
from fleetsim.robot import RobotState

REQUIRED_TOP_LEVEL = {
    "headerId", "timestamp", "version", "manufacturer", "serialNumber", "orderId",
    "orderUpdateId", "lastNodeId", "lastNodeSequenceId", "nodeStates", "edgeStates",
    "driving", "actionStates", "batteryState", "operatingMode", "errors", "safetyState",
}


def _robot() -> RobotState:
    return RobotState(
        serial="AMR-001", x=1.23456, y=2.5, theta=0.7854, battery=77.77,
        target_x=5.0, target_y=5.0, driving=True, charging=False, header_id=42,
        order_id="ORD-1", last_node_id="N07",
    )


def test_state_message_has_vda5050_required_fields():
    msg = vda5050.to_state_message(_robot(), manufacturer="fleetdeck", map_id="warehouse-a")
    assert REQUIRED_TOP_LEVEL <= set(msg)
    assert msg["version"] == vda5050.VDA_VERSION
    assert msg["headerId"] == 42
    assert msg["agvPosition"]["mapId"] == "warehouse-a"
    assert msg["agvPosition"]["x"] == 1.235
    assert msg["batteryState"] == {"batteryCharge": 77.8, "charging": False}
    assert msg["timestamp"].endswith("Z")


def test_topics_follow_vda5050_layout():
    assert vda5050.state_topic("fleetdeck", "AMR-001") == "uagv/v2/fleetdeck/AMR-001/state"
    assert vda5050.order_topic("fleetdeck", "AMR-001") == "uagv/v2/fleetdeck/AMR-001/order"
    assert vda5050.instant_actions_topic("fleetdeck", "AMR-001").endswith("/instantActions")
