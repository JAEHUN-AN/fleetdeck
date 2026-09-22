"""탐지기 채점용 라벨 데이터셋 생성.

탐지율·오경보율·탐지지연을 재려면 "언제부터 언제까지 이상이었는가"가 있어야 한다.
운영 중 무작위 주입은 재현이 어렵고 유형별 표본 수가 들쭉날쭉하다. 그래서 채점용은
**에피소드**로 뽑는다 — 유형마다 같은 수의 에피소드, 각 에피소드는 정상 구간으로
시작해 정해진 시점에 이상이 들어간다.

    uv run fleetsim-dataset > sensor-eval.csv

열: episode, kind, channel, tick, value, fault_active
`fault_active` 가 정답이다. 0 구간의 경보는 오경보, 1 구간의 첫 경보까지가 탐지지연.
"""

from __future__ import annotations

import csv
import random
import sys
from dataclasses import dataclass

from fleetsim import sensors

# 각 에피소드는 정상 WARMUP 틱 뒤에 이상이 들어간다. 탐지기가 통계를 잡을 시간을 준다.
WARMUP_TICKS = 150
FAULT_TICKS = 60
TAIL_TICKS = 40
EPISODES_PER_COMBINATION = 5
HEALTHY_EPISODES = 15

# 채점 대상 크기. 값이 크면 아무 탐지기나 잡으므로 "겨우 보이는" 수준으로 고정한다.
MAGNITUDES = {
    sensors.FaultKind.SPIKE: 8.0,
    sensors.FaultKind.STEP: 5.0,
    sensors.FaultKind.DRIFT: 0.15,
    sensors.FaultKind.VARIANCE: 5.0,
}


@dataclass(frozen=True)
class Row:
    episode: int
    kind: str
    channel: str
    tick: int
    value: float
    fault_active: int


def generate(seed: int = 20260922) -> list[Row]:
    rng = random.Random(seed)
    rows: list[Row] = []
    episode = 0

    for _ in range(HEALTHY_EPISODES):
        rows.extend(_episode(episode, None, sensors.CHANNELS[0].name, rng))
        episode += 1

    for kind in sensors.FaultKind:
        for spec in sensors.CHANNELS:
            for _ in range(EPISODES_PER_COMBINATION):
                rows.extend(_episode(episode, kind, spec.name, rng))
                episode += 1

    return rows


def _episode(episode: int, kind: sensors.FaultKind | None, channel: str,
             rng: random.Random) -> list[Row]:
    """정상 구간 → 이상 구간 → 회복 구간. SPIKE 는 이상 구간이 1틱이다."""
    bank = sensors.spawn(f"EVAL-{episode:03d}", inject_probability=0.0)
    rows: list[Row] = []

    for tick in range(WARMUP_TICKS):
        bank, readings, _ = sensors.step(bank, rng)
        rows.append(Row(episode, _label(kind), channel, tick, readings[channel], 0))

    if kind is None:
        duration = 0
    else:
        duration = 1 if kind is sensors.FaultKind.SPIKE else FAULT_TICKS
        bank = sensors.inject(bank, kind, channel, MAGNITUDES[kind], duration)

    for offset in range(duration + TAIL_TICKS):
        active = 1 if offset < duration else 0
        bank, readings, _ = sensors.step(bank, rng)
        rows.append(Row(episode, _label(kind), channel, WARMUP_TICKS + offset,
                        readings[channel], active))

    return rows


def _label(kind: sensors.FaultKind | None) -> str:
    return "NONE" if kind is None else kind.value


def run() -> None:
    writer = csv.writer(sys.stdout, lineterminator="\n")
    writer.writerow(["episode", "kind", "channel", "tick", "value", "fault_active"])
    for row in generate():
        writer.writerow([row.episode, row.kind, row.channel, row.tick, row.value,
                         row.fault_active])


if __name__ == "__main__":
    run()
