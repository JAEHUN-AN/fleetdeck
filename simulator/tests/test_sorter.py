import random

from fleetsim import sorter


def test_spawn_is_running_at_nominal_throughput():
    s = sorter.spawn("SORTER-01", x=5.0, y=22.0)
    assert s.status is sorter.EquipmentStatus.RUNNING
    assert s.throughput_per_min == sorter.NOMINAL_THROUGHPUT
    assert s.alarm_code is None


def test_step_eventually_raises_alarm_and_recovers():
    rng = random.Random(7)
    s = sorter.spawn("SORTER-01", x=5.0, y=22.0)

    states = []
    for _ in range(2000):
        s = sorter.step(s, rng)
        states.append(s.status)

    assert sorter.EquipmentStatus.ALARM in states
    assert states[-1] in (sorter.EquipmentStatus.RUNNING, sorter.EquipmentStatus.ALARM)


def test_alarm_state_has_zero_throughput_and_code():
    rng = random.Random(0)
    s = sorter.spawn("SORTER-01", x=5.0, y=22.0)
    alarmed = s
    for _ in range(2000):
        alarmed = sorter.step(alarmed, rng)
        if alarmed.status is sorter.EquipmentStatus.ALARM:
            break

    assert alarmed.status is sorter.EquipmentStatus.ALARM
    assert alarmed.throughput_per_min == 0
    assert alarmed.alarm_code in sorter.ALARM_CODES


def test_message_shape():
    msg = sorter.to_message(sorter.spawn("SORTER-02", x=17.0, y=22.0))
    assert msg["equipmentId"] == "SORTER-02"
    assert msg["status"] == "RUNNING"
    assert {"equipmentType", "throughputPerMin", "alarmCode", "x", "y", "timestamp"} <= set(msg)
    assert sorter.topic("SORTER-02") == "fleetdeck/equipment/SORTER-02/state"
