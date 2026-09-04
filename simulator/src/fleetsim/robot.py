"""격자 맵 위를 움직이는 가상 AMR. 상태는 불변, 전이는 순수 함수."""

from __future__ import annotations

import math
import random
from dataclasses import dataclass, replace

SPEED_M_PER_S = 1.2
BATTERY_DRAIN_PER_M = 0.05  # 1m 이동 시 배터리 % 감소
BATTERY_CHARGE_PER_S = 0.5  # 충전 중 초당 회복 %
LOW_BATTERY_PCT = 20.0
FULL_BATTERY_PCT = 95.0
ARRIVE_EPSILON_M = 0.1


@dataclass(frozen=True)
class RobotState:
    serial: str
    x: float
    y: float
    theta: float
    battery: float
    target_x: float
    target_y: float
    driving: bool
    charging: bool
    header_id: int
    order_id: str = ""
    last_node_id: str = ""


def spawn(serial: str, width: int, height: int, rng: random.Random) -> RobotState:
    x, y = rng.uniform(1, width - 1), rng.uniform(1, height - 1)
    return RobotState(
        serial=serial,
        x=x,
        y=y,
        theta=0.0,
        battery=rng.uniform(60.0, 100.0),
        target_x=x,
        target_y=y,
        driving=False,
        charging=False,
        header_id=0,
    )


def step(state: RobotState, dt: float, width: int, height: int, rng: random.Random) -> RobotState:
    """dt초 후 상태를 돌려준다. 충전 → 이동 → 목표 재선정 순."""
    next_header = state.header_id + 1

    if state.charging:
        return _charge(state, dt, next_header)

    if state.battery <= LOW_BATTERY_PCT:
        return replace(state, driving=False, charging=True, header_id=next_header)

    dx, dy = state.target_x - state.x, state.target_y - state.y
    dist = math.hypot(dx, dy)

    if dist <= ARRIVE_EPSILON_M:
        return _pick_new_target(state, width, height, rng, next_header)

    travel = min(SPEED_M_PER_S * dt, dist)
    ratio = travel / dist
    return replace(
        state,
        x=state.x + dx * ratio,
        y=state.y + dy * ratio,
        theta=math.atan2(dy, dx),
        battery=max(0.0, state.battery - travel * BATTERY_DRAIN_PER_M),
        driving=True,
        header_id=next_header,
    )


def _charge(state: RobotState, dt: float, header_id: int) -> RobotState:
    battery = min(100.0, state.battery + BATTERY_CHARGE_PER_S * dt)
    still_charging = battery < FULL_BATTERY_PCT
    return replace(state, battery=battery, charging=still_charging, driving=False, header_id=header_id)


def _pick_new_target(
    state: RobotState, width: int, height: int, rng: random.Random, header_id: int
) -> RobotState:
    node = f"N{rng.randint(1, 99):02d}"
    return replace(
        state,
        target_x=rng.uniform(1, width - 1),
        target_y=rng.uniform(1, height - 1),
        driving=False,
        last_node_id=node,
        order_id=f"ORD-{state.serial}-{header_id}",
        header_id=header_id,
    )
