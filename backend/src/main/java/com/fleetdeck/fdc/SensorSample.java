package com.fleetdeck.fdc;

import com.fleetdeck.equipment.EquipmentSource;
import java.time.OffsetDateTime;

/**
 * 센서 측정값 한 점. 프로토콜과 무관한 형태다.
 *
 * @param at 장비가 값을 만든 시각. OPC UA 는 sourceTimestamp, MQTT 는 페이로드의 timestamp.
 */
public record SensorSample(String equipmentId, String channel, double value, OffsetDateTime at,
		EquipmentSource source) {
}
