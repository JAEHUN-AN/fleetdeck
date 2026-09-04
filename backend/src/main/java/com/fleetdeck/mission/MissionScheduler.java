package com.fleetdeck.mission;

import com.fleetdeck.config.FleetdeckProperties;
import com.fleetdeck.map.WarehouseMap;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 주기 작업 두 가지.
 *
 * 1) PENDING 재시도 — 미션 생성 시점에 유휴 로봇이 없으면 PENDING 으로 남는다.
 *    로봇이 일을 마치고 대기 상태가 되면 이 루프가 배정한다.
 * 2) 모의 WMS 주문 — 상위 시스템 대신 출고 주문을 만들어 넣는다 (fleetdeck.wms.enabled).
 */
@Component
public class MissionScheduler {

	private static final Logger log = LoggerFactory.getLogger(MissionScheduler.class);

	private final MissionRepository repository;
	private final MissionService missionService;
	private final WarehouseMap warehouseMap;
	private final FleetdeckProperties props;

	public MissionScheduler(MissionRepository repository, MissionService missionService,
			WarehouseMap warehouseMap, FleetdeckProperties props) {
		this.repository = repository;
		this.missionService = missionService;
		this.warehouseMap = warehouseMap;
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
