package com.fleetdeck.integration;

/** 시뮬레이터가 실제로 보내는 형태의 합성 VDA5050 메시지. */
final class Vda5050Fixtures {

	private Vda5050Fixtures() {
	}

	static String stateTopic(String serial) {
		return "uagv/v2/fleetdeck/" + serial + "/state";
	}

	static String orderTopicFilter() {
		return "uagv/v2/+/+/order";
	}

	static String equipmentTopic(String equipmentId) {
		return "fleetdeck/equipment/" + equipmentId + "/state";
	}

	/** 대기 중인 로봇. nodeStates 가 비어 있어 배정 가능하다. */
	static String parkedState(String serial, long headerId, double battery) {
		return state(serial, headerId, battery, "", false, "");
	}

	/** 주문 수행 중. 남은 노드가 있어 배정 대상에서 빠진다. */
	static String executingState(String serial, long headerId, String orderId, String remainingNode) {
		return state(serial, headerId, 80.0, orderId, true, """
				{"nodeId": "%s", "sequenceId": 2, "released": true,
				 "nodePosition": {"x": 17.0, "y": 19.0, "mapId": "warehouse-a"}}
				""".formatted(remainingNode));
	}

	/** 마지막 노드 도착. nodeStates 가 비고 driving=false 이면 order 완료. */
	static String finishedState(String serial, long headerId, String orderId) {
		return state(serial, headerId, 78.0, orderId, false, "");
	}

	private static String state(String serial, long headerId, double battery, String orderId,
			boolean driving, String nodeStateJson) {
		return """
				{
				  "headerId": %d, "timestamp": "2026-09-05T12:00:00.000Z", "version": "2.0.0",
				  "manufacturer": "fleetdeck", "serialNumber": "%s", "orderId": "%s",
				  "orderUpdateId": 0, "lastNodeId": "P01", "lastNodeSequenceId": 0,
				  "nodeStates": [%s], "edgeStates": [],
				  "driving": %b, "paused": false,
				  "agvPosition": {"x": 4.0, "y": 6.0, "theta": 0.0,
				                  "mapId": "warehouse-a", "positionInitialized": true},
				  "velocity": {"vx": 0.0, "vy": 0.0, "omega": 0.0},
				  "loads": [], "actionStates": [],
				  "batteryState": {"batteryCharge": %.1f, "charging": false},
				  "operatingMode": "AUTOMATIC", "errors": [],
				  "safetyState": {"eStop": "NONE", "fieldViolation": false}
				}
				""".formatted(headerId, serial, orderId, nodeStateJson, driving, battery);
	}

	static String sorterState(String equipmentId, String status, int throughput) {
		return """
				{
				  "equipmentId": "%s", "equipmentType": "WHEEL_SORTER", "status": "%s",
				  "throughputPerMin": %d, "alarmCode": null, "x": 5.0, "y": 22.0,
				  "timestamp": "2026-09-05T12:00:00.000Z"
				}
				""".formatted(equipmentId, status, throughput);
	}
}
