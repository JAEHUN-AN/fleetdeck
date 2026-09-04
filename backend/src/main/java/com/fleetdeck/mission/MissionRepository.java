package com.fleetdeck.mission;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class MissionRepository {

	private static final int LIST_LIMIT = 100;

	private static final String INSERT = """
			INSERT INTO mission (mission_type, from_node, to_node, status, source_ref)
			VALUES (?, ?, ?, 'PENDING', ?)
			RETURNING id, mission_type, from_node, to_node, status, assigned_robot, source_ref, created_at, updated_at
			""";

	private static final String SELECT_RECENT = """
			SELECT id, mission_type, from_node, to_node, status, assigned_robot, source_ref, created_at, updated_at
			FROM mission ORDER BY created_at DESC LIMIT ?
			""";

	private static final String UPDATE_ASSIGNMENT = """
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

	public void updateAssignment(Mission mission) {
		jdbc.update(UPDATE_ASSIGNMENT, mission.status().name(), mission.assignedRobot(), mission.id());
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
