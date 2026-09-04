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
		List<ErrorEntry> errors) {

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AgvPosition(double x, double y, double theta, String mapId) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record BatteryState(double batteryCharge, boolean charging) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ErrorEntry(String errorType, String errorLevel, String errorDescription) {
	}

	public int errorCount() {
		return errors == null ? 0 : errors.size();
	}

	public boolean isCharging() {
		return batteryState != null && batteryState.charging();
	}

	public double batteryCharge() {
		return batteryState == null ? 0.0 : batteryState.batteryCharge();
	}

	public boolean isIdle() {
		return !driving && !paused && !isCharging();
	}
}
