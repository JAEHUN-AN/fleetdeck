"""주문(order)을 받아 경유지를 따라 주행하는 가상 AMR. 상태는 불변, 전이는 순수 함수.

주문이 없으면 제자리에 대기한다. 예전처럼 무작위로 배회하지 않는다.
관제(RCS)가 주문을 줄 때만 움직이는 것이 실제 AMR 동작이고,
대기 상태가 유지되어야 디스패처가 유휴 로봇을 고를 수 있다.
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
    waypoints: tuple[Waypoint, ...] = ()
    order_id: str = ""
    last_node_id: str = ""

    @property
    def has_order(self) -> bool:
        return bool(self.waypoints)


def spawn(serial: str, x: float, y: float, rng: random.Random) -> RobotState:
    """대기 위치에 정지 상태로 생성한다."""
    return RobotState(
        serial=serial,
        x=x,
        y=y,
        theta=0.0,
        battery=rng.uniform(45.0, 100.0),
        driving=False,
        charging=False,
        header_id=0,
    )


def assign_order(state: RobotState, order_id: str, waypoints: tuple[Waypoint, ...]) -> RobotState:
    """RCS 가 내린 order 를 적용한다. 진행 중이던 주문은 덮어쓴다."""
    return replace(state, order_id=order_id, waypoints=waypoints, header_id=state.header_id + 1)


def step(state: RobotState, dt: float) -> RobotState:
    """dt초 후 상태. 충전 > 저배터리 > 주행 > 대기 순으로 판단한다."""
    next_header = state.header_id + 1

    if state.charging:
        return _charge(state, dt, next_header)

    # 저배터리면 주문을 유지한 채 충전한다. 충전이 끝나면 남은 경유지부터 이어서 간다.
    if state.battery <= LOW_BATTERY_PCT:
        return replace(state, driving=False, charging=True, header_id=next_header)

    if not state.waypoints:
        return replace(state, driving=False, header_id=next_header)

    return _drive(state, dt, next_header)


def _drive(state: RobotState, dt: float, header_id: int) -> RobotState:
    target = state.waypoints[0]
    dx, dy = target.x - state.x, target.y - state.y
    dist = math.hypot(dx, dy)

    if dist <= ARRIVE_EPSILON_M:
        return _arrive(state, target, header_id)

    travel = min(SPEED_M_PER_S * dt, dist)
    ratio = travel / dist
    return replace(
        state,
        x=state.x + dx * ratio,
        y=state.y + dy * ratio,
        theta=math.atan2(dy, dx),
        battery=max(0.0, state.battery - travel * BATTERY_DRAIN_PER_M),
        driving=True,
        header_id=header_id,
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
