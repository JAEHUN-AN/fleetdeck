package com.fleetdeck.equipment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetdeck.config.WebSocketConfig;
import com.fleetdeck.fdc.SensorExtractor;
import com.fleetdeck.fdc.SensorTelemetryService;
import com.fleetdeck.telemetry.TelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * 설비 상태의 합류 지점.
 *
 * <p>입구는 프로토콜마다 다르다 — MQTT 는 JSON 문자열로, OPC UA 는 이미 타입이 있는 값으로
 * 들어온다. 그 차이는 {@link #accept(String)} 과 {@link #accept(EquipmentStateMessage,
 * EquipmentSource)} 에서 끝나고, 이후 경로(레지스트리 · 적재 · 브로드캐스트)는 하나다.
 */
@Service
public class EquipmentTelemetryService {

	private static final Logger log = LoggerFactory.getLogger(EquipmentTelemetryService.class);

	private final ObjectMapper objectMapper;
	private final EquipmentRegistry registry;
	private final TelemetryRepository repository;
	private final SimpMessagingTemplate messaging;
	private final SensorTelemetryService sensors;

	public EquipmentTelemetryService(ObjectMapper objectMapper, EquipmentRegistry registry,
			TelemetryRepository repository, SimpMessagingTemplate messaging,
			SensorTelemetryService sensors) {
		this.objectMapper = objectMapper;
		this.registry = registry;
		this.repository = repository;
		this.messaging = messaging;
		this.sensors = sensors;
	}

	/** MQTT 입구. 원문 JSON 을 그대로 jsonb 컬럼에 남긴다. */
	public void accept(String json) {
		EquipmentStateMessage state;
		try {
			state = objectMapper.readValue(json, EquipmentStateMessage.class);
		}
		catch (JsonProcessingException e) {
			log.warn("invalid equipment state payload dropped: {}", e.getOriginalMessage());
			return;
		}
		ingest(state, json, EquipmentSource.MQTT);
	}

	/** OPC UA 입구. 원문이 없으므로 같은 모양의 JSON 을 만들어 남긴다. */
	public void accept(EquipmentStateMessage state, EquipmentSource source) {
		if (isBlank(state)) {
			log.warn("equipment state without equipmentId dropped ({})", source);
			return;
		}
		String json;
		try {
			json = objectMapper.writeValueAsString(state);
		}
		catch (JsonProcessingException e) {
			log.warn("equipment state serialization failed for {}: {}", state.equipmentId(),
					e.getOriginalMessage());
			return;
		}
		ingest(state, json, source);
	}

	private void ingest(EquipmentStateMessage state, String json, EquipmentSource source) {
		if (isBlank(state)) {
			log.warn("equipment state without equipmentId dropped ({})", source);
			return;
		}
		registry.upsert(state);
		persist(state, json, source);
		messaging.convertAndSend(WebSocketConfig.EQUIPMENT_TOPIC, state);
		// 센서 채널은 별도 하이퍼테이블로 간다. 상태 행에 열로 붙이면 설비 종류마다
		// 열이 달라지고 대부분 NULL 이 된다.
		sensors.accept(SensorExtractor.fromEquipmentState(state, source));
	}

	private static boolean isBlank(EquipmentStateMessage state) {
		return state.equipmentId() == null || state.equipmentId().isBlank();
	}

	private void persist(EquipmentStateMessage state, String json, EquipmentSource source) {
		try {
			repository.insertEquipmentState(state, json, source);
		}
		catch (DataAccessException e) {
			log.error("equipment state persist failed for {} ({}): {}", state.equipmentId(), source,
					e.getMostSpecificCause().getMessage());
		}
	}
}
