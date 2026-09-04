import math
import random

from fleetsim import robot


def test_step_moves_toward_target_and_drains_battery():
    # Arrange
    rng = random.Random(0)
    start = robot.RobotState(
        serial="AMR-001", x=0.0, y=0.0, theta=0.0, battery=80.0,
        target_x=10.0, target_y=0.0, driving=False, charging=False, header_id=0,
    )

    # Act
    after = robot.step(start, dt=1.0, width=40, height=25, rng=rng)

    # Assert
    assert math.isclose(after.x, robot.SPEED_M_PER_S)
    assert after.y == 0.0
    assert after.driving is True
    assert after.battery < start.battery
    assert after.header_id == 1
    assert start.x == 0.0  # 원본 불변


def test_step_picks_new_target_when_arrived():
    rng = random.Random(1)
    arrived = robot.RobotState(
        serial="AMR-001", x=5.0, y=5.0, theta=0.0, battery=80.0,
        target_x=5.0, target_y=5.0, driving=True, charging=False, header_id=3,
    )

    after = robot.step(arrived, dt=1.0, width=40, height=25, rng=rng)

    assert (after.target_x, after.target_y) != (5.0, 5.0)
    assert after.driving is False
    assert after.order_id.startswith("ORD-AMR-001-")


def test_low_battery_switches_to_charging_and_recovers():
    rng = random.Random(2)
    low = robot.RobotState(
        serial="AMR-001", x=0.0, y=0.0, theta=0.0, battery=robot.LOW_BATTERY_PCT,
        target_x=10.0, target_y=0.0, driving=True, charging=False, header_id=0,
    )

    charging = robot.step(low, dt=1.0, width=40, height=25, rng=rng)
    charged_more = robot.step(charging, dt=1.0, width=40, height=25, rng=rng)

    assert charging.charging is True and charging.driving is False
    assert charged_more.battery > charging.battery


def test_spawn_places_robot_inside_map():
    rng = random.Random(3)
    r = robot.spawn("AMR-007", width=40, height=25, rng=rng)
    assert 0 < r.x < 40 and 0 < r.y < 25
    assert 60.0 <= r.battery <= 100.0
