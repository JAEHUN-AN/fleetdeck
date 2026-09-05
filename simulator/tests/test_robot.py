import math
import random

from fleetsim import robot
from fleetsim.robot import Waypoint


def _parked(battery: float = 80.0, **kw) -> robot.RobotState:
    """대기 슬롯(0,0)에 정지해 있는 로봇."""
    base = dict(
        serial="AMR-001", x=0.0, y=0.0, theta=0.0, battery=battery,
        driving=False, charging=False, header_id=0, park_x=0.0, park_y=0.0,
    )
    base.update(kw)
    return robot.RobotState(**base)


def _run(state: robot.RobotState, ticks: int, dt: float = 1.0) -> robot.RobotState:
    for _ in range(ticks):
        state = robot.step(state, dt)
    return state


def test_parked_robot_without_order_stays_put():
    parked = _parked()

    state = _run(parked, 10)

    assert (state.x, state.y) == (0.0, 0.0)
    assert state.driving is False
    assert state.battery == parked.battery  # 제자리면 배터리도 안 쓴다
    assert state.header_id == 10


def test_assign_order_sets_waypoints_without_mutating_original():
    parked = _parked()
    wps = (Waypoint("N01", 5.0, 0.0, 0), Waypoint("N02", 5.0, 3.0, 1))

    assigned = robot.assign_order(parked, "M-7", wps)

    assert assigned.order_id == "M-7"
    assert assigned.waypoints == wps
    assert assigned.has_order is True
    assert parked.waypoints == ()
    assert parked.has_order is False


def test_step_drives_toward_first_waypoint_and_drains_battery():
    state = robot.assign_order(_parked(), "M-1", (Waypoint("N01", 10.0, 0.0, 0),))

    after = robot.step(state, dt=1.0)

    assert math.isclose(after.x, robot.SPEED_M_PER_S)
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
    assert state.order_id == "M-2"
    assert (round(state.x, 3), round(state.y, 3)) == (2.0, 2.0)


# 6m 를 1.2m/s 로 가면 5틱, 도착 판정에 1틱 더 필요하다.
OUTBOUND_TICKS = 6


def test_robot_returns_to_park_after_finishing_an_order():
    # 주문을 마친 로봇은 투입 지점에 서 있지 않고 대기 슬롯으로 돌아간다.
    state = robot.assign_order(_parked(), "M-3", (Waypoint("D01", 6.0, 0.0, 0),))
    state = _run(state, OUTBOUND_TICKS)
    assert state.waypoints == ()
    assert (round(state.x, 1), round(state.y, 1)) == (6.0, 0.0)
    assert state.is_parked is False

    state = _run(state, OUTBOUND_TICKS)  # 복귀

    assert state.is_parked is True
    assert (round(state.x, 3), round(state.y, 3)) == (0.0, 0.0)
    assert state.driving is False


def test_returning_robot_reports_driving_but_no_remaining_nodes():
    # 관제가 "주문 없음"(nodeStates 빔)으로 가용을 판단하므로,
    # 복귀 중에도 driving 을 정직하게 보고할 수 있다.
    state = robot.assign_order(_parked(), "M-4", (Waypoint("D01", 6.0, 0.0, 0),))
    state = _run(state, OUTBOUND_TICKS)

    returning = robot.step(state, dt=1.0)

    assert returning.driving is True
    assert returning.waypoints == ()
    assert returning.is_parked is False


def test_new_order_interrupts_the_return_trip():
    state = robot.assign_order(_parked(), "M-5", (Waypoint("D01", 6.0, 0.0, 0),))
    state = _run(state, OUTBOUND_TICKS)
    state = _run(state, 2)  # 복귀 도중 (6.0 -> 약 3.6)
    assert state.is_parked is False
    assert 3.0 < state.x < 4.0

    state = robot.assign_order(state, "M-6", (Waypoint("P09", 0.0, 8.0, 0),))
    # 도착 직후를 봐야 한다. 더 돌리면 대기 슬롯으로 복귀해버린다.
    for _ in range(30):
        state = robot.step(state, dt=1.0)
        if not state.waypoints:
            break

    assert state.last_node_id == "P09"
    assert (round(state.x, 1), round(state.y, 1)) == (0.0, 8.0)


def test_low_battery_pauses_order_and_resumes_after_charge():
    state = robot.assign_order(
        _parked(battery=robot.LOW_BATTERY_PCT), "M-7", (Waypoint("N01", 10.0, 0.0, 0),)
    )

    charging = robot.step(state, dt=1.0)
    assert charging.charging is True
    assert charging.driving is False
    assert charging.waypoints == state.waypoints

    for _ in range(200):
        charging = robot.step(charging, dt=1.0)
        if charging.driving:
            break

    assert charging.charging is False
    assert charging.driving is True
    assert charging.x > 0.0


def test_spawn_records_its_position_as_the_park_slot():
    r = robot.spawn("AMR-007", x=12.0, y=2.0, rng=random.Random(3))

    assert (r.x, r.y) == (12.0, 2.0)
    assert (r.park_x, r.park_y) == (12.0, 2.0)
    assert r.is_parked is True
    assert r.driving is False
    assert r.waypoints == ()
    assert 45.0 <= r.battery <= 100.0
