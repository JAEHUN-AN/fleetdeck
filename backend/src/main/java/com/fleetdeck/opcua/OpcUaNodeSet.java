package com.fleetdeck.opcua;

import java.util.Map;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * 탐색으로 찾아낸 설비 하나의 변수 노드들.
 *
 * <p>NodeId 를 코드에 박지 않고 서버를 브라우징해서 만든다. 현장에서 설비가 몇 대인지,
 * 태그 이름이 무엇인지는 서버가 안다. 박아두면 설비가 늘 때마다 배포해야 한다.
 */
record OpcUaNodeSet(String equipmentId, Map<String, NodeId> variables) {

	OpcUaNodeSet {
		variables = Map.copyOf(variables);
	}
}
