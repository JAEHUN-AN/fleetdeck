package com.fleetdeck.robot;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * VDA5050 v2 state 메시지 중 관제에 필요한 부분. 모르는 필드는 무시한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RobotStateMessage(
		long headerId,
		String timestamp,
		String version,
		String manufacturer,
		String serialNumber,
		String orderId,
		String lastNodeId,
		boolean driving,
		boolean paused,
		AgvPosition agvPosition,
		BatteryState batteryState,
		String operatingMode,
		List<NodeState> nodeStates,
		List<ErrorEntry> errors) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AgvPosition(double x, double y, double theta, String mapId) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record BatteryState(double batteryCharge, boolean charging) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record NodeState(String nodeId, long sequenceId, boolean released) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ErrorEntry(String errorType, String errorLevel, String errorDescription) {
	}

	public int errorCount() {
		return errors == null ? 0 : errors.size();
	}

	public int remainingNodes() {
		return nodeStates == null ? 0 : nodeStates.size();
	}

	public boolean isCharging() {
		return batteryState != null && batteryState.charging();
	}

	public double batteryCharge() {
		return batteryState == null ? 0.0 : batteryState.batteryCharge();
	}

	public boolean hasOrder() {
		return orderId != null && !orderId.isBlank();
	}

	/** 주문 수행 중: 남은 노드가 있거나 주행 중. */
	public boolean isExecutingOrder() {
		return hasOrder() && (remainingNodes() > 0 || driving);
	}

	/**
	 * 주문 완료: orderId 는 있는데 남은 노드가 없고 정지 상태.
	 * VDA5050 은 nodeStates/actionStates 가 비고 driving=false 일 때 order 완료로 본다.
	 */
	public boolean hasFinishedOrder() {
		return hasOrder() && remainingNodes() == 0 && !driving;
	}

	/**
	 * 새 주문을 받을 수 있는 상태인가.
	 *
	 * driving 은 보지 않는다. 로봇은 주문이 없을 때 대기 슬롯으로 복귀하며 주행하는데,
	 * 그 상태에서도 즉시 배정받아 방향을 틀 수 있어야 한다.
	 * 진행 중인 주문의 유무는 nodeStates 가 말해준다 — 주문 수행 중에는 항상 차 있다.
	 */
	public boolean isAvailable() {
		return !paused && !isCharging() && remainingNodes() == 0;
	}
}
