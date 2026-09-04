package com.fleetdeck.mission;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MissionRepository {

	private static final int LIST_LIMIT = 100;

	private static final String COLUMNS =
			"id, mission_type, from_node, to_node, status, assigned_robot, source_ref, created_at, updated_at";

	// 텍스트 블록은 각 줄 끝 공백을 지우므로 RETURNING 뒤 구분자를 직접 넣는다.
	private static final String INSERT = """
			INSERT INTO mission (mission_type, from_node, to_node, status, source_ref)
			VALUES (?, ?, ?, 'PENDING', ?)
			RETURNING
			""" + COLUMNS;

	private static final String SELECT_RECENT =
			"SELECT " + COLUMNS + " FROM mission ORDER BY created_at DESC LIMIT ?";

	private static final String SELECT_BY_ID =
			"SELECT " + COLUMNS + " FROM mission WHERE id = ?";

	private static final String SELECT_PENDING =
			"SELECT " + COLUMNS + " FROM mission WHERE status = 'PENDING' ORDER BY created_at LIMIT ?";

	private static final String COUNT_OPEN =
			"SELECT count(*) FROM mission WHERE status IN ('PENDING', 'ASSIGNED', 'RUNNING')";

	private static final String UPDATE_STATUS = """
			UPDATE mission SET status = ?, assigned_robot = ?, updated_at = now() WHERE id = ?
			""";

	private static final RowMapper<Mission> ROW_MAPPER = MissionRepository::mapRow;

	private final JdbcTemplate jdbc;

	public MissionRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public Mission insert(MissionRequest req) {
		return jdbc.queryForObject(INSERT, ROW_MAPPER,
				req.type().name(), req.fromNode(), req.toNode(), req.sourceRef());
	}

	public List<Mission> findRecent() {
		return jdbc.query(SELECT_RECENT, ROW_MAPPER, LIST_LIMIT);
	}

	public Optional<Mission> findById(long id) {
		return jdbc.query(SELECT_BY_ID, ROW_MAPPER, id).stream().findFirst();
	}

	public List<Mission> findPending(int limit) {
		return jdbc.query(SELECT_PENDING, ROW_MAPPER, limit);
	}

	public int countOpen() {
		Integer n = jdbc.queryForObject(COUNT_OPEN, Integer.class);
		return n == null ? 0 : n;
	}

	public void updateStatus(Mission mission) {
		jdbc.update(UPDATE_STATUS, mission.status().name(), mission.assignedRobot(), mission.id());
	}

	private static Mission mapRow(ResultSet rs, int rowNum) throws SQLException {
		return new Mission(
				rs.getLong("id"),
				Mission.MissionType.valueOf(rs.getString("mission_type")),
				rs.getString("from_node"),
				rs.getString("to_node"),
				Mission.MissionStatus.valueOf(rs.getString("status")),
				rs.getString("assigned_robot"),
				rs.getString("source_ref"),
				rs.getObject("created_at", OffsetDateTime.class),
				rs.getObject("updated_at", OffsetDateTime.class));
	}
}
