package com.fleetdeck.mission;

import com.fleetdeck.config.WebSocketConfig;
import java.util.List;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

@Service
public class MissionService {

	private final MissionRepository repository;
	private final MissionDispatcher dispatcher;
	private final SimpMessagingTemplate messaging;

	public MissionService(MissionRepository repository, MissionDispatcher dispatcher, SimpMessagingTemplate messaging) {
		this.repository = repository;
		this.dispatcher = dispatcher;
		this.messaging = messaging;
	}

	public Mission create(MissionRequest request) {
		Mission created = repository.insert(request);
		Mission result = dispatcher.tryAssign(created);
		if (result.status() != created.status()) {
			repository.updateAssignment(result);
		}
		messaging.convertAndSend(WebSocketConfig.MISSIONS_TOPIC, result);
		return result;
	}

	public List<Mission> recent() {
		return repository.findRecent();
	}
}
