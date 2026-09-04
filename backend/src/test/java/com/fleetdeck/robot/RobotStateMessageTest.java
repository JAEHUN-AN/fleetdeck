package com.fleetdeck.robot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class RobotStateMessageTest {

	private final ObjectMapper mapper = new ObjectMapper();

	// 시뮬레이터가 실제로 보내는 형태. positionInitialized, velocity 등 모르는 필드 포함.
	private static final String SAMPLE = """
			{
			  "headerId": 42, "timestamp": "2026-09-02T08:00:00.000Z", "version": "2.0.0",
			  "manufacturer": "fleetdeck", "serialNumber": "AMR-001", "orderId": "ORD-1",
			  "orderUpdateId": 0, "lastNodeId": "N07", "lastNodeSequenceId": 0,
			  "nodeStates": [], "edgeStates": [], "driving": true, "paused": false,
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
		RobotStateMessage m = mapper.readValue(SAMPLE, RobotStateMessage.class);

		assertThat(m.serialNumber()).isEqualTo("AMR-001");
		assertThat(m.agvPosition().x()).isEqualTo(1.235);
		assertThat(m.agvPosition().mapId()).isEqualTo("warehouse-a");
		assertThat(m.batteryCharge()).isEqualTo(77.8);
		assertThat(m.driving()).isTrue();
		assertThat(m.isIdle()).isFalse();
		assertThat(m.errorCount()).isZero();
	}

	@Test
	void isIdleRequiresNotDrivingNotPausedNotCharging() {
		RobotStateMessage idle = new RobotStateMessage(1, null, null, "fleetdeck", "AMR-002", null, null,
				false, false, null, new RobotStateMessage.BatteryState(50.0, false), "AUTOMATIC", null);
		RobotStateMessage charging = new RobotStateMessage(1, null, null, "fleetdeck", "AMR-003", null, null,
				false, false, null, new RobotStateMessage.BatteryState(10.0, true), "AUTOMATIC", null);

		assertThat(idle.isIdle()).isTrue();
		assertThat(charging.isIdle()).isFalse();
	}
}
