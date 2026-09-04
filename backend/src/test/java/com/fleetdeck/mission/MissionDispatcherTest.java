package com.fleetdeck.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetdeck.robot.RobotStateMessage;
import com.fleetdeck.robot.RobotStates;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MissionDispatcherTest {

	@Test
	void picksIdleRobotWithHighestBattery() {
		List<RobotStateMessage> fleet = List.of(
				RobotStates.idle("AMR-001", 60.0),
				RobotStates.idle("AMR-002", 90.0),
				RobotStates.idle("AMR-003", 75.0));

		Optional<RobotStateMessage> picked = MissionDispatcher.pickIdleRobot(fleet);

		assertThat(picked).map(RobotStateMessage::serialNumber).hasValue("AMR-002");
	}

	@Test
	void skipsDrivingChargingAndRobotsWithRemainingNodes() {
		List<RobotStateMessage> fleet = List.of(
				RobotStates.of("AMR-001", "M-1", true, false, 99.0, List.of()),
				RobotStates.of("AMR-002", "", false, true, 98.0, List.of()),
				RobotStates.of("AMR-003", "M-2", false, false, 97.0,
						List.of(new RobotStateMessage.NodeState("D01", 2, true))),
				RobotStates.idle("AMR-004", 40.0));

		Optional<RobotStateMessage> picked = MissionDispatcher.pickIdleRobot(fleet);

		assertThat(picked).map(RobotStateMessage::serialNumber).hasValue("AMR-004");
	}

	@Test
	void returnsEmptyWhenNoIdleRobot() {
		List<RobotStateMessage> fleet = List.of(
				RobotStates.of("AMR-001", "M-1", true, false, 99.0, List.of()));

		assertThat(MissionDispatcher.pickIdleRobot(fleet)).isEmpty();
	}

	@Test
	void robotThatFinishedItsOrderBecomesAvailableAgain() {
		// 주문을 마친 로봇은 orderId 가 남아 있어도 다시 배정 대상이다.
		List<RobotStateMessage> fleet = List.of(
				RobotStates.of("AMR-001", "M-1", false, false, 55.0, List.of()));

		assertThat(MissionDispatcher.pickIdleRobot(fleet))
				.map(RobotStateMessage::serialNumber)
				.hasValue("AMR-001");
	}
}
