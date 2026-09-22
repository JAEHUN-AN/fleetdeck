package com.fleetdeck.opcua;

import static org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.Unsigned.uint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UShort;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseDirection;
import org.eclipse.milo.opcua.stack.core.types.enumerated.BrowseResultMask;
import org.eclipse.milo.opcua.stack.core.types.enumerated.NodeClass;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseDescription;
import org.eclipse.milo.opcua.stack.core.types.structured.BrowseResult;
import org.eclipse.milo.opcua.stack.core.types.structured.ReferenceDescription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Equipment 폴더 아래를 훑어 설비와 변수 노드를 찾는다.
 *
 * <pre>
 *   Objects/Equipment/UA-SORTER-01/{Status, ThroughputPerMin, ...}
 * </pre>
 */
final class OpcUaBrowser {

	private static final Logger log = LoggerFactory.getLogger(OpcUaBrowser.class);

	private OpcUaBrowser() {
	}

	/**
	 * @param namespaceUri 시뮬레이터가 등록한 네임스페이스. 인덱스는 서버가 정하므로 URI 로 찾는다.
	 * @param folderName   설비들이 매달린 폴더의 BrowseName
	 */
	static List<OpcUaNodeSet> discover(OpcUaClient client, String namespaceUri, String folderName)
			throws UaException {
		UShort namespaceIndex = client.getNamespaceTable().getIndex(namespaceUri);
		if (namespaceIndex == null) {
			throw new UaException(org.eclipse.milo.opcua.stack.core.StatusCodes.Bad_NotFound,
					"네임스페이스를 서버가 모른다: " + namespaceUri);
		}

		NodeId folder = new NodeId(namespaceIndex, folderName);
		List<OpcUaNodeSet> found = new ArrayList<>();
		for (ReferenceDescription equipment : children(client, folder, NodeClass.Object)) {
			String equipmentId = equipment.getBrowseName().getName();
			NodeId equipmentNode = equipment.getNodeId().toNodeId(client.getNamespaceTable())
					.orElse(null);
			if (equipmentId == null || equipmentNode == null) {
				continue;
			}
			Map<String, NodeId> variables = new LinkedHashMap<>();
			for (ReferenceDescription variable : children(client, equipmentNode, NodeClass.Variable)) {
				String name = variable.getBrowseName().getName();
				variable.getNodeId().toNodeId(client.getNamespaceTable())
						.ifPresent(id -> variables.put(name, id));
			}
			if (variables.isEmpty()) {
				log.warn("OPC UA 설비 {} 에 변수 노드가 없다 — 건너뛴다", equipmentId);
				continue;
			}
			found.add(new OpcUaNodeSet(equipmentId, variables));
		}
		return List.copyOf(found);
	}

	private static List<ReferenceDescription> children(OpcUaClient client, NodeId node,
			NodeClass nodeClass) throws UaException {
		BrowseDescription browse = new BrowseDescription(
				node,
				BrowseDirection.Forward,
				NodeIds.HierarchicalReferences,
				true,
				uint(nodeClass.getValue()),
				uint(BrowseResultMask.All.getValue()));

		BrowseResult result = client.browse(browse);
		ReferenceDescription[] references = result.getReferences();
		// continuationPoint 는 다루지 않는다. 이 주소공간은 한 번에 들어온다.
		if (result.getContinuationPoint() != null && result.getContinuationPoint().isNotNull()) {
			log.warn("browse 결과가 잘렸다 ({}). 설비가 더 있을 수 있다.", node);
		}
		return references == null ? List.of() : List.of(references);
	}
}
