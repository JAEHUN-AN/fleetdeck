"""소터·컨베이어 같은 고정 설비의 상태 전이 모델."""

from __future__ import annotations

import random
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from enum import StrEnum

ALARM_PROBABILITY_PER_TICK = 0.01
RECOVER_PROBABILITY_PER_TICK = 0.2
NOMINAL_THROUGHPUT = 1800  # 개/분
ALARM_CODES = ("E101_JAM", "E205_MOTOR_OVERLOAD", "E310_SENSOR_BLOCKED")


class EquipmentStatus(StrEnum):
    RUNNING = "RUNNING"
    STOPPED = "STOPPED"
    ALARM = "ALARM"


@dataclass(frozen=True)
class SorterState:
    equipment_id: str
    equipment_type: str
    x: float
    y: float
    status: EquipmentStatus
    throughput_per_min: int
    alarm_code: str | None = None


def spawn(equipment_id: str, x: float, y: float, equipment_type: str = "WHEEL_SORTER") -> SorterState:
    return SorterState(
        equipment_id=equipment_id,
        equipment_type=equipment_type,
        x=x,
        y=y,
        status=EquipmentStatus.RUNNING,
        throughput_per_min=NOMINAL_THROUGHPUT,
    )


def step(state: SorterState, rng: random.Random) -> SorterState:
    if state.status is EquipmentStatus.ALARM:
        if rng.random() < RECOVER_PROBABILITY_PER_TICK:
            return replace(
                state,
                status=EquipmentStatus.RUNNING,
                throughput_per_min=NOMINAL_THROUGHPUT,
                alarm_code=None,
            )
        return state

    if rng.random() < ALARM_PROBABILITY_PER_TICK:
        return replace(
            state,
            status=EquipmentStatus.ALARM,
            throughput_per_min=0,
            alarm_code=rng.choice(ALARM_CODES),
        )

    jitter = rng.randint(-80, 80)
    return replace(state, throughput_per_min=max(0, NOMINAL_THROUGHPUT + jitter))


def topic(equipment_id: str) -> str:
    return f"fleetdeck/equipment/{equipment_id}/state"


def to_message(state: SorterState) -> dict:
    return {
        "equipmentId": state.equipment_id,
        "equipmentType": state.equipment_type,
        "status": state.status.value,
        "throughputPerMin": state.throughput_per_min,
        "alarmCode": state.alarm_code,
        "x": round(state.x, 2),
        "y": round(state.y, 2),
        "timestamp": datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
    }
