"""주문(order)을 받아 경유지를 따라 주행하는 가상 AMR. 상태는 불변, 전이는 순수 함수.

주문이 없으면 자기 대기 슬롯으로 복귀한다. 실제 AMR 도 일을 마치면 투입 지점에
계속 서 있지 않고 대기 구역으로 돌아간다. 복귀 주행 중에도 새 주문을 받으면
즉시 그쪽으로 전환한다 (nodeStates 가 비어 있어 관제는 이 로봇을 가용으로 본다).
"""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, replace

SPEED_M_PER_S = 1.2
BATTERY_DRAIN_PER_M = 0.05  # 1m 이동 시 배터리 % 감소
BATTERY_CHARGE_PER_S = 2.0  # 충전 중 초당 회복 %
LOW_BATTERY_PCT = 20.0
FULL_BATTERY_PCT = 95.0
ARRIVE_EPSILON_M = 0.15


@dataclass(frozen=True)
class Waypoint:
    """order 의 노드 하나. 좌표는 RCS 가 nodePosition 으로 내려준다."""

    node_id: str
    x: float
    y: float
    sequence_id: int = 0


@dataclass(frozen=True)
class RobotState:
    serial: str
    x: float
    y: float
    theta: float
    battery: float
    driving: bool
    charging: bool
    header_id: int
    park_x: float = 0.0
    park_y: float = 0.0
    waypoints: tuple[Waypoint, ...] = ()
    order_id: str = ""
    last_node_id: str = ""

    @property
    def has_order(self) -> bool:
        return bool(self.waypoints)

    @property
    def is_parked(self) -> bool:
        return math.hypot(self.park_x - self.x, self.park_y - self.y) <= ARRIVE_EPSILON_M


def spawn(serial: str, x: float, y: float, rng: random.Random) -> RobotState:
    """대기 슬롯에 정지 상태로 생성한다. 그 자리가 이 로봇의 복귀 지점이 된다."""
    return RobotState(
        serial=serial,
        x=x,
        y=y,
        theta=0.0,
        battery=rng.uniform(45.0, 100.0),
        driving=False,
        charging=False,
        header_id=0,
        park_x=x,
        park_y=y,
    )


def assign_order(state: RobotState, order_id: str, waypoints: tuple[Waypoint, ...]) -> RobotState:
    """RCS 가 내린 order 를 적용한다. 복귀 중이었다면 즉시 주문 쪽으로 전환된다."""
    return replace(state, order_id=order_id, waypoints=waypoints, header_id=state.header_id + 1)


def step(state: RobotState, dt: float) -> RobotState:
    """dt초 후 상태. 충전 > 저배터리 > 주문 주행 > 대기 슬롯 복귀 > 정지 순."""
    next_header = state.header_id + 1

    if state.charging:
        return _charge(state, dt, next_header)

    # 저배터리면 주문을 유지한 채 충전한다. 충전이 끝나면 남은 경유지부터 이어서 간다.
    if state.battery <= LOW_BATTERY_PCT:
        return replace(state, driving=False, charging=True, header_id=next_header)

    if state.waypoints:
        return _drive_order(state, dt, next_header)

    if not state.is_parked:
        return _drive_park(state, dt, next_header)

    return replace(state, driving=False, header_id=next_header)


def _drive_order(state: RobotState, dt: float, header_id: int) -> RobotState:
    target = state.waypoints[0]
    moved = _move_toward(state, target.x, target.y, dt)

    if moved is None:
        return _arrive(state, target, header_id)
    return replace(moved, driving=True, header_id=header_id)


def _drive_park(state: RobotState, dt: float, header_id: int) -> RobotState:
    """대기 슬롯으로 복귀. 주문이 아니므로 nodeStates 는 계속 비어 있다."""
    moved = _move_toward(state, state.park_x, state.park_y, dt)

    if moved is None:
        return replace(state, x=state.park_x, y=state.park_y, driving=False, header_id=header_id)
    return replace(moved, driving=True, header_id=header_id)


def _move_toward(state: RobotState, tx: float, ty: float, dt: float) -> RobotState | None:
    """목표로 dt 만큼 이동한 상태. 이미 도착했으면 None."""
    dx, dy = tx - state.x, ty - state.y
    dist = math.hypot(dx, dy)
    if dist <= ARRIVE_EPSILON_M:
        return None

    travel = min(SPEED_M_PER_S * dt, dist)
    ratio = travel / dist
    return replace(
        state,
        x=state.x + dx * ratio,
        y=state.y + dy * ratio,
        theta=math.atan2(dy, dx),
        battery=max(0.0, state.battery - travel * BATTERY_DRAIN_PER_M),
    )


def _arrive(state: RobotState, target: Waypoint, header_id: int) -> RobotState:
    """경유지 도착: 큐에서 빼고, 마지막이었으면 주행을 멈춘다."""
    remaining = state.waypoints[1:]
    return replace(
        state,
        x=target.x,
        y=target.y,
        waypoints=remaining,
        last_node_id=target.node_id,
        driving=bool(remaining),
        header_id=header_id,
    )


def _charge(state: RobotState, dt: float, header_id: int) -> RobotState:
    battery = min(100.0, state.battery + BATTERY_CHARGE_PER_S * dt)
    still_charging = battery < FULL_BATTERY_PCT
    return replace(state, battery=battery, charging=still_charging, driving=False, header_id=header_id)
