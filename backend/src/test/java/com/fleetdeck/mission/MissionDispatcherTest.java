package com.fleetdeck.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetdeck.robot.RobotStateMessage;
import com.fleetdeck.robot.RobotStates;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MissionDispatcherTest {

	private static final Set<String> NONE_RESERVED = Set.of();

	@Test
	void picksIdleRobotWithHighestBattery() {
		List<RobotStateMessage> fleet = List.of(
				RobotStates.idle("AMR-001", 60.0),
				RobotStates.idle("AMR-002", 90.0),
				RobotStates.idle("AMR-003", 75.0));

		Optional<RobotStateMessage> picked = MissionDispatcher.pickIdleRobot(fleet, NONE_RESERVED);

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

		Optional<RobotStateMessage> picked = MissionDispatcher.pickIdleRobot(fleet, NONE_RESERVED);

		assertThat(picked).map(RobotStateMessage::serialNumber).hasValue("AMR-004");
	}

	@Test
	void returnsEmptyWhenNoIdleRobot() {
		List<RobotStateMessage> fleet = List.of(
				RobotStates.of("AMR-001", "M-1", true, false, 99.0, List.of()));

		assertThat(MissionDispatcher.pickIdleRobot(fleet, NONE_RESERVED)).isEmpty();
	}

	@Test
	void robotThatFinishedItsOrderBecomesAvailableAgain() {
		// 주문을 마친 로봇은 orderId 가 남아 있어도 다시 배정 대상이다.
		List<RobotStateMessage> fleet = List.of(
				RobotStates.of("AMR-001", "M-1", false, false, 55.0, List.of()));

		assertThat(MissionDispatcher.pickIdleRobot(fleet, NONE_RESERVED))
				.map(RobotStateMessage::serialNumber)
				.hasValue("AMR-001");
	}

	@Test
	void reservedRobotIsNotPickedAgainEvenWhileStillReportingIdle() {
		// 실제로 났던 버그: 배정 직후 텔레메트리가 갱신되기 전이라 같은 로봇이 유휴로 보인다.
		// 예약이 없으면 배터리가 가장 많은 AMR-002 가 연속 두 번 뽑혀 앞 미션이 유실된다.
		List<RobotStateMessage> fleet = List.of(
				RobotStates.idle("AMR-001", 60.0),
				RobotStates.idle("AMR-002", 90.0));

		Optional<RobotStateMessage> second = MissionDispatcher.pickIdleRobot(fleet, Set.of("AMR-002"));

		assertThat(second).map(RobotStateMessage::serialNumber).hasValue("AMR-001");
	}

	@Test
	void returnsEmptyWhenEveryIdleRobotIsReserved() {
		List<RobotStateMessage> fleet = List.of(
				RobotStates.idle("AMR-001", 60.0),
				RobotStates.idle("AMR-002", 90.0));

		assertThat(MissionDispatcher.pickIdleRobot(fleet, Set.of("AMR-001", "AMR-002"))).isEmpty();
	}
}
