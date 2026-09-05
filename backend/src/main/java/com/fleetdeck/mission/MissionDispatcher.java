package com.fleetdeck.mission;

import com.fleetdeck.config.FleetdeckProperties;
import com.fleetdeck.map.WarehouseMap;
import com.fleetdeck.mqtt.MqttPublisher;
import com.fleetdeck.robot.RobotRegistry;
import com.fleetdeck.robot.RobotReservations;
import com.fleetdeck.robot.RobotStateMessage;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 미션을 유휴 로봇에 배정하고 VDA5050 order 를 발행한다.
 * 노드 좌표(nodePosition)를 함께 실어 보내야 로봇이 주행할 수 있다.
 */
@Component
public class MissionDispatcher {

	private static final Logger log = LoggerFactory.getLogger(MissionDispatcher.class);
	private static final String VDA_VERSION = "2.0.0";

	private final RobotRegistry robots;
	private final RobotReservations reservations;
	private final MqttPublisher publisher;
	private final WarehouseMap warehouseMap;
	private final FleetdeckProperties props;

	public MissionDispatcher(RobotRegistry robots, RobotReservations reservations, MqttPublisher publisher,
			WarehouseMap warehouseMap, FleetdeckProperties props) {
		this.robots = robots;
		this.reservations = reservations;
		this.publisher = publisher;
		this.warehouseMap = warehouseMap;
		this.props = props;
	}

	/** 배정에 성공하면 ASSIGNED 상태의 새 Mission, 유휴 로봇이 없으면 원본을 그대로 돌려준다. */
	public Mission tryAssign(Mission mission) {
		List<WarehouseMap.Node> route = buildRoute(mission);
		if (route.isEmpty()) {
			log.warn("mission {}: 경로를 만들 수 없음 ({} -> {})", mission.id(), mission.fromNode(), mission.toNode());
			return mission.failed();
		}

		Instant now = Instant.now();
		// 오프라인 로봇은 마지막 상태가 유휴로 남아 있어도 후보에서 뺀다.
		Optional<RobotStateMessage> picked =
				pickIdleRobot(robots.onlineStates(now), reservations.reservedSerials(now));
		if (picked.isEmpty()) {
			log.debug("no idle robot for mission {}", mission.id());
			return mission;
		}

		RobotStateMessage robot = picked.get();
		Mission assigned = mission.assignedTo(robot.serialNumber());

		// 발행 전에 예약한다. 같은 패스의 다음 미션이 이 로봇을 다시 고르지 못하게 하는 것이 핵심.
		reservations.reserve(robot.serialNumber(), assigned.orderId(), now);
		try {
			publisher.publish(orderTopic(robot), toOrder(assigned, robot, route));
		}
		catch (RuntimeException e) {
			reservations.release(robot.serialNumber());
			log.error("mission {} order 발행 실패: {}", assigned.id(), e.getMessage());
			return mission;
		}

		log.info("mission {} assigned to {} ({} nodes)", assigned.id(), robot.serialNumber(), route.size());
		return assigned;
	}

	/**
	 * 가용하면서 예약되지 않은 로봇 중 배터리가 가장 많은 것. 순수 함수라 단위 테스트 대상.
	 */
	static Optional<RobotStateMessage> pickIdleRobot(Collection<RobotStateMessage> candidates,
			Set<String> reservedSerials) {
		return candidates.stream()
				.filter(RobotStateMessage::isAvailable)
				.filter(r -> !reservedSerials.contains(r.serialNumber()))
				.max(Comparator.comparingDouble(RobotStateMessage::batteryCharge));
	}

	/**
	 * from -> to 경로. 통로를 가로지르는 이동이면 경유점을 하나 끼운다.
	 *
	 * 한계: 같은 쪽 안에서의 이동은 여전히 직행이라 랙 사이를 지날 수 있다.
	 * 제대로 하려면 통행 가능 그래프 위에서 A* 를 돌려야 한다.
	 */
	private List<WarehouseMap.Node> buildRoute(Mission mission) {
		Optional<WarehouseMap.Node> from = warehouseMap.find(mission.fromNode());
		Optional<WarehouseMap.Node> to = warehouseMap.find(mission.toNode());
		if (from.isEmpty() || to.isEmpty()) {
			return List.of();
		}

		List<WarehouseMap.Node> corridor = warehouseMap.byKind(WarehouseMap.NodeKind.WAYPOINT);
		return corridorWaypoint(from.get(), to.get(), corridor)
				.map(via -> List.of(from.get(), via, to.get()))
				.orElseGet(() -> List.of(from.get(), to.get()));
	}

	/**
	 * 통로를 건너야 하면 우회 거리가 가장 짧은 경유점을 고른다. 순수 함수라 단위 테스트 대상.
	 *
	 * 통로는 경유점들이 늘어선 세로선이다. 출발지와 목적지가 그 선의 반대편에 있으면
	 * 직선으로 갈 때 사이의 랙을 관통하므로 경유점을 거쳐야 한다.
	 */
	static Optional<WarehouseMap.Node> corridorWaypoint(WarehouseMap.Node from, WarehouseMap.Node to,
			List<WarehouseMap.Node> corridor) {
		if (corridor.isEmpty()) {
			return Optional.empty();
		}
		double corridorX = corridor.stream().mapToDouble(WarehouseMap.Node::x).average().orElseThrow();
		if (!crossesCorridor(from, to, corridorX)) {
			return Optional.empty();
		}
		return corridor.stream()
				.min(Comparator.comparingDouble(w -> distance(from, w) + distance(w, to)));
	}

	private static boolean crossesCorridor(WarehouseMap.Node from, WarehouseMap.Node to, double corridorX) {
		return (from.x() - corridorX) * (to.x() - corridorX) < 0;
	}

	private static double distance(WarehouseMap.Node a, WarehouseMap.Node b) {
		return Math.hypot(a.x() - b.x(), a.y() - b.y());
	}

	private String orderTopic(RobotStateMessage robot) {
		String manufacturer = robot.manufacturer() != null ? robot.manufacturer() : props.mqtt().manufacturer();
		return "uagv/v2/" + manufacturer + "/" + robot.serialNumber() + "/order";
	}

	private static Map<String, Object> toOrder(Mission mission, RobotStateMessage robot,
			List<WarehouseMap.Node> route) {
		List<Map<String, Object>> nodes = new ArrayList<>();
		List<Map<String, Object>> edges = new ArrayList<>();

		for (int i = 0; i < route.size(); i++) {
			WarehouseMap.Node node = route.get(i);
			nodes.add(nodeEntry(node, i * 2L));
			if (i > 0) {
				edges.add(edgeEntry(route.get(i - 1), node, i * 2L - 1));
			}
		}

		Map<String, Object> order = new LinkedHashMap<>();
		order.put("headerId", 0);
		order.put("timestamp", OffsetDateTime.now().toString());
		order.put("version", VDA_VERSION);
		order.put("manufacturer", robot.manufacturer() == null ? "" : robot.manufacturer());
		order.put("serialNumber", robot.serialNumber());
		order.put("orderId", mission.orderId());
		order.put("orderUpdateId", 0);
		order.put("nodes", nodes);
		order.put("edges", edges);
		return order;
	}

	private static Map<String, Object> nodeEntry(WarehouseMap.Node node, long sequenceId) {
		return Map.of(
				"nodeId", node.nodeId(),
				"sequenceId", sequenceId,
				"released", true,
				"nodePosition", Map.of(
						"x", node.x(),
						"y", node.y(),
						"mapId", WarehouseMap.MAP_ID),
				"actions", List.of());
	}

	private static Map<String, Object> edgeEntry(WarehouseMap.Node from, WarehouseMap.Node to, long sequenceId) {
		return Map.of(
				"edgeId", from.nodeId() + "-" + to.nodeId(),
				"sequenceId", sequenceId,
				"released", true,
				"startNodeId", from.nodeId(),
				"endNodeId", to.nodeId(),
				"actions", List.of());
	}
}
