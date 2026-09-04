package com.fleetdeck.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetdeck.mission.Mission.MissionStatus;
import com.fleetdeck.mission.Mission.MissionType;
import com.fleetdeck.robot.RobotStateMessage;
import com.fleetdeck.robot.RobotStates;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class MissionLifecycleTest {

	private static final List<RobotStateMessage.NodeState> ONE_NODE_LEFT =
			List.of(new RobotStateMessage.NodeState("D02", 2, true));

	private static Mission mission(MissionStatus status, String robot) {
		OffsetDateTime now = OffsetDateTime.now();
		return new Mission(7L, MissionType.TRANSPORT, "P01", "D02", status, robot, "WMS-1", now, now);
	}

	@Test
	void assignedBecomesRunningWhenRobotStartsDriving() {
		Mission assigned = mission(MissionStatus.ASSIGNED, "AMR-001");
		RobotStateMessage driving = RobotStates.of("AMR-001", "M-7", true, false, 80.0, ONE_NODE_LEFT);

		Optional<Mission> next = MissionLifecycle.nextStatus(assigned, driving);

		assertThat(next).map(Mission::status).hasValue(MissionStatus.RUNNING);
		assertThat(next).map(Mission::assignedRobot).hasValue("AMR-001");
	}

	@Test
	void runningBecomesDoneWhenRobotReachesLastNode() {
		Mission running = mission(MissionStatus.RUNNING, "AMR-001");
		RobotStateMessage arrived = RobotStates.of("AMR-001", "M-7", false, false, 78.0, List.of());

		Optional<Mission> next = MissionLifecycle.nextStatus(running, arrived);

		assertThat(next).map(Mission::status).hasValue(MissionStatus.DONE);
	}

	@Test
	void assignedStaysAssignedWhileRobotHasNotMovedYet() {
		Mission assigned = mission(MissionStatus.ASSIGNED, "AMR-001");
		// order 를 아직 못 받아 nodeStates 가 비어 있고 정지 상태.
		RobotStateMessage notStarted = RobotStates.of("AMR-001", "", false, false, 80.0, List.of());

		assertThat(MissionLifecycle.nextStatus(assigned, notStarted)).isEmpty();
	}

	@Test
	void runningStaysRunningWhileNodesRemain() {
		Mission running = mission(MissionStatus.RUNNING, "AMR-001");
		RobotStateMessage enRoute = RobotStates.of("AMR-001", "M-7", true, false, 70.0, ONE_NODE_LEFT);

		assertThat(MissionLifecycle.nextStatus(running, enRoute)).isEmpty();
	}

	@Test
	void robotErrorFailsTheMission() {
		Mission running = mission(MissionStatus.RUNNING, "AMR-001");
		RobotStateMessage broken = RobotStates.withError("AMR-001", "M-7");

		Optional<Mission> next = MissionLifecycle.nextStatus(running, broken);

		assertThat(next).map(Mission::status).hasValue(MissionStatus.FAILED);
	}

	@Test
	void chargingPauseDoesNotCompleteTheMission() {
		// 저배터리로 멈춘 로봇은 남은 노드를 들고 있으므로 완료가 아니다.
		Mission running = mission(MissionStatus.RUNNING, "AMR-001");
		RobotStateMessage charging = RobotStates.of("AMR-001", "M-7", false, true, 19.0, ONE_NODE_LEFT);

		assertThat(MissionLifecycle.nextStatus(running, charging)).isEmpty();
	}

	@Test
	void orderIdRoundTripsWithMissionId() {
		assertThat(mission(MissionStatus.PENDING, null).orderId()).isEqualTo("M-7");
		assertThat(Mission.idFromOrderId("M-7")).isEqualTo(7L);
		assertThat(Mission.idFromOrderId("")).isNull();
		assertThat(Mission.idFromOrderId(null)).isNull();
		assertThat(Mission.idFromOrderId("ORD-99")).isNull();
		assertThat(Mission.idFromOrderId("M-abc")).isNull();
	}

	@Test
	void terminalStatusesAreRecognised() {
		assertThat(MissionStatus.DONE.isTerminal()).isTrue();
		assertThat(MissionStatus.FAILED.isTerminal()).isTrue();
		assertThat(MissionStatus.RUNNING.isTerminal()).isFalse();
		assertThat(MissionStatus.PENDING.isTerminal()).isFalse();
	}
}
