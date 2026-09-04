package com.fleetdeck.equipment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 소터·컨베이어 등 고정 설비 상태. 시뮬레이터의 fleetdeck/equipment/{id}/state 페이로드.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EquipmentStateMessage(
		String equipmentId,
		String equipmentType,
		String status,
		Integer throughputPerMin,
		String alarmCode,
		Double x,
		Double y,
		String timestamp) {

	public boolean isAlarm() {
		return "ALARM".equals(status);
	}
}
