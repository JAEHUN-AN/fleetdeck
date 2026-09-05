package com.fleetdeck.mission;

import com.fleetdeck.config.FleetdeckProperties;
import com.fleetdeck.config.WebSocketConfig;
import com.fleetdeck.map.WarehouseMap;
import com.fleetdeck.robot.RobotRegistry;
import com.fleetdeck.robot.RobotReservations;
import com.fleetdeck.robot.RobotStateMessage;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기 작업 세 가지.
 *
 * 1) PENDING 재시도 — 생성 시점에 유휴 로봇이 없으면 PENDING 으로 남는다.
 *    로봇이 일을 마치고 대기 상태가 되면 이 루프가 배정한다.
 * 2) 정체 배정 회수 — order 를 못 받았거나 백엔드 재시작으로 완료 보고를 놓친 미션을
 *    PENDING 으로 되돌린다. 예약 차단이 놓친 경우까지 받는 안전망.
 * 3) 모의 WMS 주문 — 상위 시스템 대신 출고 주문을 만들어 넣는다 (fleetdeck.wms.enabled).
 */
@Component
public class MissionScheduler {

	private static final Logger log = LoggerFactory.getLogger(MissionScheduler.class);
	private static final int RECLAIM_LIMIT = 20;

	private final MissionRepository repository;
	private final MissionService missionService;
	private final RobotRegistry robots;
	private final RobotReservations reservations;
	private final WarehouseMap warehouseMap;
	private final SimpMessagingTemplate messaging;
	private final FleetdeckProperties props;

	public MissionScheduler(MissionRepository repository, MissionService missionService,
			RobotRegistry robots, RobotReservations reservations, WarehouseMap warehouseMap,
			SimpMessagingTemplate messaging, FleetdeckProperties props) {
		this.repository = repository;
		this.missionService = missionService;
		this.robots = robots;
		this.reservations = reservations;
		this.warehouseMap = warehouseMap;
		this.messaging = messaging;
		this.props = props;
	}

	@Scheduled(fixedDelayString = "${fleetdeck.dispatch.interval:3s}")
	public void dispatchPending() {
		List<Mission> pending;
		try {
			pending = repository.findPending(props.dispatch().batchSize());
		}
		catch (DataAccessException e) {
			log.error("PENDING 조회 실패: {}", e.getMostSpecificCause().getMessage());
			return;
		}

		for (Mission mission : pending) {
			Mission result = missionService.dispatchAndPublish(mission);
			if (result.status() == Mission.MissionStatus.PENDING) {
				// 유휴 로봇이 아직 없다. 나머지도 마찬가지이므로 이번 주기는 여기서 끝낸다.
				break;
			}
		}
	}

	@Scheduled(fixedDelayString = "${fleetdeck.dispatch.reclaim-interval:10s}")
	public void reclaimStale() {
		OffsetDateTime cutoff = OffsetDateTime.now().minus(props.dispatch().staleAfter());
		List<Mission> stale;
		try {
			stale = repository.findStale(cutoff, RECLAIM_LIMIT);
		}
		catch (DataAccessException e) {
			log.error("정체 미션 조회 실패: {}", e.getMostSpecificCause().getMessage());
			return;
		}

		for (Mission mission : stale) {
			if (isRobotStillWorkingOn(mission)) {
				continue;
			}
			reclaim(mission);
		}
	}

	/**
	 * 배정된 로봇이 지금도 이 미션의 order 를 수행 중인지.
	 * 경로가 길어 오래 걸리는 정상 주행을 회수해버리지 않기 위한 확인.
	 */
	private boolean isRobotStillWorkingOn(Mission mission) {
		if (mission.assignedRobot() == null) {
			return false;
		}
		Optional<RobotStateMessage> robot = robots.find(mission.assignedRobot());
		return robot.isPresent()
				&& mission.orderId().equals(robot.get().orderId())
				&& robot.get().isExecutingOrder();
	}

	private void reclaim(Mission mission) {
		Mission reclaimed = mission.reclaimed();
		try {
			repository.updateStatus(reclaimed);
		}
		catch (DataAccessException e) {
			log.error("mission {} 회수 실패: {}", mission.id(), e.getMostSpecificCause().getMessage());
			return;
		}
		reservations.release(mission.assignedRobot());
		log.warn("mission {} {} -> PENDING 회수 ({} 가 {}초 넘게 진전 없음)",
				mission.id(), mission.status(), mission.assignedRobot(), props.dispatch().staleAfter().toSeconds());
		messaging.convertAndSend(WebSocketConfig.MISSIONS_TOPIC, reclaimed);
	}

	@Scheduled(fixedDelayString = "${fleetdeck.wms.interval:12s}")
	public void generateMockWmsOrder() {
		if (!props.wms().enabled()) {
			return;
		}

		try {
			if (repository.countOpen() >= props.wms().maxOpenMissions()) {
				return;
			}
			missionService.create(randomTransportOrder());
		}
		catch (DataAccessException e) {
			log.error("모의 WMS 주문 생성 실패: {}", e.getMostSpecificCause().getMessage());
		}
	}

	private MissionRequest randomTransportOrder() {
		List<WarehouseMap.Node> picks = warehouseMap.byKind(WarehouseMap.NodeKind.PICK);
		List<WarehouseMap.Node> drops = warehouseMap.byKind(WarehouseMap.NodeKind.DROP);
		ThreadLocalRandom rng = ThreadLocalRandom.current();

		String from = picks.get(rng.nextInt(picks.size())).nodeId();
		String to = drops.get(rng.nextInt(drops.size())).nodeId();
		String ref = "WMS-AUTO-%06d".formatted(rng.nextInt(1, 1_000_000));

		return new MissionRequest(Mission.MissionType.TRANSPORT, from, to, ref);
	}
}
