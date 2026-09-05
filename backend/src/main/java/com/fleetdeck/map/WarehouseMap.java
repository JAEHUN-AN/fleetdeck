package com.fleetdeck.map;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 창고 노드 좌표표. RCS 가 order 에 nodePosition 을 실어 보내야 로봇이 주행할 수 있으므로,
 * 노드 ID -> 좌표 매핑의 소유자는 관제 쪽이다.
 *
 * 지금은 코드에 상수로 둔다. 실제 현장에서는 맵 편집기나 DB 에서 읽어온다.
 */
@Component
public class WarehouseMap {

	public static final String MAP_ID = "warehouse-a";

	private static final Map<String, Node> NODES = buildNodes();

	public record Node(String nodeId, double x, double y, NodeKind kind) {
	}

	public enum NodeKind {
		/** 입고 지점 */
		PICK,
		/** 출고·분류 투입 지점 */
		DROP,
		/** 통로 경유점 */
		WAYPOINT
	}

	private static Map<String, Node> buildNodes() {
		Map<String, Node> nodes = new LinkedHashMap<>();
		// 입고 랙 (좌측)
		put(nodes, new Node("P01", 4.0, 6.0, NodeKind.PICK));
		put(nodes, new Node("P02", 4.0, 11.0, NodeKind.PICK));
		put(nodes, new Node("P03", 4.0, 16.0, NodeKind.PICK));
		put(nodes, new Node("P04", 12.0, 6.0, NodeKind.PICK));
		put(nodes, new Node("P05", 12.0, 11.0, NodeKind.PICK));
		put(nodes, new Node("P06", 12.0, 16.0, NodeKind.PICK));
		// 통로 경유점 (중앙 세로 통로)
		put(nodes, new Node("W01", 20.0, 6.0, NodeKind.WAYPOINT));
		put(nodes, new Node("W02", 20.0, 12.0, NodeKind.WAYPOINT));
		put(nodes, new Node("W03", 20.0, 18.0, NodeKind.WAYPOINT));
		// 소터 투입 지점. 소터 설비(y=22) 앞에 서되, 정차한 로봇이 설비와 겹치지 않게 3m 띄운다.
		put(nodes, new Node("D01", 5.0, 19.0, NodeKind.DROP));
		put(nodes, new Node("D02", 17.0, 19.0, NodeKind.DROP));
		put(nodes, new Node("D03", 29.0, 19.0, NodeKind.DROP));
		// 우측 보관 구역
		put(nodes, new Node("P07", 30.0, 8.0, NodeKind.PICK));
		put(nodes, new Node("P08", 30.0, 14.0, NodeKind.PICK));
		put(nodes, new Node("P09", 36.0, 11.0, NodeKind.PICK));
		return Map.copyOf(nodes);
	}

	private static void put(Map<String, Node> nodes, Node node) {
		nodes.put(node.nodeId(), node);
	}

	public Optional<Node> find(String nodeId) {
		return Optional.ofNullable(NODES.get(nodeId));
	}

	public boolean contains(String nodeId) {
		return NODES.containsKey(nodeId);
	}

	public List<Node> all() {
		return List.copyOf(NODES.values());
	}

	public List<Node> byKind(NodeKind kind) {
		return NODES.values().stream().filter(n -> n.kind() == kind).toList();
	}
}
