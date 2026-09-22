package com.fleetdeck.equipment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetdeck.config.WebSocketConfig;
import com.fleetdeck.fdc.SensorTelemetryService;
import com.fleetdeck.telemetry.TelemetryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * 프로토콜이 끝나는 지점의 계약.
 * MQTT 로 오든 OPC UA 로 오든 레지스트리·적재·브로드캐스트는 같아야 하고,
 * 다른 것은 source 하나뿐이어야 한다.
 */
@ExtendWith(MockitoExtension.class)
class EquipmentTelemetryServiceTest {

	@Mock
	private TelemetryRepository repository;

	@Mock
	private SimpMessagingTemplate messaging;

	private EquipmentRegistry registry;

	private EquipmentTelemetryService service;

	@BeforeEach
	void setUp() {
		registry = new EquipmentRegistry();
		service = new EquipmentTelemetryService(new ObjectMapper(), registry, repository, messaging,
				mock(SensorTelemetryService.class));
	}

	@Test
	void mqttJsonIsTaggedAsMqtt() {
		service.accept("""
				{"equipmentId":"SORTER-01","equipmentType":"WHEEL_SORTER","status":"RUNNING",
				 "throughputPerMin":1800,"x":5.0,"y":22.0,"timestamp":"2026-09-22T01:00:00.000Z"}
				""");

		verify(repository).insertEquipmentState(any(), anyString(), eq(EquipmentSource.MQTT));
		assertThat(registry.find("SORTER-01")).isPresent();
	}

	@Test
	void opcUaMessageIsTaggedAsOpcUaAndTakesTheSamePath() {
		EquipmentStateMessage state = new EquipmentStateMessage("UA-SORTER-01", "TILT_TRAY_SORTER",
				"RUNNING", 1800, null, 8.0, 17.0, "2026-09-22T01:00:00.000Z");

		service.accept(state, EquipmentSource.OPC_UA);

		verify(repository).insertEquipmentState(eq(state), anyString(), eq(EquipmentSource.OPC_UA));
		verify(messaging).convertAndSend(WebSocketConfig.EQUIPMENT_TOPIC, state);
		assertThat(registry.find("UA-SORTER-01")).contains(state);
	}

	@Test
	void opcUaPathSerializesAPayloadForTheJsonbColumn() {
		service.accept(new EquipmentStateMessage("UA-SORTER-01", "TILT_TRAY_SORTER", "ALARM", 0,
				"E101_JAM", 8.0, 17.0, "2026-09-22T01:00:00.000Z"), EquipmentSource.OPC_UA);

		ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
		verify(repository).insertEquipmentState(any(), payload.capture(), any());
		assertThat(payload.getValue())
				.contains("\"equipmentId\":\"UA-SORTER-01\"", "\"alarmCode\":\"E101_JAM\"");
	}

	@Test
	void messageWithoutEquipmentIdIsDropped() {
		service.accept(new EquipmentStateMessage(" ", "T", "RUNNING", 1, null, 0.0, 0.0, null),
				EquipmentSource.OPC_UA);

		verifyNoInteractions(repository, messaging);
		assertThat(registry.size()).isZero();
	}

	@Test
	void invalidJsonIsDroppedWithoutTouchingTheRegistry() {
		service.accept("{ not json");

		verify(repository, never()).insertEquipmentState(any(), anyString(), any());
		assertThat(registry.size()).isZero();
	}
}
