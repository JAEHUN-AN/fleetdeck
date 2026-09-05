package com.fleetdeck.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fleetdeck.equipment.EquipmentRegistry;
import com.fleetdeck.robot.RobotRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MQTT 로 들어온 텔레메트리가 레지스트리·DB 까지 실제로 도달하는지 검증한다.
 * 단위 테스트는 파싱과 판정만 보므로 이 경로 전체는 여기서만 확인된다.
 */
class TelemetryPipelineIT extends IntegrationTestBase {

	private static final Duration TIMEOUT = Duration.ofSeconds(15);

	@Autowired
	private RobotRegistry robots;

	@Autowired
	private EquipmentRegistry equipment;

	@Autowired
	private JdbcTemplate jdbc;

	private TestMqtt robot;

	@BeforeEach
	void connect() throws Exception {
		robot = new TestMqtt(mqttUrl(), "it-telemetry-" + System.nanoTime());
	}

	@AfterEach
	void disconnect() throws Exception {
		robot.close();
	}

	@Test
	@DisplayName("로봇 state 가 레지스트리와 TimescaleDB 에 모두 도달한다")
	void robotStateReachesRegistryAndDatabase() throws Exception {
		String serial = "AMR-901";

		robot.publish(Vda5050Fixtures.stateTopic(serial), Vda5050Fixtures.parkedState(serial, 1, 88.0));

		await().atMost(TIMEOUT).untilAsserted(() -> {
			assertThat(robots.find(serial)).isPresent()
					.hasValueSatisfying(r -> assertThat(r.batteryCharge()).isEqualTo(88.0));

			Integer rows = jdbc.queryForObject(
					"SELECT count(*) FROM robot_state_log WHERE serial_number = ?", Integer.class, serial);
			assertThat(rows).isPositive();
		});
	}

	@Test
	@DisplayName("적재된 행에 요약 컬럼과 원문 payload 가 함께 들어간다")
	void persistedRowKeepsSummaryColumnsAndRawPayload() throws Exception {
		String serial = "AMR-902";

		robot.publish(Vda5050Fixtures.stateTopic(serial), Vda5050Fixtures.parkedState(serial, 7, 64.0));

		await().atMost(TIMEOUT).untilAsserted(() -> {
			var row = jdbc.queryForMap("""
					SELECT battery_charge, driving, x, y, payload->>'headerId' AS header_id
					FROM robot_state_log WHERE serial_number = ? ORDER BY ts DESC LIMIT 1
					""", serial);

			assertThat(row.get("battery_charge")).isEqualTo(64.0);
			assertThat(row.get("driving")).isEqualTo(false);
			assertThat(row.get("x")).isEqualTo(4.0);
			assertThat(row.get("header_id")).isEqualTo("7");
		});
	}

	@Test
	@DisplayName("설비 상태도 같은 경로로 흐른다")
	void equipmentStateReachesRegistryAndDatabase() throws Exception {
		String id = "SORTER-91";

		robot.publish(Vda5050Fixtures.equipmentTopic(id), Vda5050Fixtures.sorterState(id, "ALARM", 0));

		await().atMost(TIMEOUT).untilAsserted(() -> {
			assertThat(equipment.find(id)).isPresent()
					.hasValueSatisfying(e -> assertThat(e.status()).isEqualTo("ALARM"));

			Integer rows = jdbc.queryForObject(
					"SELECT count(*) FROM equipment_state_log WHERE equipment_id = ?", Integer.class, id);
			assertThat(rows).isPositive();
		});
	}

	@Test
	@DisplayName("보고가 끊긴 로봇은 오프라인으로 넘어가 배정 후보에서 빠진다")
	void silentRobotGoesOffline() throws Exception {
		String serial = "AMR-903";
		robot.publish(Vda5050Fixtures.stateTopic(serial), Vda5050Fixtures.parkedState(serial, 1, 90.0));

		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(robots.isOnline(serial, Instant.now())).isTrue());

		// offline-after 기본값 15초. 실제로 기다리는 대신 판정 시각을 앞당겨 확인한다.
		Instant later = Instant.now().plusSeconds(20);

		assertThat(robots.isOnline(serial, later)).isFalse();
		assertThat(robots.onlineStates(later)).noneMatch(r -> r.serialNumber().equals(serial));
	}

	@Test
	@DisplayName("깨진 payload 는 파이프라인을 죽이지 않고 무시된다")
	void malformedPayloadIsDroppedWithoutBreakingThePipeline() throws Exception {
		String good = "AMR-904";

		robot.publish(Vda5050Fixtures.stateTopic("AMR-BAD"), "{ this is not json");
		robot.publish(Vda5050Fixtures.stateTopic(good), Vda5050Fixtures.parkedState(good, 1, 50.0));

		// 깨진 메시지 뒤에 온 정상 메시지가 처리되면 파이프라인이 살아 있는 것이다.
		await().atMost(TIMEOUT).untilAsserted(() -> assertThat(robots.find(good)).isPresent());
		assertThat(robots.find("AMR-BAD")).isEmpty();
	}
}
