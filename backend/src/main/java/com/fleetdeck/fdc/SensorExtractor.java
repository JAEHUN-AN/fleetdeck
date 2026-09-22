package com.fleetdeck.fdc;

import com.fleetdeck.equipment.EquipmentSource;
import com.fleetdeck.equipment.EquipmentStateMessage;
import com.fleetdeck.telemetry.TelemetryRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 설비 상태 메시지에서 센서 표본을 뽑아낸다.
 *
 * <p>MQTT 는 페이로드의 {@code sensors} 맵으로 온다. OPC UA 쪽은 변수 이름이 프로토콜
 * 지식이라 {@code opcua} 패키지가 직접 골라 넘긴다 - 여기로 끌어오면 상태 변수 목록이
 * 두 곳에 흩어진다.
 */
public final class SensorExtractor {

	private SensorExtractor() {
	}

	public static List<SensorSample> fromEquipmentState(EquipmentStateMessage state,
			EquipmentSource source) {
		Map<String, Double> sensors = state.sensors();
		if (sensors == null || sensors.isEmpty()) {
			return List.of();
		}
		OffsetDateTime at = TelemetryRepository.parseTimestamp(state.timestamp());
		List<SensorSample> samples = new ArrayList<>(sensors.size());
		sensors.forEach((channel, value) -> {
			if (channel != null && !channel.isBlank() && value != null && !value.isNaN()) {
				samples.add(new SensorSample(state.equipmentId(), channel, value, at, source));
			}
		});
		return samples;
	}
}
