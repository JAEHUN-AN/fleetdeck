package com.fleetdeck.robot;

import java.util.List;

/** 테스트용 RobotStateMessage 빌더. 생성자 인자가 많아 테스트마다 반복하지 않도록 모았다. */
public final class RobotStates {

	private RobotStates() {
	}

	public static RobotStateMessage of(String serial, String orderId, boolean driving, boolean charging,
			double battery, List<RobotStateMessage.NodeState> nodeStates) {
		return new RobotStateMessage(
				1L, "2026-09-04T02:00:00.000Z", "2.0.0", "fleetdeck", serial, orderId, null,
				driving, false,
				new RobotStateMessage.AgvPosition(0.0, 0.0, 0.0, "warehouse-a"),
				new RobotStateMessage.BatteryState(battery, charging),
				"AUTOMATIC", nodeStates, List.of());
	}

	public static RobotStateMessage idle(String serial, double battery) {
		return of(serial, "", false, false, battery, List.of());
	}

	public static RobotStateMessage withError(String serial, String orderId) {
		return new RobotStateMessage(
				1L, "2026-09-04T02:00:00.000Z", "2.0.0", "fleetdeck", serial, orderId, null,
				false, false,
				new RobotStateMessage.AgvPosition(0.0, 0.0, 0.0, "warehouse-a"),
				new RobotStateMessage.BatteryState(50.0, false),
				"AUTOMATIC", List.of(),
				List.of(new RobotStateMessage.ErrorEntry("motorOverload", "FATAL", "구동부 과부하")));
	}
}
