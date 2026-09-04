package com.fleetdeck.mission;

import com.fleetdeck.config.WebSocketConfig;
import com.fleetdeck.mission.Mission.MissionStatus;
import com.fleetdeck.robot.RobotStateMessage;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * 로봇 텔레메트리를 보고 미션 상태를 전이시킨다. 제어 루프의 되먹임 구간.
 *
 *   ASSIGNED --(로봇이 주행 시작)--> RUNNING --(마지막 노드 도착)--> DONE
 *   ASSIGNED/RUNNING --(로봇 에러)--> FAILED
 *
 * 텔레메트리는 1초에 로봇 수만큼 들어오므로, 상태가 실제로 바뀔 때만 DB 를 건드린다.
 */
@Service
public class MissionLifecycle {

	private static final Logger log = LoggerFactory.getLogger(MissionLifecycle.class);

	private final MissionRepository repository;
	private final SimpMessagingTemplate messaging;

	public MissionLifecycle(MissionRepository repository, SimpMessagingTemplate messaging) {
		this.repository = repository;
		this.messaging = messaging;
	}

	public void onRobotState(RobotStateMessage state) {
		Long missionId = Mission.idFromOrderId(state.orderId());
		if (missionId == null) {
			return;
		}

		Optional<Mission> found = load(missionId);
		if (found.isEmpty()) {
			return;
		}

		Mission mission = found.get();
		if (mission.status().isTerminal()) {
			return;
		}
		if (!state.serialNumber().equals(mission.assignedRobot())) {
			// 다른 로봇이 같은 orderId 를 보고하는 경우. 재배정 중일 수 있으니 건드리지 않는다.
			return;
		}

		nextStatus(mission, state).ifPresent(next -> persistAndBroadcast(mission, next));
	}

	/** 전이 규칙만 담은 순수 함수. 바뀔 게 없으면 empty. */
	static Optional<Mission> nextStatus(Mission mission, RobotStateMessage state) {
		if (state.errorCount() > 0) {
			return Optional.of(mission.failed());
		}
		if (mission.status() == MissionStatus.ASSIGNED && state.isExecutingOrder()) {
			return Optional.of(mission.running());
		}
		if (mission.status() == MissionStatus.RUNNING && state.hasFinishedOrder()) {
			return Optional.of(mission.completed());
		}
		return Optional.empty();
	}

	private Optional<Mission> load(long missionId) {
		try {
			return repository.findById(missionId);
		}
		catch (DataAccessException e) {
			log.error("mission {} 조회 실패: {}", missionId, e.getMostSpecificCause().getMessage());
			return Optional.empty();
		}
	}

	private void persistAndBroadcast(Mission before, Mission after) {
		try {
			repository.updateStatus(after);
		}
		catch (DataAccessException e) {
			log.error("mission {} 상태 저장 실패: {}", after.id(), e.getMostSpecificCause().getMessage());
			return;
		}
		log.info("mission {} {} -> {} ({})", after.id(), before.status(), after.status(), after.assignedRobot());
		messaging.convertAndSend(WebSocketConfig.MISSIONS_TOPIC, after);
	}
}
