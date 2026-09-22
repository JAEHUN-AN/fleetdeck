package com.fleetdeck.fdc;

import com.fleetdeck.telemetry.TelemetryRepository;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 센서값·경보·주입 라벨 적재.
 *
 * <p>센서값은 초당 (설비 × 채널) 만큼 들어오므로 배치로 넣는다. 경보와 라벨은 드물어서
 * 건건이 넣는다.
 */
@Repository
public class SensorRepository {

	private static final String INSERT_SENSOR = """
			INSERT INTO equipment_sensor_log (ts, equipment_id, channel, value, source)
			VALUES (?, ?, ?, ?, ?)
			""";

	private static final String INSERT_ALARM = """
			INSERT INTO sensor_alarm_log (ts, equipment_id, channel, detector, value, score)
			VALUES (?, ?, ?, ?, ?, ?)
			""";

	private static final String RECENT_ALARMS = """
			SELECT ts, equipment_id, channel, detector, value, score
			FROM sensor_alarm_log
			ORDER BY ts DESC, id DESC
			LIMIT ?
			""";

	private static final String INSERT_FAULT = """
			INSERT INTO fault_injection_log (ts, equipment_id, channel, kind, magnitude, phase)
			VALUES (?, ?, ?, ?, ?, ?)
			""";

	private final JdbcTemplate jdbc;

	public SensorRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public void insertSamples(List<SensorSample> samples) {
		if (samples.isEmpty()) {
			return;
		}
		jdbc.batchUpdate(INSERT_SENSOR, samples, samples.size(), (ps, sample) -> {
			ps.setObject(1, sample.at());
			ps.setString(2, sample.equipmentId());
			ps.setString(3, sample.channel());
			ps.setDouble(4, sample.value());
			ps.setString(5, sample.source().name());
		});
	}

	public void insertAlarm(SensorSample sample, ChannelMonitor.Alarm alarm) {
		jdbc.update(INSERT_ALARM, sample.at(), sample.equipmentId(), sample.channel(),
				alarm.detector(), alarm.value(), alarm.score());
	}

	public List<SensorTelemetryService.AlarmView> recentAlarms(int limit) {
		return jdbc.query(RECENT_ALARMS, (rs, rowNum) -> new SensorTelemetryService.AlarmView(
				rs.getString("equipment_id"),
				rs.getString("channel"),
				rs.getString("detector"),
				rs.getDouble("value"),
				rs.getDouble("score"),
				rs.getObject("ts", java.time.OffsetDateTime.class)), limit);
	}

	public void insertFaultInjection(FaultInjectionMessage m) {
		jdbc.update(INSERT_FAULT, TelemetryRepository.parseTimestamp(m.timestamp()),
				m.equipmentId(), m.channel(), m.kind(), m.magnitude(), m.phase());
	}
}
