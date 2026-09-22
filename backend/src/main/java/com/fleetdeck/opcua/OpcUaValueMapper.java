package com.fleetdeck.opcua;

import com.fleetdeck.equipment.EquipmentStateMessage;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * OPC UA 변수값 묶음을 설비 상태 메시지로 옮긴다.
 *
 * <p>프로토콜이 끝나는 지점이다. 여기를 지나면 MQTT 로 들어온 설비와 구분이 없어지고
 * 같은 레지스트리·같은 테이블·같은 WebSocket 토픽을 탄다.
 *
 * <p>값의 시각은 변수로 받지 않고 OPC UA DataValue 의 sourceTimestamp 를 쓴다.
 * 장비가 값을 만든 시각이라 수집 시각보다 정확하다.
 */
public final class OpcUaValueMapper {

	/** 시뮬레이터 주소공간의 변수 이름. 구독·폴링 양쪽이 같은 목록을 쓴다. */
	public static final java.util.List<String> VARIABLES = java.util.List.of(
			"EquipmentType", "Status", "AlarmCode", "ThroughputPerMin", "PositionX", "PositionY");

	private static final DateTimeFormatter ISO_MILLIS = DateTimeFormatter
			.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	private OpcUaValueMapper() {
	}

	/**
	 * 적재해도 되는 스냅샷인가. 구독은 변수별로 따로 도착하므로 처음 몇 번은
	 * 일부만 찬 상태로 불린다. 상태값이 없으면 설비 상태라고 할 수 없다.
	 */
	/**
	 * 상태 변수가 아닌 수치 변수를 센서 채널로 본다.
	 *
	 * <p>채널 이름을 코드에 박지 않는다. 설비가 채널을 늘려도 브라우징이 찾아오고
	 * 여기서 자동으로 통과하므로 백엔드를 고칠 일이 없다.
	 */
	public static Map<String, Double> sensorChannels(Map<String, Object> values) {
		Map<String, Double> channels = new java.util.LinkedHashMap<>();
		values.forEach((name, raw) -> {
			if (!VARIABLES.contains(name) && raw instanceof Number number
					&& !Double.isNaN(number.doubleValue())) {
				channels.put(name, number.doubleValue());
			}
		});
		return channels;
	}

	public static boolean isComplete(Map<String, Object> values) {
		Object status = values.get("Status");
		return status instanceof String s && !s.isBlank();
	}

	public static EquipmentStateMessage toMessage(String equipmentId, Map<String, Object> values,
			Instant sourceTime) {
		return new EquipmentStateMessage(
				equipmentId,
				string(values.get("EquipmentType")),
				string(values.get("Status")),
				integer(values.get("ThroughputPerMin")),
				string(values.get("AlarmCode")),
				decimal(values.get("PositionX")),
				decimal(values.get("PositionY")),
				sourceTime == null ? null : ISO_MILLIS.format(sourceTime));
	}

	/** 빈 문자열은 "값 없음"이다. OPC UA 스칼라 변수는 타입이 고정이라 null 을 실을 수 없다. */
	private static String string(Object raw) {
		if (raw instanceof String s) {
			return s.isEmpty() ? null : s;
		}
		return raw == null ? null : String.valueOf(raw);
	}

	/** 서버가 Int64 로 실어 보낸다. Integer 로 바로 캐스팅하면 깨진다. */
	private static Integer integer(Object raw) {
		return raw instanceof Number n ? n.intValue() : null;
	}

	private static Double decimal(Object raw) {
		return raw instanceof Number n ? n.doubleValue() : null;
	}
}
