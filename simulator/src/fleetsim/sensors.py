"""설비 연속 센서와 이상 주입.

상태(RUNNING/ALARM)와 처리량만으로는 통계를 걸 수 없다. 관리도도 이상 탐지도
연속 변량을 전제한다. 그래서 소터마다 세 채널을 붙인다.

이상은 무작위로 주입하되 **언제 어느 채널에 무엇을 넣었는지 사건으로 남긴다.**
정답 라벨이 없으면 탐지율도 오경보율도 잴 수 없고, 탐지기 비교는 인상평이 된다.

이상 유형 네 가지는 현장에서 실제로 구분해 부르는 것들이다.

- DRIFT    서서히 오른다. 베어링 마모, 필터 막힘. 임계값만 보면 늦게 잡힌다.
- SPIKE    한 틱 튄다. 순간 부하, 노이즈. 이동평균에 묻히기 쉽다.
- STEP     수준이 한 번에 바뀌어 유지된다. 부품 교체 후 오설정.
- VARIANCE 평균은 그대로인데 산포만 커진다. 체결 풀림. 평균만 보면 못 잡는다.
"""

from __future__ import annotations

import random
from collections.abc import Mapping
from dataclasses import dataclass, replace
from enum import StrEnum
from types import MappingProxyType


@dataclass(frozen=True)
class ChannelSpec:
    """채널 하나의 정상 동작 사양. sigma 는 정상 상태의 표준편차다."""

    name: str
    unit: str
    nominal: float
    sigma: float


CHANNELS: tuple[ChannelSpec, ...] = (
    ChannelSpec("MotorCurrent", "A", 12.0, 0.25),
    ChannelSpec("VibrationRms", "mm/s", 2.5, 0.15),
    ChannelSpec("BearingTemp", "degC", 45.0, 0.5),
)

_SPECS = {c.name: c for c in CHANNELS}

# 무작위 주입 기본값. 틱당 확률과 지속 구간.
INJECT_PROBABILITY_PER_TICK = 0.004
FAULT_TICKS = {
    "SPIKE": (1, 1),
    "STEP": (20, 60),
    "DRIFT": (30, 90),
    "VARIANCE": (30, 90),
}
# 크기는 sigma 배수. DRIFT 만 "틱당 sigma 배수" 로 읽는다.
FAULT_MAGNITUDE = {
    "SPIKE": (6.0, 12.0),
    "STEP": (4.0, 8.0),
    "DRIFT": (0.12, 0.35),
    "VARIANCE": (4.0, 8.0),
}


class FaultKind(StrEnum):
    DRIFT = "DRIFT"
    SPIKE = "SPIKE"
    STEP = "STEP"
    VARIANCE = "VARIANCE"


@dataclass(frozen=True)
class Fault:
    kind: FaultKind
    channel: str
    magnitude: float
    remaining: int
    elapsed: int = 0


@dataclass(frozen=True)
class FaultEvent:
    """정답 라벨. 채점은 이 사건과 탐지 시각을 맞춰 본다."""

    equipment_id: str
    channel: str
    kind: FaultKind
    magnitude: float
    phase: str  # START | END


@dataclass(frozen=True)
class SensorBank:
    equipment_id: str
    faults: Mapping[str, Fault]
    inject_probability: float = INJECT_PROBABILITY_PER_TICK


def spec_of(channel: str) -> ChannelSpec:
    return _SPECS[channel]


def spawn(equipment_id: str, inject_probability: float = INJECT_PROBABILITY_PER_TICK) -> SensorBank:
    return SensorBank(equipment_id, MappingProxyType({}), inject_probability)


def inject(bank: SensorBank, kind: FaultKind, channel: str, magnitude: float,
           ticks: int) -> SensorBank:
    """수동 주입. 이미 그 채널에 이상이 있으면 무시한다(겹치면 채점이 불가능해진다)."""
    if channel in bank.faults:
        return bank
    faults = dict(bank.faults)
    faults[channel] = Fault(kind, channel, magnitude, ticks)
    return replace(bank, faults=MappingProxyType(faults))


def step(bank: SensorBank, rng: random.Random) -> tuple[SensorBank, dict[str, float],
                                                        tuple[FaultEvent, ...]]:
    """한 틱. 새 상태, 채널별 측정값, 이번 틱에 시작·종료된 이상 사건을 돌려준다."""
    faults = dict(bank.faults)
    events: list[FaultEvent] = []

    started = _maybe_inject(bank, faults, rng)
    events.extend(started)

    readings: dict[str, float] = {}
    for spec in CHANNELS:
        fault = faults.get(spec.name)
        readings[spec.name] = _sample(spec, fault, rng)

    for spec in CHANNELS:
        fault = faults.get(spec.name)
        if fault is None:
            continue
        advanced = replace(fault, remaining=fault.remaining - 1, elapsed=fault.elapsed + 1)
        if advanced.remaining <= 0:
            del faults[spec.name]
            events.append(FaultEvent(bank.equipment_id, spec.name, fault.kind, fault.magnitude,
                                     "END"))
        else:
            faults[spec.name] = advanced

    return replace(bank, faults=MappingProxyType(faults)), readings, tuple(events)


def _maybe_inject(bank: SensorBank, faults: dict[str, Fault],
                  rng: random.Random) -> list[FaultEvent]:
    if bank.inject_probability <= 0 or rng.random() >= bank.inject_probability:
        return []
    free = [c.name for c in CHANNELS if c.name not in faults]
    if not free:
        return []

    channel = rng.choice(free)
    kind = FaultKind(rng.choice(list(FaultKind)))
    low_ticks, high_ticks = FAULT_TICKS[kind.value]
    low_mag, high_mag = FAULT_MAGNITUDE[kind.value]
    magnitude = rng.uniform(low_mag, high_mag)

    faults[channel] = Fault(kind, channel, magnitude, rng.randint(low_ticks, high_ticks))
    return [FaultEvent(bank.equipment_id, channel, kind, magnitude, "START")]


def _sample(spec: ChannelSpec, fault: Fault | None, rng: random.Random) -> float:
    """정상은 공칭값 + 백색잡음. 이상은 유형에 따라 평균이나 산포를 건드린다."""
    sigma = spec.sigma
    offset = 0.0

    if fault is not None:
        if fault.kind is FaultKind.VARIANCE:
            sigma = spec.sigma * fault.magnitude
        elif fault.kind is FaultKind.DRIFT:
            # 시작 시점에는 정상과 구분되지 않고 시간에 비례해 벌어진다.
            offset = spec.sigma * fault.magnitude * fault.elapsed
        else:  # SPIKE, STEP
            offset = spec.sigma * fault.magnitude

    return round(rng.gauss(spec.nominal + offset, sigma), 4)
