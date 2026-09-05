package com.fleetdeck.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * 대시보드가 실제로 받는 STOMP 프레임을 검증한다.
 *
 * REST·MQTT·DB 경로는 다른 통합 테스트가 덮지만 브로드캐스트 구간은 여기서만 확인된다.
 * 화면이 갱신되지 않는 장애는 대부분 이 구간에서 난다.
 */
class WebSocketBroadcastIT extends IntegrationTestBase {

	private static final Duration FRAME_TIMEOUT = Duration.ofSeconds(20);

	@LocalServerPort
	private int port;

	@Autowired
	private TestRestTemplate rest;

	private WebSocketStompClient stompClient;
	private StompSession session;
	private TestMqtt robot;

	private final BlockingQueue<JsonNode> robotFrames = new LinkedBlockingQueue<>();
	private final BlockingQueue<JsonNode> equipmentFrames = new LinkedBlockingQueue<>();
	private final BlockingQueue<JsonNode> missionFrames = new LinkedBlockingQueue<>();

	@BeforeEach
	void connect() throws Exception {
		robot = new TestMqtt(mqttUrl(), "it-ws-" + System.nanoTime());

		stompClient = new WebSocketStompClient(new StandardWebSocketClient());
		// 서버는 application/json 으로 보낸다. StringMessageConverter 는 text/plain 만 받아
		// 프레임을 조용히 버리므로 Jackson 컨버터를 쓴다.
		// JsonNode 로 받으면 @JsonUnwrapped 평탄화 결과를 그대로 볼 수 있다.
		stompClient.setMessageConverter(new MappingJackson2MessageConverter());

		session = stompClient
				.connectAsync("ws://localhost:" + port + "/ws", new StompSessionHandlerAdapter() {
				})
				.get(20, TimeUnit.SECONDS);

		subscribe("/topic/robots", robotFrames);
		subscribe("/topic/equipment", equipmentFrames);
		subscribe("/topic/missions", missionFrames);
	}

	@AfterEach
	void disconnect() throws Exception {
		if (session != null && session.isConnected()) {
			session.disconnect();
		}
		if (stompClient != null) {
			stompClient.stop();
		}
		robot.close();
	}

	private void subscribe(String destination, BlockingQueue<JsonNode> sink) {
		session.subscribe(destination, new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return JsonNode.class;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				sink.add((JsonNode) payload);
			}
		});
	}

	/** 조건에 맞는 프레임이 올 때까지 기다린다. 관계없는 프레임은 흘려보낸다. */
	private JsonNode awaitFrame(BlockingQueue<JsonNode> sink, String mustContain) throws Exception {
		long deadline = System.nanoTime() + FRAME_TIMEOUT.toNanos();
		while (System.nanoTime() < deadline) {
			JsonNode frame = sink.poll(1, TimeUnit.SECONDS);
			if (frame != null && frame.toString().contains(mustContain)) {
				return frame;
			}
		}
		throw new AssertionError("%s 를 담은 프레임이 %s 안에 오지 않았다"
				.formatted(mustContain, FRAME_TIMEOUT));
	}

	@Test
	@DisplayName("MQTT 로 들어온 로봇 상태가 /topic/robots 로 흘러나간다")
	void robotStateIsBroadcast() throws Exception {
		String serial = "AMR-950";

		robot.publish(Vda5050Fixtures.stateTopic(serial), Vda5050Fixtures.parkedState(serial, 1, 73.0));

		JsonNode frame = awaitFrame(robotFrames, serial);

		assertThat(frame.get("serialNumber").asText()).isEqualTo(serial);
		assertThat(frame.get("batteryState").get("batteryCharge").asDouble()).isEqualTo(73.0);
	}

	@Test
	@DisplayName("브로드캐스트 프레임에 online 과 lastSeenAt 이 평탄화되어 실린다")
	void broadcastCarriesLivenessFields() throws Exception {
		String serial = "AMR-951";

		robot.publish(Vda5050Fixtures.stateTopic(serial), Vda5050Fixtures.parkedState(serial, 1, 60.0));

		JsonNode frame = awaitFrame(robotFrames, serial);

		// @JsonUnwrapped 가 깨지면 프론트의 robotActivity() 가 전부 오프라인으로 오판한다.
		assertThat(frame.has("online")).as("online 필드").isTrue();
		assertThat(frame.get("online").asBoolean()).isTrue();
		assertThat(frame.has("lastSeenAt")).as("lastSeenAt 필드").isTrue();
		assertThat(frame.get("serialNumber").asText()).isEqualTo(serial);
	}

	@Test
	@DisplayName("설비 상태가 /topic/equipment 로 흘러나간다")
	void equipmentStateIsBroadcast() throws Exception {
		String id = "SORTER-95";

		robot.publish(Vda5050Fixtures.equipmentTopic(id), Vda5050Fixtures.sorterState(id, "ALARM", 0));

		JsonNode frame = awaitFrame(equipmentFrames, id);

		assertThat(frame.get("equipmentId").asText()).isEqualTo(id);
		assertThat(frame.get("status").asText()).isEqualTo("ALARM");
	}

	@Test
	@DisplayName("미션 생성이 /topic/missions 로 흘러나간다")
	void missionChangesAreBroadcast() throws Exception {
		String serial = "AMR-952";
		robot.publish(Vda5050Fixtures.stateTopic(serial), Vda5050Fixtures.parkedState(serial, 1, 95.0));
		awaitFrame(robotFrames, serial);

		var created = rest.postForEntity("/api/missions",
				Map.of("type", "TRANSPORT", "fromNode", "P01", "toNode", "D02",
						"sourceRef", "IT-WS-MISSION"),
				String.class);
		assertThat(created.getStatusCode().is2xxSuccessful()).isTrue();

		JsonNode frame = awaitFrame(missionFrames, "IT-WS-MISSION");

		assertThat(frame.get("fromNode").asText()).isEqualTo("P01");
		assertThat(frame.get("toNode").asText()).isEqualTo("D02");
		assertThat(frame.has("status")).isTrue();
		assertThat(frame.has("retryCount")).isTrue();
	}
}
