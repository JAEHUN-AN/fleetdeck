package com.fleetdeck.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetdeck.robot.RobotStateMessage;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MissionDispatcherTest {

	private static RobotStateMessage robot(String serial, boolean driving, boolean charging, double battery) {
		return new RobotStateMessage(1, null, null, "fleetdeck", serial, null, null, driving, false, null,
				new RobotStateMessage.BatteryState(battery, charging), "AUTOMATIC", null);
	}

	@Test
	void picksIdleRobotWithHighestBattery() {
		List<RobotStateMessage> fleet = List.of(
				robot("AMR-001", false, false, 60.0),
				robot("AMR-002", false, false, 90.0),
				robot("AMR-003", false, false, 75.0));

		Optional<RobotStateMessage> picked = MissionDispatcher.pickIdleRobot(fleet);

		assertThat(picked).map(RobotStateMessage::serialNumber).hasValue("AMR-002");
	}

	@Test
	void skipsDrivingAndChargingRobots() {
		List<RobotStateMessage> fleet = List.of(
				robot("AMR-001", true, false, 99.0),
				robot("AMR-002", false, true, 98.0),
				robot("AMR-003", false, false, 40.0));

		Optional<RobotStateMessage> picked = MissionDispatcher.pickIdleRobot(fleet);

		assertThat(picked).map(RobotStateMessage::serialNumber).hasValue("AMR-003");
	}

	@Test
	void returnsEmptyWhenNoIdleRobot() {
		List<RobotStateMessage> fleet = List.of(robot("AMR-001", true, false, 99.0));

		assertThat(MissionDispatcher.pickIdleRobot(fleet)).isEmpty();
	}
}
