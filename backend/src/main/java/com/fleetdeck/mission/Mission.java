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

	/**
	 * PENDING  배정 대기 (유휴 로봇이 없을 때)
	 * ASSIGNED 로봇에 배정, order 발행 완료. 로봇이 아직 출발 전
	 * RUNNING  로봇이 주행 중 (state 의 nodeStates 가 남아 있음)
	 * DONE     로봇이 마지막 노드 도착, 주행 종료
	 * FAILED   로봇 에러 등으로 중단
	 */
	public enum MissionStatus {
		PENDING, ASSIGNED, RUNNING, DONE, FAILED;

		public boolean isTerminal() {
			return this == DONE || this == FAILED;
		}
	}

	public Mission assignedTo(String robotSerial) {
		return withStatus(MissionStatus.ASSIGNED, robotSerial);
	}

	public Mission running() {
		return withStatus(MissionStatus.RUNNING, assignedRobot);
	}

	public Mission completed() {
		return withStatus(MissionStatus.DONE, assignedRobot);
	}

	public Mission failed() {
		return withStatus(MissionStatus.FAILED, assignedRobot);
	}

	/** 배정을 회수해 다시 배정 대기로 되돌린다. 로봇이 order 를 못 받았거나 응답이 끊긴 경우. */
	public Mission reclaimed() {
		return withStatus(MissionStatus.PENDING, null);
	}

	private Mission withStatus(MissionStatus next, String robotSerial) {
		return new Mission(id, type, fromNode, toNode, next, robotSerial, sourceRef,
				createdAt, OffsetDateTime.now());
	}

	/** 로봇에 내려보내는 VDA5050 orderId. 미션 ID 와 1:1. */
	public String orderId() {
		return "M-" + id;
	}

	public static Long idFromOrderId(String orderId) {
		if (orderId == null || !orderId.startsWith("M-")) {
			return null;
		}
		try {
			return Long.valueOf(orderId.substring(2));
		}
		catch (NumberFormatException e) {
			return null;
		}
	}
}
