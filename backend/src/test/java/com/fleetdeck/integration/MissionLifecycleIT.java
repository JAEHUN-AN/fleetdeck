package com.fleetdeck.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetdeck.mission.Mission;
import com.fleetdeck.mission.MissionRepository;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 주문 생성부터 완료 보고까지 제어 루프 전체를 실제 MQTT 로 돌려본다.
 *
 * 여기서만 잡히는 것들: 디스패처가 실제로 order 를 발행하는지, order 에 로봇이 주행할 수
 * 있는 nodePosition 이 들어 있는지, 로봇 보고에 따라 미션이 전이되는지.
 */
class MissionLifecycleIT extends IntegrationTestBase {

	private static final Duration TIMEOUT = Duration.ofSeconds(20);
	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private TestRestTemplate rest;

	@Autowired
	private MissionRepository missions;

	private TestMqtt robot;

	@BeforeEach
	void connect() throws Exception {
		robot = new TestMqtt(mqttUrl(), "it-mission-" + System.nanoTime());
		robot.subscribe(Vda5050Fixtures.orderTopicFilter());
	}

	@AfterEach
	void disconnect() throws Exception {
		robot.close();
	}

	/** 로봇 하나를 대기 상태로 등록하고 관제가 인식할 때까지 기다린다. */
	private void bringRobotOnline(String serial) throws Exception {
		robot.publish(Vda5050Fixtures.stateTopic(serial), Vda5050Fixtures.parkedState(serial, 1, 95.0));
		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(rest.getForObject("/api/robots", String.class)).contains(serial));
	}

	private long createMission(String from, String to, String ref) {
		ResponseEntity<Mission> res = rest.postForEntity("/api/missions",
				Map.of("type", "TRANSPORT", "fromNode", from, "toNode", to, "sourceRef", ref),
				Mission.class);

		assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(res.getBody()).isNotNull();
		return res.getBody().id();
	}

	@Test
	@DisplayName("주문 생성 -> 배정 -> 주행 -> 완료 가 실제 MQTT 로 이어진다")
	void fullLoopFromOrderToCompletion() throws Exception {
		String serial = "AMR-910";
		bringRobotOnline(serial);

		long missionId = createMission("P01", "D02", "IT-FULL-LOOP");
		String orderId = "M-" + missionId;

		// 1) 디스패처가 이 로봇에게 order 를 발행한다
		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(robot.received()).anySatisfy(m -> {
					assertThat(m.topic()).isEqualTo("uagv/v2/fleetdeck/" + serial + "/order");
					assertThat(m.payload()).contains(orderId);
				}));

		// 2) order 에 주행 가능한 좌표가 들어 있어야 한다
		JsonNode order = MAPPER.readTree(robot.received().stream()
				.filter(m -> m.payload().contains(orderId)).findFirst().orElseThrow().payload());
		JsonNode firstNode = order.get("nodes").get(0);
		assertThat(firstNode.get("nodeId").asText()).isEqualTo("P01");
		assertThat(firstNode.get("nodePosition").get("x").isNumber()).isTrue();
		assertThat(firstNode.get("nodePosition").get("y").isNumber()).isTrue();

		// 3) 로봇이 주행을 시작하면 RUNNING
		robot.publish(Vda5050Fixtures.stateTopic(serial),
				Vda5050Fixtures.executingState(serial, 2, orderId, "D02"));
		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(missions.findById(missionId)).isPresent()
						.hasValueSatisfying(m ->
								assertThat(m.status()).isEqualTo(Mission.MissionStatus.RUNNING)));

		// 4) 마지막 노드 도착 보고로 DONE
		robot.publish(Vda5050Fixtures.stateTopic(serial),
				Vda5050Fixtures.finishedState(serial, 3, orderId));
		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(missions.findById(missionId)).isPresent()
						.hasValueSatisfying(m -> {
							assertThat(m.status()).isEqualTo(Mission.MissionStatus.DONE);
							assertThat(m.assignedRobot()).isEqualTo(serial);
							assertThat(m.retryCount()).isZero();
						}));
	}

	@Test
	@DisplayName("가용 로봇이 없으면 PENDING 으로 남고, 로봇이 생기면 배정된다")
	void missionWaitsUntilARobotBecomesAvailable() throws Exception {
		// 로봇을 등록하지 않은 상태에서 주문
		long missionId = createMission("P02", "D01", "IT-NO-ROBOT");

		await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() ->
				assertThat(missions.findById(missionId)).isPresent()
						.hasValueSatisfying(m ->
								assertThat(m.status()).isEqualTo(Mission.MissionStatus.PENDING)));

		// 이제 로봇이 들어오면 재시도 루프가 배정한다
		String serial = "AMR-911";
		bringRobotOnline(serial);

		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(missions.findById(missionId)).isPresent()
						.hasValueSatisfying(m -> {
							assertThat(m.status()).isNotEqualTo(Mission.MissionStatus.PENDING);
							assertThat(m.assignedRobot()).isEqualTo(serial);
						}));
	}

	@Test
	@DisplayName("맵에 없는 노드로 주문하면 FAILED 로 끝난다")
	void missionWithUnknownNodeFails() throws Exception {
		bringRobotOnline("AMR-912");

		long missionId = createMission("NOPE-1", "NOPE-2", "IT-BAD-NODE");

		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(missions.findById(missionId)).isPresent()
						.hasValueSatisfying(m ->
								assertThat(m.status()).isEqualTo(Mission.MissionStatus.FAILED)));
	}

	@Test
	@DisplayName("어떤 로봇도 열린 미션을 둘 이상 갖지 않는다")
	void noRobotHoldsTwoOpenMissionsAtOnce() throws Exception {
		// 앞선 테스트가 등록한 로봇이 아직 온라인일 수 있으므로 플릿 크기를 가정하지 않는다.
		// 검증할 불변식은 "한 로봇 = 열린 미션 최대 1건" 하나뿐이다.
		bringRobotOnline("AMR-913");

		long first = createMission("P03", "D01", "IT-DUP-1");
		createMission("P04", "D02", "IT-DUP-2");
		createMission("P05", "D03", "IT-DUP-3");

		// 배정 루프가 한 번 돌 때까지 기다린다.
		await().atMost(TIMEOUT).untilAsserted(() ->
				assertThat(missions.findById(first)).isPresent()
						.hasValueSatisfying(m ->
								assertThat(m.status()).isNotEqualTo(Mission.MissionStatus.PENDING)));

		Map<String, Long> openPerRobot = missions.findRecent().stream()
				.filter(m -> !m.status().isTerminal())
				.filter(m -> m.assignedRobot() != null)
				.collect(java.util.stream.Collectors.groupingBy(
						Mission::assignedRobot, java.util.stream.Collectors.counting()));

		assertThat(openPerRobot).allSatisfy((robotSerial, open) ->
				assertThat(open).as("로봇 %s 의 열린 미션 수", robotSerial).isEqualTo(1L));
	}
}
