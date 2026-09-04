from fleetsim import vda5050
from fleetsim.robot import RobotState, Waypoint

REQUIRED_TOP_LEVEL = {
    "headerId", "timestamp", "version", "manufacturer", "serialNumber", "orderId",
    "orderUpdateId", "lastNodeId", "lastNodeSequenceId", "nodeStates", "edgeStates",
    "driving", "actionStates", "batteryState", "operatingMode", "errors", "safetyState",
}


def _robot(**kw) -> RobotState:
    base = dict(
        serial="AMR-001", x=1.23456, y=2.5, theta=0.7854, battery=77.77,
        driving=True, charging=False, header_id=42,
        order_id="M-1", last_node_id="N07",
        waypoints=(Waypoint("N08", 9.0, 4.0, 2),),
    )
    base.update(kw)
    return RobotState(**base)


def test_state_message_has_vda5050_required_fields():
    msg = vda5050.to_state_message(_robot(), manufacturer="fleetdeck", map_id="warehouse-a")

    assert REQUIRED_TOP_LEVEL <= set(msg)
    assert msg["version"] == vda5050.VDA_VERSION
    assert msg["headerId"] == 42
    assert msg["agvPosition"]["mapId"] == "warehouse-a"
    assert msg["agvPosition"]["x"] == 1.235
    assert msg["batteryState"] == {"batteryCharge": 77.8, "charging": False}
    assert msg["timestamp"].endswith("Z")


def test_node_states_reflect_remaining_waypoints():
    msg = vda5050.to_state_message(_robot(), manufacturer="fleetdeck", map_id="warehouse-a")

    assert msg["nodeStates"] == [
        {
            "nodeId": "N08",
            "sequenceId": 2,
            "released": True,
            "nodePosition": {"x": 9.0, "y": 4.0, "mapId": "warehouse-a"},
        }
    ]


def test_completed_order_reports_empty_node_states_and_not_driving():
    msg = vda5050.to_state_message(
        _robot(waypoints=(), driving=False), manufacturer="fleetdeck", map_id="warehouse-a"
    )

    assert msg["nodeStates"] == []
    assert msg["driving"] is False
    assert msg["velocity"]["vx"] == 0.0
    assert msg["orderId"] == "M-1"


def test_parse_order_extracts_waypoints_sorted_by_sequence():
    body = {
        "orderId": "M-9",
        "serialNumber": "AMR-002",
        "nodes": [
            {"nodeId": "N20", "sequenceId": 2, "released": True, "nodePosition": {"x": 8.0, "y": 9.0}},
            {"nodeId": "N10", "sequenceId": 0, "released": True, "nodePosition": {"x": 3.0, "y": 4.0}},
        ],
    }

    order_id, wps = vda5050.parse_order(body)

    assert order_id == "M-9"
    assert [w.node_id for w in wps] == ["N10", "N20"]
    assert (wps[0].x, wps[0].y) == (3.0, 4.0)


def test_parse_order_skips_unreleased_and_positionless_nodes():
    body = {
        "orderId": "M-10",
        "nodes": [
            {"nodeId": "OK", "sequenceId": 0, "released": True, "nodePosition": {"x": 1.0, "y": 1.0}},
            {"nodeId": "HORIZON", "sequenceId": 1, "released": False, "nodePosition": {"x": 2.0, "y": 2.0}},
            {"nodeId": "NOPOS", "sequenceId": 2, "released": True},
        ],
    }

    _, wps = vda5050.parse_order(body)

    assert [w.node_id for w in wps] == ["OK"]


def test_parse_order_on_empty_body_returns_nothing():
    order_id, wps = vda5050.parse_order({})

    assert order_id == ""
    assert wps == ()


def test_topics_and_serial_extraction():
    assert vda5050.state_topic("fleetdeck", "AMR-001") == "uagv/v2/fleetdeck/AMR-001/state"
    assert vda5050.order_topic("fleetdeck", "AMR-001") == "uagv/v2/fleetdeck/AMR-001/order"
    assert vda5050.instant_actions_topic("fleetdeck", "AMR-001").endswith("/instantActions")
    assert vda5050.serial_from_topic("uagv/v2/fleetdeck/AMR-003/order") == "AMR-003"
    assert vda5050.serial_from_topic("bogus") == ""
