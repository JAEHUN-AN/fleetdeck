package com.fleetdeck.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fleetdeck.config.FleetdeckProperties;
import com.fleetdeck.equipment.EquipmentSource;
import com.fleetdeck.equipment.EquipmentStateMessage;
import com.fleetdeck.equipment.EquipmentTelemetryService;
import com.fleetdeck.fdc.SensorSample;
import com.fleetdeck.fdc.SensorTelemetryService;
import com.fleetdeck.opcua.OpcUaCollector;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 실제 OPC UA 서버(시뮬레이터)에 붙어 탐색 → 구독 → 매핑까지 흘려본다.
 *
 * <p>가짜 서버를 인프로세스로 띄우면 주소공간을 우리가 만들게 되므로, 실제로 문제가 됐던
 * 것들(BrowseName 의 네임스페이스, Int64 로 오는 정수, sourceTimestamp 유무)이 재현되지
 * 않는다. 그래서 진짜 asyncua 서버를 상대한다.
 *
 * <p>서버가 없으면 건너뛴다. 띄우는 방법:
 *
 * <pre>
 *   docker compose up -d simulator
 * </pre>
 */
@Tag("integration")
class OpcUaCollectorIT {

	private static final String HOST = System.getProperty("opcua.host", "127.0.0.1");
	private static final int PORT = Integer.getInteger("opcua.port", 4840);
	private static final String ENDPOINT = "opc.tcp://" + HOST + ":" + PORT + "/fleetdeck/server/";

	private EquipmentTelemetryService equipment;
	private SensorTelemetryService sensors;
	private OpcUaCollector collector;

	@BeforeEach
	void setUp() {
		Assumptions.assumeTrue(reachable(), "OPC UA 서버가 없다: " + ENDPOINT);
		equipment = mock(EquipmentTelemetryService.class);
		sensors = mock(SensorTelemetryService.class);
		collector = new OpcUaCollector(properties(FleetdeckProperties.OpcUaMode.SUBSCRIBE), equipment,
				sensors);
	}

	@AfterEach
	void tearDown() {
		if (collector != null) {
			collector.stop();
		}
	}

	@Test
	void subscribesAndDeliversCompleteEquipmentState() {
		collector.start();

		List<EquipmentStateMessage> states = captureAtLeastOne();

		// 부분 스냅샷(상태 없이 좌표만)이 새어 나가면 안 된다.
		assertThat(states).allSatisfy(state -> {
			assertThat(state.equipmentId()).startsWith("UA-SORTER-");
			assertThat(state.status()).isNotBlank();
			assertThat(state.equipmentType()).isEqualTo("TILT_TRAY_SORTER");
			assertThat(state.throughputPerMin()).isNotNull();
			assertThat(state.x()).isNotNull();
			assertThat(state.y()).isNotNull();
			// 장비가 값을 만든 시각. 없으면 수집 시각으로 대체돼 지연 측정이 무의미해진다.
			assertThat(state.timestamp()).isNotNull();
		});
	}

	@Test
	void browsesEveryEquipmentRatherThanTheFirstOne() {
		collector.start();

		assertThat(captureAtLeastOne()).extracting(EquipmentStateMessage::equipmentId)
				.contains("UA-SORTER-01", "UA-SORTER-02");
	}

	@Test
	void pollModeProducesTheSameShape() {
		collector.stop();
		collector = new OpcUaCollector(properties(FleetdeckProperties.OpcUaMode.POLL), equipment,
				sensors);
		collector.start();

		assertThat(captureAtLeastOne()).allSatisfy(
				state -> assertThat(state.status()).isNotBlank());
	}

	/**
	 * 구독은 변수 단위로 알림이 온다. 설비 2대 × 변수 6개면 서버가 1초에 한 번 값을 바꿔도
	 * 알림은 초당 12건이다. 그대로 적재하면 행이 6배로 불고 대부분 필드가 빈 채 남는다.
	 * 설비당 1건으로 모이는지 — 즉 초당 2건 언저리인지 본다.
	 */
	@Test
	void notificationsAreCoalescedIntoOneStatePerEquipment() {
		collector.start();
		captureAtLeastOne();

		org.mockito.Mockito.clearInvocations(equipment);
		long startedAt = System.nanoTime();
		await().pollDelay(Duration.ofSeconds(6)).atMost(Duration.ofSeconds(8))
				.untilAsserted(() -> assertThat(true).isTrue());
		double seconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;

		ArgumentCaptor<EquipmentStateMessage> captor =
				ArgumentCaptor.forClass(EquipmentStateMessage.class);
		verify(equipment, atLeastOnce()).accept(captor.capture(), eq(EquipmentSource.OPC_UA));
		double perSecond = captor.getAllValues().size() / seconds;

		// 설비 2대가 1Hz 로 갱신되므로 이상적으로 초당 2건. 변수별로 새면 12건에 가까워진다.
		// 실측 1.98/s (docs/021-opcua.md).
		assertThat(perSecond).isLessThan(4.0);
	}

	@Test
	void sensorChannelsFlowWithoutBeingNamedInTheBackend() {
		// 채널 이름을 백엔드에 박지 않았다. 브라우징이 찾아오고, 상태 변수가 아닌 수치 변수를
		// 센서로 넘긴다 - 설비가 채널을 늘려도 여기는 그대로여야 한다.
		collector.start();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<SensorSample>> captor = ArgumentCaptor.forClass(List.class);
		await().atMost(Duration.ofSeconds(20)).untilAsserted(
				() -> verify(sensors, atLeastOnce()).accept(captor.capture()));

		List<SensorSample> samples = captor.getAllValues().stream().flatMap(List::stream).toList();
		assertThat(samples).isNotEmpty();
		assertThat(samples).extracting(SensorSample::channel)
				.contains("MotorCurrent", "VibrationRms", "BearingTemp");
		assertThat(samples).allSatisfy(s -> {
			assertThat(s.equipmentId()).startsWith("UA-SORTER-");
			assertThat(s.source()).isEqualTo(EquipmentSource.OPC_UA);
			assertThat(s.at()).isNotNull();
		});
		// 상태 변수가 센서로 새면 ThroughputPerMin 이 채널로 들어온다.
		assertThat(samples).extracting(SensorSample::channel)
				.doesNotContain("ThroughputPerMin", "PositionX", "PositionY");
	}

	private List<EquipmentStateMessage> captureAtLeastOne() {
		ArgumentCaptor<EquipmentStateMessage> captor =
				ArgumentCaptor.forClass(EquipmentStateMessage.class);
		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> verify(equipment, atLeastOnce())
				.accept(captor.capture(), eq(EquipmentSource.OPC_UA)));
		return captor.getAllValues();
	}

	private static FleetdeckProperties properties(FleetdeckProperties.OpcUaMode mode) {
		return new FleetdeckProperties(null,
				new FleetdeckProperties.OpcUa(true, ENDPOINT, "urn:fleetdeck:simulator", "Equipment",
						mode, Duration.ofMillis(500), Duration.ofMillis(500), Duration.ofSeconds(1)),
				null, null, null, null);
	}

	private static boolean reachable() {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(HOST, PORT), 1000);
			return true;
		}
		catch (IOException e) {
			return false;
		}
	}
}
