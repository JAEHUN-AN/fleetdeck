package com.fleetdeck.robot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RobotStateMessageTest {

	private final ObjectMapper mapper = new ObjectMapper();

	// 시뮬레이터가 실제로 보내는 형태. positionInitialized, velocity 등 모르는 필드 포함.
	private static final String DRIVING_WITH_ONE_NODE_LEFT = """
			{
			  "headerId": 42, "timestamp": "2026-09-04T02:00:00.000Z", "version": "2.0.0",
			  "manufacturer": "fleetdeck", "serialNumber": "AMR-001", "orderId": "M-7",
			  "orderUpdateId": 0, "lastNodeId": "P01", "lastNodeSequenceId": 0,
			  "nodeStates": [
			    {"nodeId": "D02", "sequenceId": 2, "released": true,
			     "nodePosition": {"x": 17.0, "y": 21.0, "mapId": "warehouse-a"}}
			  ],
			  "edgeStates": [], "driving": true, "paused": false,
			  "agvPosition": {"x": 1.235, "y": 2.5, "theta": 0.7854, "mapId": "warehouse-a", "positionInitialized": true},
			  "velocity": {"vx": 1.2, "vy": 0.0, "omega": 0.0},
			  "loads": [], "actionStates": [],
			  "batteryState": {"batteryCharge": 77.8, "charging": false},
			  "operatingMode": "AUTOMATIC", "errors": [],
			  "safetyState": {"eStop": "NONE", "fieldViolation": false}
			}
			""";

	@Test
	void parsesSimulatorPayloadIgnoringUnknownFields() throws Exception {
		RobotStateMessage m = mapper.readValue(DRIVING_WITH_ONE_NODE_LEFT, RobotStateMessage.class);

		assertThat(m.serialNumber()).isEqualTo("AMR-001");
		assertThat(m.orderId()).isEqualTo("M-7");
		assertThat(m.agvPosition().x()).isEqualTo(1.235);
		assertThat(m.agvPosition().mapId()).isEqualTo("warehouse-a");
		assertThat(m.batteryCharge()).isEqualTo(77.8);
		assertThat(m.driving()).isTrue();
		assertThat(m.errorCount()).isZero();
	}

	@Test
	void parsesNodeStatesAndReportsRemainingCount() throws Exception {
		RobotStateMessage m = mapper.readValue(DRIVING_WITH_ONE_NODE_LEFT, RobotStateMessage.class);

		assertThat(m.remainingNodes()).isEqualTo(1);
		assertThat(m.nodeStates().get(0).nodeId()).isEqualTo("D02");
		assertThat(m.isExecutingOrder()).isTrue();
		assertThat(m.hasFinishedOrder()).isFalse();
		assertThat(m.isAvailable()).isFalse();
	}

	@Test
	void finishedOrderHasNoRemainingNodesAndIsNotDriving() {
		RobotStateMessage finished = RobotStates.of("AMR-001", "M-7", false, false, 60.0, List.of());

		assertThat(finished.hasFinishedOrder()).isTrue();
		assertThat(finished.isExecutingOrder()).isFalse();
		assertThat(finished.isAvailable()).isTrue();
	}

	@Test
	void robotWithoutOrderIsAvailableButHasNotFinishedAnything() {
		RobotStateMessage parked = RobotStates.of("AMR-002", "", false, false, 60.0, List.of());

		assertThat(parked.hasOrder()).isFalse();
		assertThat(parked.hasFinishedOrder()).isFalse();
		assertThat(parked.isAvailable()).isTrue();
	}

	@Test
	void availabilityIgnoresDrivingSoAReturningRobotCanBeDispatched() {
		// 대기 슬롯으로 복귀 중: 주행하지만 진행 중인 주문은 없다. 배정 가능해야 한다.
		RobotStateMessage returning = RobotStates.of("AMR-005", "M-9", true, false, 70.0, List.of());

		assertThat(returning.driving()).isTrue();
		assertThat(returning.remainingNodes()).isZero();
		assertThat(returning.isAvailable()).isTrue();
	}

	@Test
	void availabilityExcludesChargingAndRobotsWithRemainingNodes() {
		RobotStateMessage charging = RobotStates.of("AMR-003", "", false, true, 10.0, List.of());
		RobotStateMessage enRoute = RobotStates.of("AMR-004", "M-8", true, false, 80.0,
				List.of(new RobotStateMessage.NodeState("D01", 2, true)));

		assertThat(charging.isAvailable()).isFalse();
		assertThat(enRoute.isAvailable()).isFalse();
	}
}
