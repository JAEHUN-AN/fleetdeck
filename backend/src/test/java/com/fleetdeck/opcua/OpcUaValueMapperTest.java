package com.fleetdeck.opcua;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetdeck.equipment.EquipmentStateMessage;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * OPC UA 변수값 묶음 -> EquipmentStateMessage 매핑.
 * 실제 서버에서 확인한 Variant 타입을 그대로 재현한다
 * (ThroughputPerMin 은 Int64 로 온다 — docs/021-opcua.md 참고).
 */
class OpcUaValueMapperTest {

	private static Map<String, Object> running() {
		Map<String, Object> values = new HashMap<>();
		values.put("EquipmentType", "TILT_TRAY_SORTER");
		values.put("Status", "RUNNING");
		values.put("AlarmCode", "");
		values.put("ThroughputPerMin", 1800L);
		values.put("PositionX", 8.0);
		values.put("PositionY", 17.0);
		return values;
	}

	@Test
	void mapsEveryField() {
		Instant ts = Instant.parse("2026-09-22T01:02:03Z");
		EquipmentStateMessage m = OpcUaValueMapper.toMessage("UA-SORTER-01", running(), ts);

		assertThat(m.equipmentId()).isEqualTo("UA-SORTER-01");
		assertThat(m.equipmentType()).isEqualTo("TILT_TRAY_SORTER");
		assertThat(m.status()).isEqualTo("RUNNING");
		assertThat(m.throughputPerMin()).isEqualTo(1800);
		assertThat(m.x()).isEqualTo(8.0);
		assertThat(m.y()).isEqualTo(17.0);
		// MQTT 경로(시뮬레이터 isoformat milliseconds)와 같은 모양이어야 한 테이블에서 섞여도 읽힌다.
		assertThat(m.timestamp()).isEqualTo("2026-09-22T01:02:03.000Z");
	}

	@Test
	void int64ThroughputBecomesInteger() {
		// 서버가 Int64 로 실어 보낸다. Integer 캐스팅으로 받으면 ClassCastException 이 난다.
		assertThat(OpcUaValueMapper.toMessage("E", running(), Instant.EPOCH).throughputPerMin())
				.isEqualTo(1800);
	}

	@Test
	void emptyAlarmCodeBecomesNull() {
		// OPC UA 스칼라는 타입 고정이라 빈 문자열로 온다. DB·화면에서는 알람 없음이어야 한다.
		assertThat(OpcUaValueMapper.toMessage("E", running(), Instant.EPOCH).alarmCode()).isNull();
	}

	@Test
	void keepsAlarmCodeWhenPresent() {
		Map<String, Object> values = running();
		values.put("Status", "ALARM");
		values.put("AlarmCode", "E101_JAM");
		values.put("ThroughputPerMin", 0L);

		EquipmentStateMessage m = OpcUaValueMapper.toMessage("E", values, Instant.EPOCH);
		assertThat(m.alarmCode()).isEqualTo("E101_JAM");
		assertThat(m.isAlarm()).isTrue();
		assertThat(m.throughputPerMin()).isZero();
	}

	@Test
	void missingValuesDoNotThrow() {
		// 서버가 아직 한 번도 안 쓴 변수, 혹은 BadNodeIdUnknown 으로 빠진 변수.
		EquipmentStateMessage m = OpcUaValueMapper.toMessage("E", Map.of(), Instant.EPOCH);
		assertThat(m.equipmentId()).isEqualTo("E");
		assertThat(m.status()).isNull();
		assertThat(m.throughputPerMin()).isNull();
	}

	@Test
	void isCompleteRequiresStatus() {
		// 상태 없이 좌표만 온 부분 스냅샷은 적재하지 않는다.
		assertThat(OpcUaValueMapper.isComplete(Map.of("PositionX", 1.0))).isFalse();
		assertThat(OpcUaValueMapper.isComplete(running())).isTrue();
	}

	@Test
	void nullTimestampFallsBackToNull() {
		assertThat(OpcUaValueMapper.toMessage("E", running(), null).timestamp()).isNull();
	}
}
