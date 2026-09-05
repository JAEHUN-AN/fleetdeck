package com.fleetdeck.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetdeck.map.WarehouseMap.Node;
import com.fleetdeck.map.WarehouseMap.NodeKind;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 통로 경유점 삽입. 통로는 x=20 에 늘어선 W01~W03 이다.
 */
class RoutePlanningTest {

	private static final Node W01 = new Node("W01", 20.0, 6.0, NodeKind.WAYPOINT);
	private static final Node W02 = new Node("W02", 20.0, 12.0, NodeKind.WAYPOINT);
	private static final Node W03 = new Node("W03", 20.0, 18.0, NodeKind.WAYPOINT);
	private static final List<Node> CORRIDOR = List.of(W01, W02, W03);

	private static final Node P01 = new Node("P01", 4.0, 6.0, NodeKind.PICK);   // 좌측
	private static final Node P03 = new Node("P03", 4.0, 16.0, NodeKind.PICK);  // 좌측
	private static final Node P07 = new Node("P07", 30.0, 8.0, NodeKind.PICK);  // 우측
	private static final Node D01 = new Node("D01", 5.0, 19.0, NodeKind.DROP);  // 좌측
	private static final Node D03 = new Node("D03", 29.0, 19.0, NodeKind.DROP); // 우측

	@Test
	void sameSideNeedsNoWaypoint() {
		// 둘 다 통로 왼쪽이면 건널 일이 없다.
		assertThat(MissionDispatcher.corridorWaypoint(P01, D01, CORRIDOR)).isEmpty();
		assertThat(MissionDispatcher.corridorWaypoint(P07, D03, CORRIDOR)).isEmpty();
	}

	@Test
	void crossingTheCorridorInsertsAWaypoint() {
		Optional<Node> via = MissionDispatcher.corridorWaypoint(P01, P07, CORRIDOR);

		assertThat(via).isPresent();
		assertThat(via.get().kind()).isEqualTo(NodeKind.WAYPOINT);
	}

	@Test
	void picksTheWaypointWithTheShortestDetour() {
		// P01(4,6) -> P07(30,8) 은 두 점 모두 y 가 낮아 W01(20,6) 이 가장 가깝다.
		assertThat(MissionDispatcher.corridorWaypoint(P01, P07, CORRIDOR))
				.map(Node::nodeId).hasValue("W01");

		// P03(4,16) -> D03(29,19) 는 y 가 높아 W03(20,18) 쪽이 유리하다.
		assertThat(MissionDispatcher.corridorWaypoint(P03, D03, CORRIDOR))
				.map(Node::nodeId).hasValue("W03");
	}

	@Test
	void directionDoesNotMatter() {
		Optional<Node> forward = MissionDispatcher.corridorWaypoint(P01, P07, CORRIDOR);
		Optional<Node> backward = MissionDispatcher.corridorWaypoint(P07, P01, CORRIDOR);

		assertThat(forward.map(Node::nodeId)).isEqualTo(backward.map(Node::nodeId));
	}

	@Test
	void noCorridorDefinedMeansNoInsertion() {
		assertThat(MissionDispatcher.corridorWaypoint(P01, P07, List.of())).isEmpty();
	}

	@Test
	void nodeSittingOnTheCorridorIsNotACrossing() {
		// 출발지가 통로 위에 있으면 (x - corridorX) 가 0 이라 건너는 게 아니다.
		Node onCorridor = new Node("X", 20.0, 10.0, NodeKind.PICK);

		assertThat(MissionDispatcher.corridorWaypoint(onCorridor, P07, CORRIDOR)).isEmpty();
		assertThat(MissionDispatcher.corridorWaypoint(P01, onCorridor, CORRIDOR)).isEmpty();
	}
}
