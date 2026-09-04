package com.fleetdeck.equipment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetdeck.config.WebSocketConfig;
import com.fleetdeck.telemetry.TelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class EquipmentTelemetryService {

	private static final Logger log = LoggerFactory.getLogger(EquipmentTelemetryService.class);

	private final ObjectMapper objectMapper;
	private final EquipmentRegistry registry;
	private final TelemetryRepository repository;
	private final SimpMessagingTemplate messaging;

	public EquipmentTelemetryService(ObjectMapper objectMapper, EquipmentRegistry registry,
			TelemetryRepository repository, SimpMessagingTemplate messaging) {
		this.objectMapper = objectMapper;
		this.registry = registry;
		this.repository = repository;
		this.messaging = messaging;
	}

	public void accept(String json) {
		EquipmentStateMessage state;
		try {
			state = objectMapper.readValue(json, EquipmentStateMessage.class);
		}
		catch (JsonProcessingException e) {
			log.warn("invalid equipment state payload dropped: {}", e.getOriginalMessage());
			return;
		}
		if (state.equipmentId() == null || state.equipmentId().isBlank()) {
			log.warn("equipment state without equipmentId dropped");
			return;
		}

		registry.upsert(state);
		persist(state, json);
		messaging.convertAndSend(WebSocketConfig.EQUIPMENT_TOPIC, state);
	}

	private void persist(EquipmentStateMessage state, String json) {
		try {
			repository.insertEquipmentState(state, json);
		}
		catch (DataAccessException e) {
			log.error("equipment state persist failed for {}: {}", state.equipmentId(), e.getMostSpecificCause().getMessage());
		}
	}
}
