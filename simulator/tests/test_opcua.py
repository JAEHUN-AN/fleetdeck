"""OPC UA 주소공간 매핑 (서버 없이 도는 순수 함수만)."""

from __future__ import annotations

import pytest

from fleetsim import opcua as ua
from fleetsim import sorter as sorter_model
from fleetsim.sorter import EquipmentStatus


def _state(**overrides) -> sorter_model.SorterState:
    base = sorter_model.spawn("UA-SORTER-01", x=3.0, y=7.5)
    return base if not overrides else type(base)(**{**base.__dict__, **overrides})


def test_node_identifier_is_deterministic_and_hierarchical():
    assert ua.node_identifier("UA-SORTER-01", "Status") == "Equipment/UA-SORTER-01/Status"


def test_equipment_ids_are_numbered_and_distinct_from_mqtt_ones():
    ids = ua.equipment_ids(3)
    assert ids == ["UA-SORTER-01", "UA-SORTER-02", "UA-SORTER-03"]
    assert not any(i.startswith("SORTER-") for i in ids), "MQTT 소터와 이름이 겹치면 안 된다"


def test_variable_values_cover_every_declared_variable():
    values = ua.variable_values(_state())
    assert set(values) == set(ua.ALL_VARIABLES)


def test_sensor_channels_ride_the_same_address_space():
    """채널을 따로 노출하지 않는다 - 수집기가 브라우징으로 같이 찾는다."""
    values = ua.variable_values(_state(), {"MotorCurrent": 12.7, "VibrationRms": 2.4,
                                          "BearingTemp": 45.9})
    assert values["MotorCurrent"] == pytest.approx(12.7)
    assert values["BearingTemp"] == pytest.approx(45.9)


def test_missing_reading_is_zero_not_missing():
    """OPC UA 스칼라는 타입이 고정이다. 값이 없다고 변수를 빼면 브라우징 결과가 흔들린다."""
    values = ua.variable_values(_state())
    assert values["MotorCurrent"] == pytest.approx(0.0)


def test_variable_values_map_running_state():
    values = ua.variable_values(_state())
    assert values["Status"] == "RUNNING"
    assert values["EquipmentType"] == "WHEEL_SORTER"
    assert values["ThroughputPerMin"] == sorter_model.NOMINAL_THROUGHPUT
    assert values["PositionX"] == pytest.approx(3.0)
    assert values["PositionY"] == pytest.approx(7.5)


def test_alarm_code_is_empty_string_when_absent():
    """OPC UA 스칼라 변수는 타입이 고정이다. None 을 실으면 타입이 흔들린다."""
    assert ua.variable_values(_state())["AlarmCode"] == ""


def test_alarm_state_carries_code_and_zero_throughput():
    values = ua.variable_values(
        _state(status=EquipmentStatus.ALARM, throughput_per_min=0, alarm_code="E101_JAM")
    )
    assert values["Status"] == "ALARM"
    assert values["AlarmCode"] == "E101_JAM"
    assert values["ThroughputPerMin"] == 0


def test_throughput_is_int_not_float():
    """백엔드가 Integer 로 읽는다. Variant 타입이 Double 이 되면 매핑이 깨진다."""
    assert isinstance(ua.variable_values(_state())["ThroughputPerMin"], int)
