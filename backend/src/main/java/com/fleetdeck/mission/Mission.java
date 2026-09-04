package com.fleetdeck.mission;

import java.time.OffsetDateTime;

public record Mission(
		Long id,
		MissionType type,
		String fromNode,
		String toNode,
		MissionStatus status,
		String assignedRobot,
		String sourceRef,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	public enum MissionType { TRANSPORT, CHARGE, PARK }

	public enum MissionStatus { PENDING, ASSIGNED, RUNNING, DONE, FAILED }

	public Mission assignedTo(String robotSerial) {
		return new Mission(id, type, fromNode, toNode, MissionStatus.ASSIGNED, robotSerial, sourceRef,
				createdAt, OffsetDateTime.now());
	}

	public String orderId() {
		return "M-" + id;
	}
}
