package com.fleetdeck.telemetry;

import com.fleetdeck.equipment.EquipmentSource;
import com.fleetdeck.equipment.EquipmentStateMessage;
import com.fleetdeck.robot.RobotStateMessage;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 텔레메트리 히스토리를 TimescaleDB 하이퍼테이블에 적재한다.
 */
@Repository
public class TelemetryRepository {

	private static final String INSERT_ROBOT = """
			INSERT INTO robot_state_log
			  (ts, serial_number, manufacturer, x, y, theta, battery_charge, driving,
			   operating_mode, order_id, error_count, payload)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
			""";

	private static final String INSERT_EQUIPMENT = """
			INSERT INTO equipment_state_log
			  (ts, equipment_id, equipment_type, status, throughput_per_min, alarm_code, payload, source)
			VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)
			""";

	private final JdbcTemplate jdbc;

	public TelemetryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insertRobotState(RobotStateMessage m, String rawJson) {
		RobotStateMessage.AgvPosition pos = m.agvPosition();
		RobotStateMessage.BatteryState bat = m.batteryState();
		jdbc.update(INSERT_ROBOT,
				parseTimestamp(m.timestamp()),
				m.serialNumber(),
				m.manufacturer(),
				pos == null ? null : pos.x(),
				pos == null ? null : pos.y(),
				pos == null ? null : pos.theta(),
				bat == null ? null : bat.batteryCharge(),
				m.driving(),
				m.operatingMode(),
				m.orderId(),
				m.errorCount(),
				rawJson);
	}

	public void insertEquipmentState(EquipmentStateMessage m, String rawJson, EquipmentSource source) {
		jdbc.update(INSERT_EQUIPMENT,
				parseTimestamp(m.timestamp()),
				m.equipmentId(),
				m.equipmentType(),
				m.status(),
				m.throughputPerMin(),
				m.alarmCode(),
				rawJson,
				source.name());
	}

	/** 페이로드의 ISO 시각. 없거나 깨졌으면 수집 시각으로 대체한다. */
	public static OffsetDateTime parseTimestamp(String iso) {
		if (iso == null || iso.isBlank()) {
			return OffsetDateTime.now();
		}
		try {
			return OffsetDateTime.parse(iso);
		}
		catch (DateTimeParseException e) {
			return OffsetDateTime.now();
		}
	}
}
