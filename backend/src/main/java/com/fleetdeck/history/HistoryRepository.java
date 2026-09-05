package com.fleetdeck.history;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 텔레메트리 시계열 조회.
 *
 * 원본은 초당 수십 행씩 쌓이므로 그대로 내려보내면 화면이 감당하지 못한다.
 * TimescaleDB 의 time_bucket 으로 구간 평균을 내서 점 개수를 일정하게 유지한다.
 * 하이퍼테이블을 쓰는 이유가 여기서 드러난다.
 */
@Repository
public class HistoryRepository {

	private static final String ROBOT_BATTERY = """
			SELECT time_bucket(CAST(? AS interval), ts) AS bucket,
			       serial_number AS series,
			       avg(battery_charge) AS value
			FROM robot_state_log
			WHERE ts > now() - CAST(? AS interval) AND battery_charge IS NOT NULL
			GROUP BY bucket, series
			ORDER BY bucket
			""";

	private static final String EQUIPMENT_THROUGHPUT = """
			SELECT time_bucket(CAST(? AS interval), ts) AS bucket,
			       equipment_id AS series,
			       avg(throughput_per_min) AS value
			FROM equipment_state_log
			WHERE ts > now() - CAST(? AS interval) AND throughput_per_min IS NOT NULL
			GROUP BY bucket, series
			ORDER BY bucket
			""";

	/** 전체 플릿의 주행 비율(%). 가동률을 한 줄로 보여준다. */
	private static final String FLEET_DRIVING_RATIO = """
			SELECT time_bucket(CAST(? AS interval), ts) AS bucket,
			       'fleet' AS series,
			       100.0 * count(*) FILTER (WHERE driving) / NULLIF(count(*), 0) AS value
			FROM robot_state_log
			WHERE ts > now() - CAST(? AS interval)
			GROUP BY bucket
			ORDER BY bucket
			""";

	private static final RowMapper<HistoryPoint> ROW_MAPPER = HistoryRepository::mapRow;

	private final JdbcTemplate jdbc;

	public HistoryRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public List<HistoryPoint> robotBattery(Duration window, Duration bucket) {
		return query(ROBOT_BATTERY, window, bucket);
	}

	public List<HistoryPoint> equipmentThroughput(Duration window, Duration bucket) {
		return query(EQUIPMENT_THROUGHPUT, window, bucket);
	}

	public List<HistoryPoint> fleetDrivingRatio(Duration window, Duration bucket) {
		return query(FLEET_DRIVING_RATIO, window, bucket);
	}

	private List<HistoryPoint> query(String sql, Duration window, Duration bucket) {
		return jdbc.query(sql, ROW_MAPPER, toInterval(bucket), toInterval(window));
	}

	/** Duration 을 postgres interval 문자열로. */
	static String toInterval(Duration duration) {
		return duration.toSeconds() + " seconds";
	}

	private static HistoryPoint mapRow(ResultSet rs, int rowNum) throws SQLException {
		double value = rs.getDouble("value");
		return new HistoryPoint(
				rs.getObject("bucket", OffsetDateTime.class),
				rs.getString("series"),
				rs.wasNull() ? 0.0 : value);
	}
}
