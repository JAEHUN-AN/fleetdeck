package com.fleetdeck.equipment;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Map;

/**
 * 소터·컨베이어 등 고정 설비 상태. 시뮬레이터의 fleetdeck/equipment/{id}/state 페이로드.
 *
 * @param sensors 연속 센서 채널의 현재값. 없을 수 있다(센서를 안 내보내는 설비).
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
		String timestamp,
		Map<String, Double> sensors) {

	/** 센서 없는 설비용. 기존 호출부와 테스트가 그대로 읽히도록 남긴다. */
	public EquipmentStateMessage(String equipmentId, String equipmentType, String status,
			Integer throughputPerMin, String alarmCode, Double x, Double y, String timestamp) {
		this(equipmentId, equipmentType, status, throughputPerMin, alarmCode, x, y, timestamp, null);
	}

	public boolean isAlarm() {
		return "ALARM".equals(status);
	}
}
