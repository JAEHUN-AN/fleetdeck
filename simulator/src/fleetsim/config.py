"""환경변수를 읽어 불변 설정 객체로 만든다."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class SimConfig:
    mqtt_host: str
    mqtt_port: int
    manufacturer: str
    robot_count: int
    sorter_count: int
    tick_sec: float
    map_width: int
    map_height: int
    map_id: str = "warehouse-a"

    @staticmethod
    def from_env() -> SimConfig:
        return SimConfig(
            mqtt_host=os.getenv("MQTT_HOST", "localhost"),
            mqtt_port=_int_env("MQTT_PORT", 1883),
            manufacturer=os.getenv("SIM_MANUFACTURER", "fleetdeck"),
            robot_count=_int_env("SIM_ROBOT_COUNT", 5),
            sorter_count=_int_env("SIM_SORTER_COUNT", 2),
            tick_sec=_float_env("SIM_TICK_SEC", 1.0),
            map_width=_int_env("SIM_MAP_WIDTH", 40),
            map_height=_int_env("SIM_MAP_HEIGHT", 25),
        )


def _int_env(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or raw == "":
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer, got {raw!r}") from exc


def _float_env(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or raw == "":
        return default
    try:
        return float(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number, got {raw!r}") from exc
