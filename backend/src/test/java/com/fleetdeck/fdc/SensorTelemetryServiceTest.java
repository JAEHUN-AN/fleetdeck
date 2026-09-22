package com.fleetdeck.fdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fleetdeck.config.FleetdeckProperties;
import com.fleetdeck.config.WebSocketConfig;
import com.fleetdeck.equipment.EquipmentSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 센서 적재 경로의 계약.
 *
 * <p>여기서 지키는 것은 넷이다 — 프로토콜과 무관하게 적재된다, 사양 없는 채널도 적재는 된다,
 * 이상이 오면 경보가 적재·중계된다, 적재가 실패해도 수집이 멈추지 않는다.
 */
class SensorTelemetryServiceTest {

	private static final OffsetDateTime AT = OffsetDateTime.parse("2026-09-22T01:00:00Z");

	private SensorRepository repository;
	private SimpMessagingTemplate messaging;
	private SensorTelemetryService service;

	@BeforeEach
	void setUp() {
		repository = mock(SensorRepository.class);
		messaging = mock(SimpMessagingTemplate.class);
		service = new SensorTelemetryService(properties(true), repository, messaging);
	}

	private static FleetdeckProperties properties(boolean enabled) {
		return new FleetdeckProperties(null, null,
				new FleetdeckProperties.Fdc(enabled,
						Map.of("MotorCurrent", new ChannelSpec(12.0, 0.25))),
				null, null, null);
	}

	private static SensorSample sample(String channel, double value) {
		return new SensorSample("UA-SORTER-01", channel, value, AT, EquipmentSource.OPC_UA);
	}

	@Test
	void persistsEverySampleRegardlessOfProtocol() {
		List<SensorSample> samples = List.of(
				new SensorSample("SORTER-01", "MotorCurrent", 12.1, AT, EquipmentSource.MQTT),
				new SensorSample("UA-SORTER-01", "MotorCurrent", 12.2, AT, EquipmentSource.OPC_UA));

		service.accept(samples);

		verify(repository).insertSamples(samples);
	}

	@Test
	void emptyBatchTouchesNothing() {
		service.accept(List.of());

		verifyNoInteractions(repository, messaging);
	}

	@Test
	void channelWithoutASpecIsStoredButNotAnalysed() {
		// 설비가 새 채널을 내보내기 시작해도 데이터는 남아야 한다.
		// 다만 기준 없이 만든 관리한계는 경보가 아니라 소음이다.
		for (int i = 0; i < 50; i++) {
			service.accept(List.of(sample("ChamberPressure", 9999.0)));
		}

		verify(repository, never()).insertAlarm(any(), any());
		verifyNoInteractions(messaging);
	}

	@Test
	void sustainedAnomalyRaisesAnAlarmAndBroadcastsIt() {
		warmUp();

		for (int i = 0; i < 10; i++) {
			service.accept(List.of(sample("MotorCurrent", 12.0 + 10 * 0.25)));
		}

		verify(repository, org.mockito.Mockito.atLeastOnce()).insertAlarm(any(), any());
		ArgumentCaptor<SensorTelemetryService.AlarmView> view =
				ArgumentCaptor.forClass(SensorTelemetryService.AlarmView.class);
		verify(messaging, org.mockito.Mockito.atLeastOnce())
				.convertAndSend(eq(WebSocketConfig.ALARMS_TOPIC), view.capture());

		assertThat(view.getValue().equipmentId()).isEqualTo("UA-SORTER-01");
		assertThat(view.getValue().channel()).isEqualTo("MotorCurrent");
		assertThat(view.getValue().at()).isEqualTo(AT);
	}

	@Test
	void healthySignalStaysWellUnderTheFalseAlarmBudget() {
		// 0 을 요구하면 안 된다 - 통계 탐지기의 오경보율은 설계상 0 이 아니고, 0 을 기대한
		// 테스트는 시드에 따라 흔들린다. 지켜야 하는 것은 "실용 범위" 다.
		Random rng = new Random(41);
		for (int i = 0; i < 400; i++) {
			service.accept(List.of(sample("MotorCurrent", 12.0 + rng.nextGaussian() * 0.25)));
		}

		verify(repository, org.mockito.Mockito.atMost(8)).insertAlarm(any(), any());
	}

	@Test
	void oneEquipmentGoingBadDoesNotAlarmTheOther() {
		// 감시기를 설비 간에 공유하면 한 대의 이상이 다른 대의 통계를 오염시킨다.
		Random rng = new Random(43);
		for (int i = 0; i < 200; i++) {
			double bad = i < 120 ? 12.0 + rng.nextGaussian() * 0.25 : 12.0 + 10 * 0.25;
			service.accept(List.of(
					new SensorSample("A", "MotorCurrent", bad, AT, EquipmentSource.MQTT),
					new SensorSample("B", "MotorCurrent", 12.0 + rng.nextGaussian() * 0.25, AT,
							EquipmentSource.MQTT)));
		}

		ArgumentCaptor<SensorSample> alarmed = ArgumentCaptor.forClass(SensorSample.class);
		verify(repository, org.mockito.Mockito.atLeastOnce()).insertAlarm(alarmed.capture(), any());
		Map<String, Long> byEquipment = alarmed.getAllValues().stream().collect(
				java.util.stream.Collectors.groupingBy(SensorSample::equipmentId,
						java.util.stream.Collectors.counting()));

		assertThat(byEquipment.get("A")).as("이상이 있는 A").isGreaterThan(3);
		// B 도 0 은 아니다 - 통계 탐지기의 오경보는 설계상 존재한다. 확인할 것은
		// "A 의 이상이 B 로 번지지 않는다" 이지 "B 가 절대 안 운다" 가 아니다.
		assertThat(byEquipment.getOrDefault("B", 0L)).as("정상인 B (오경보 예산)")
				.isLessThanOrEqualTo(2);
	}

	@Test
	void detectionIsSkippedWhenDisabledButPersistenceIsNot() {
		service = new SensorTelemetryService(properties(false), repository, messaging);

		for (int i = 0; i < 50; i++) {
			service.accept(List.of(sample("MotorCurrent", 9999.0)));
		}

		verify(repository, org.mockito.Mockito.atLeastOnce()).insertSamples(anyList());
		verify(repository, never()).insertAlarm(any(), any());
	}

	@Test
	void persistenceFailureDoesNotStopIngestion() {
		// DB 가 잠깐 죽어도 수집 스레드가 죽으면 안 된다. 그 순간 모든 설비가 보이지 않게 된다.
		org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("db down"))
				.when(repository).insertSamples(anyList());

		service.accept(List.of(sample("MotorCurrent", 12.1)));
		service.accept(List.of(sample("MotorCurrent", 12.2)));

		verify(repository, org.mockito.Mockito.times(2)).insertSamples(anyList());
	}

	@Test
	void incompleteFaultLabelIsDropped() {
		service.acceptFaultInjection(
				new FaultInjectionMessage(" ", "MotorCurrent", "STEP", 5.0, "START", null));

		verify(repository, never()).insertFaultInjection(any());
	}

	@Test
	void usableFaultLabelIsStored() {
		FaultInjectionMessage message = new FaultInjectionMessage("UA-SORTER-01", "MotorCurrent",
				"STEP", 5.0, "START", "2026-09-22T01:00:00.000Z");

		service.acceptFaultInjection(message);

		verify(repository).insertFaultInjection(message);
	}

	private void warmUp() {
		Random rng = new Random(37);
		for (int i = 0; i < 120; i++) {
			service.accept(List.of(sample("MotorCurrent", 12.0 + rng.nextGaussian() * 0.25)));
		}
	}
}
