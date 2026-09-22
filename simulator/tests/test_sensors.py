"""센서 채널과 이상 주입 (난수는 시드로 고정한다)."""

from __future__ import annotations

import random
import statistics

import pytest

from fleetsim import sensors


def _bank(**overrides) -> sensors.SensorBank:
    base = sensors.spawn("UA-SORTER-01")
    return base if not overrides else type(base)(**{**base.__dict__, **overrides})


def _run(bank: sensors.SensorBank, ticks: int, rng: random.Random):
    """ticks 번 돌려 채널별 값 리스트와 사건 목록을 돌려준다."""
    series: dict[str, list[float]] = {c.name: [] for c in sensors.CHANNELS}
    events: list[sensors.FaultEvent] = []
    for _ in range(ticks):
        bank, readings, new_events = sensors.step(bank, rng)
        for name, value in readings.items():
            series[name].append(value)
        events.extend(new_events)
    return bank, series, events


def test_channels_cover_the_three_physical_quantities():
    names = [c.name for c in sensors.CHANNELS]
    assert names == ["MotorCurrent", "VibrationRms", "BearingTemp"]
    assert [c.unit for c in sensors.CHANNELS] == ["A", "mm/s", "degC"]


def test_healthy_readings_hover_around_nominal():
    # 주입이 없으면 평균은 공칭값, 산포는 사양의 sigma 근처여야 한다.
    bank = _bank(inject_probability=0.0)
    _, series, events = _run(bank, 400, random.Random(11))

    assert events == []
    for spec in sensors.CHANNELS:
        values = series[spec.name]
        assert statistics.fmean(values) == pytest.approx(spec.nominal, abs=spec.sigma)
        assert statistics.stdev(values) == pytest.approx(spec.sigma, rel=0.35)


def test_spike_lasts_exactly_one_tick():
    bank = _bank(inject_probability=0.0)
    bank = sensors.inject(bank, sensors.FaultKind.SPIKE, "MotorCurrent", magnitude=8.0, ticks=1)

    _, series, _ = _run(bank, 5, random.Random(3))
    values = series["MotorCurrent"]
    spec = sensors.spec_of("MotorCurrent")

    assert values[0] > spec.nominal + 5 * spec.sigma
    assert all(v < spec.nominal + 3 * spec.sigma for v in values[1:])


def test_step_holds_a_new_level_then_returns():
    bank = _bank(inject_probability=0.0)
    bank = sensors.inject(bank, sensors.FaultKind.STEP, "BearingTemp", magnitude=6.0, ticks=10)

    _, series, _ = _run(bank, 20, random.Random(5))
    values = series["BearingTemp"]
    spec = sensors.spec_of("BearingTemp")

    during = statistics.fmean(values[:10])
    after = statistics.fmean(values[12:])
    assert during > spec.nominal + 4 * spec.sigma
    assert after == pytest.approx(spec.nominal, abs=spec.sigma)


def test_drift_grows_over_time_rather_than_jumping():
    bank = _bank(inject_probability=0.0)
    bank = sensors.inject(bank, sensors.FaultKind.DRIFT, "VibrationRms", magnitude=0.4, ticks=30)

    _, series, _ = _run(bank, 30, random.Random(7))
    values = series["VibrationRms"]

    early = statistics.fmean(values[:5])
    late = statistics.fmean(values[-5:])
    assert late > early + 5 * sensors.spec_of("VibrationRms").sigma
    # 계단이 아니라 경사여야 한다: 초반 5틱은 아직 공칭에 가깝다.
    assert early == pytest.approx(sensors.spec_of("VibrationRms").nominal, abs=0.3)


def test_variance_widens_spread_without_moving_the_mean():
    bank = _bank(inject_probability=0.0)
    bank = sensors.inject(bank, sensors.FaultKind.VARIANCE, "MotorCurrent", magnitude=5.0, ticks=300)

    _, series, _ = _run(bank, 300, random.Random(13))
    values = series["MotorCurrent"]
    spec = sensors.spec_of("MotorCurrent")

    assert statistics.fmean(values) == pytest.approx(spec.nominal, abs=spec.sigma)
    assert statistics.stdev(values) > 3 * spec.sigma


def test_injection_emits_a_labelled_event():
    """정답 라벨이 없으면 탐지율을 잴 수 없다. 시작과 끝을 모두 남긴다."""
    bank = _bank(inject_probability=0.0)
    bank = sensors.inject(bank, sensors.FaultKind.STEP, "BearingTemp", magnitude=6.0, ticks=3)

    _, _, events = _run(bank, 6, random.Random(2))

    assert [e.phase for e in events] == ["END"]
    ended = events[0]
    assert ended.kind is sensors.FaultKind.STEP
    assert ended.channel == "BearingTemp"
    assert ended.equipment_id == "UA-SORTER-01"


def test_one_fault_per_channel_at_a_time():
    bank = _bank(inject_probability=0.0)
    bank = sensors.inject(bank, sensors.FaultKind.STEP, "MotorCurrent", magnitude=6.0, ticks=10)
    bank = sensors.inject(bank, sensors.FaultKind.SPIKE, "MotorCurrent", magnitude=8.0, ticks=1)

    # 나중 것이 무시된다 — 겹치면 어느 쪽을 탐지한 것인지 채점할 수 없다.
    assert bank.faults["MotorCurrent"].kind is sensors.FaultKind.STEP


def test_random_injection_is_reproducible_for_a_seed():
    _, _, events_a = _run(_bank(), 500, random.Random(42))
    _, _, events_b = _run(_bank(), 500, random.Random(42))

    assert [(e.kind, e.channel, e.phase) for e in events_a] == [
        (e.kind, e.channel, e.phase) for e in events_b
    ]
    assert events_a, "500틱이면 주입이 한 번은 있어야 한다"
