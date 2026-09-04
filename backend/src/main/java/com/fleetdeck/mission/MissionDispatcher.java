package com.fleetdeck.mission;

import com.fleetdeck.config.FleetdeckProperties;
import com.fleetdeck.mqtt.MqttPublisher;
import com.fleetdeck.robot.RobotRegistry;
import com.fleetdeck.robot.RobotStateMessage;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 미션을 유휴 로봇에 배정하고 VDA5050 order 를 발행한다.
 * W5 에서 거리 기반 선택, 재배정, 교통 제어로 확장.
 */
@Component
public class MissionDispatcher {

	private static final Logger log = LoggerFactory.getLogger(MissionDispatcher.class);
	private static final String VDA_VERSION = "2.0.0";

	private final RobotRegistry robots;
	private final MqttPublisher publisher;
	private final FleetdeckProperties props;

	public MissionDispatcher(RobotRegistry robots, MqttPublisher publisher, FleetdeckProperties props) {
		this.robots = robots;
		this.publisher = publisher;
		this.props = props;
	}

	/** 배정에 성공하면 ASSIGNED 상태의 새 Mission, 유휴 로봇이 없으면 원본을 그대로 돌려준다. */
	public Mission tryAssign(Mission mission) {
		Optional<RobotStateMessage> picked = pickIdleRobot(robots.all());
		if (picked.isEmpty()) {
			log.info("no idle robot for mission {}", mission.id());
			return mission;
		}
		RobotStateMessage robot = picked.get();
		Mission assigned = mission.assignedTo(robot.serialNumber());
		publisher.publish(orderTopic(robot), toOrder(assigned, robot));
		log.info("mission {} assigned to {}", assigned.id(), robot.serialNumber());
		return assigned;
	}

	/** 유휴 로봇 중 배터리가 가장 많은 것. 순수 함수라 단위 테스트 대상. */
	static Optional<RobotStateMessage> pickIdleRobot(Collection<RobotStateMessage> candidates) {
		return candidates.stream()
				.filter(RobotStateMessage::isIdle)
				.max(Comparator.comparingDouble(RobotStateMessage::batteryCharge));
	}

	private String orderTopic(RobotStateMessage robot) {
		String manufacturer = robot.manufacturer() != null ? robot.manufacturer() : props.mqtt().manufacturer();
		return "uagv/v2/" + manufacturer + "/" + robot.serialNumber() + "/order";
	}

	/** VDA5050 order 최소 골격. 노드 좌표·액션은 W5 에서 맵 데이터와 함께 채운다. */
	private static Map<String, Object> toOrder(Mission mission, RobotStateMessage robot) {
		return Map.of(
				"headerId", 0,
				"timestamp", OffsetDateTime.now().toString(),
				"version", VDA_VERSION,
				"manufacturer", robot.manufacturer() == null ? "" : robot.manufacturer(),
				"serialNumber", robot.serialNumber(),
				"orderId", mission.orderId(),
				"orderUpdateId", 0,
				"nodes", List.of(
						Map.of("nodeId", mission.fromNode(), "sequenceId", 0, "released", true),
						Map.of("nodeId", mission.toNode(), "sequenceId", 2, "released", true)),
				"edges", List.of(
						Map.of("edgeId", mission.fromNode() + "-" + mission.toNode(), "sequenceId", 1,
								"released", true, "startNodeId", mission.fromNode(), "endNodeId", mission.toNode())));
	}
}
