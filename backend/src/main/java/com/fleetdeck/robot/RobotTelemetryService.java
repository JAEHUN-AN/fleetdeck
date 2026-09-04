package com.fleetdeck.robot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetdeck.config.WebSocketConfig;
import com.fleetdeck.mission.MissionLifecycle;
import com.fleetdeck.telemetry.TelemetryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * 로봇 state JSON → 파싱 → 최신값 갱신 → DB 적재 → 미션 상태 전이 → WebSocket 브로드캐스트.
 */
@Service
public class RobotTelemetryService {

	private static final Logger log = LoggerFactory.getLogger(RobotTelemetryService.class);

	private final ObjectMapper objectMapper;
	private final RobotRegistry registry;
	private final TelemetryRepository repository;
	private final MissionLifecycle missionLifecycle;
	private final SimpMessagingTemplate messaging;

	public RobotTelemetryService(ObjectMapper objectMapper, RobotRegistry registry,
			TelemetryRepository repository, MissionLifecycle missionLifecycle,
			SimpMessagingTemplate messaging) {
		this.objectMapper = objectMapper;
		this.registry = registry;
		this.repository = repository;
		this.missionLifecycle = missionLifecycle;
		this.messaging = messaging;
	}

	public void accept(String json) {
		RobotStateMessage state;
		try {
			state = objectMapper.readValue(json, RobotStateMessage.class);
		}
		catch (JsonProcessingException e) {
			log.warn("invalid robot state payload dropped: {}", e.getOriginalMessage());
			return;
		}
		if (state.serialNumber() == null || state.serialNumber().isBlank()) {
			log.warn("robot state without serialNumber dropped");
			return;
		}

		registry.upsert(state);
		persist(state, json);
		missionLifecycle.onRobotState(state);
		messaging.convertAndSend(WebSocketConfig.ROBOTS_TOPIC, state);
	}

	private void persist(RobotStateMessage state, String json) {
		try {
			repository.insertRobotState(state, json);
		}
		catch (DataAccessException e) {
			// DB 장애가 실시간 관제를 막지 않도록 기록만 남긴다.
			log.error("robot state persist failed for {}: {}", state.serialNumber(),
					e.getMostSpecificCause().getMessage());
		}
	}
}
