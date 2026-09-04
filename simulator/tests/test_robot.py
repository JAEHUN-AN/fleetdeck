import math
import random

from fleetsim import robot
from fleetsim.robot import Waypoint


def _parked(battery: float = 80.0, **kw) -> robot.RobotState:
    base = dict(
        serial="AMR-001", x=0.0, y=0.0, theta=0.0, battery=battery,
        driving=False, charging=False, header_id=0,
    )
    base.update(kw)
    return robot.RobotState(**base)


def test_robot_without_order_stays_parked():
    # Arrange: 주문이 없는 로봇
    parked = _parked()

    # Act: 10틱 진행
    state = parked
    for _ in range(10):
        state = robot.step(state, dt=1.0)

    # Assert: 제자리, 대기, 배터리 그대로 (배회하지 않는다)
    assert (state.x, state.y) == (0.0, 0.0)
    assert state.driving is False
    assert state.battery == parked.battery
    assert state.header_id == 10


def test_assign_order_sets_waypoints_without_mutating_original():
    parked = _parked()
    wps = (Waypoint("N01", 5.0, 0.0, 0), Waypoint("N02", 5.0, 3.0, 1))

    assigned = robot.assign_order(parked, "M-7", wps)

    assert assigned.order_id == "M-7"
    assert assigned.waypoints == wps
    assert assigned.has_order is True
    assert parked.waypoints == ()  # 원본 불변
    assert parked.has_order is False


def test_step_drives_toward_first_waypoint_and_drains_battery():
    state = robot.assign_order(_parked(), "M-1", (Waypoint("N01", 10.0, 0.0, 0),))

    after = robot.step(state, dt=1.0)

    assert math.isclose(after.x, robot.SPEED_M_PER_S)
    assert after.y == 0.0
    assert after.driving is True
    assert after.battery < state.battery


def test_waypoints_are_consumed_in_order_and_order_completes():
    state = robot.assign_order(
        _parked(), "M-2", (Waypoint("N01", 2.0, 0.0, 0), Waypoint("N02", 2.0, 2.0, 1))
    )

    visited = []
    for _ in range(30):
        prev = len(state.waypoints)
        state = robot.step(state, dt=1.0)
        if len(state.waypoints) < prev:
            visited.append(state.last_node_id)
        if not state.waypoints:
            break

    assert visited == ["N01", "N02"]
    assert state.waypoints == ()
    assert state.driving is False           # 마지막 노드 도착 후 정지
    assert state.order_id == "M-2"          # 완료 후에도 orderId 는 유지
    assert (round(state.x, 3), round(state.y, 3)) == (2.0, 2.0)


def test_low_battery_pauses_order_and_resumes_after_charge():
    state = robot.assign_order(
        _parked(battery=robot.LOW_BATTERY_PCT), "M-3", (Waypoint("N01", 10.0, 0.0, 0),)
    )

    charging = robot.step(state, dt=1.0)
    assert charging.charging is True
    assert charging.driving is False
    assert charging.waypoints == state.waypoints  # 주문은 유지된다

    # 완충될 때까지 진행하면 다시 주행한다
    for _ in range(200):
        charging = robot.step(charging, dt=1.0)
        if charging.driving:
            break

    assert charging.charging is False
    assert charging.driving is True
    assert charging.x > 0.0


def test_spawn_parks_at_given_position():
    r = robot.spawn("AMR-007", x=12.0, y=2.0, rng=random.Random(3))

    assert (r.x, r.y) == (12.0, 2.0)
    assert r.driving is False
    assert r.waypoints == ()
    assert 45.0 <= r.battery <= 100.0
