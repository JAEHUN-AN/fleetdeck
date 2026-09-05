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
		int retryCount,
		OffsetDateTime createdAt,
		OffsetDateTime updatedAt) {

	public enum MissionType { TRANSPORT, CHARGE, PARK }

	/**
	 * PENDING  배정 대기 (유휴 로봇이 없거나, 회수되어 다시 대기)
	 * ASSIGNED 로봇에 배정, order 발행 완료. 로봇이 아직 출발 전
	 * RUNNING  로봇이 주행 중 (state 의 nodeStates 가 남아 있음)
	 * DONE     로봇이 마지막 노드 도착, 주행 종료
	 * FAILED   로봇 에러 또는 재시도 상한 초과로 포기
	 */
	public enum MissionStatus {
		PENDING, ASSIGNED, RUNNING, DONE, FAILED;

		public boolean isTerminal() {
			return this == DONE || this == FAILED;
		}
	}

	public Mission assignedTo(String robotSerial) {
		return with(MissionStatus.ASSIGNED, robotSerial, retryCount);
	}

	public Mission running() {
		return with(MissionStatus.RUNNING, assignedRobot, retryCount);
	}

	public Mission completed() {
		return with(MissionStatus.DONE, assignedRobot, retryCount);
	}

	public Mission failed() {
		return with(MissionStatus.FAILED, assignedRobot, retryCount);
	}

	/**
	 * 배정을 회수한다. 로봇이 order 를 못 받았거나 응답이 끊긴 경우.
	 * 재시도 상한을 넘기면 무한 재배정 대신 FAILED 로 포기한다.
	 */
	public Mission reclaimed(int maxRetries) {
		int next = retryCount + 1;
		MissionStatus status = next > maxRetries ? MissionStatus.FAILED : MissionStatus.PENDING;
		return with(status, null, next);
	}

	public boolean isGivenUp(int maxRetries) {
		return status == MissionStatus.FAILED && retryCount > maxRetries;
	}

	private Mission with(MissionStatus nextStatus, String robotSerial, int nextRetryCount) {
		return new Mission(id, type, fromNode, toNode, nextStatus, robotSerial, sourceRef,
				nextRetryCount, createdAt, OffsetDateTime.now());
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
