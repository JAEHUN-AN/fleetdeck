package com.fleetdeck.map;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 프론트가 맵에 노드를 그리고 주문 생성 폼을 채우는 데 쓴다. */
@RestController
@RequestMapping("/api/map")
public class MapController {

	private final WarehouseMap warehouseMap;

	public MapController(WarehouseMap warehouseMap) {
		this.warehouseMap = warehouseMap;
	}

	@GetMapping
	public Map<String, Object> map() {
		return Map.of(
				"mapId", WarehouseMap.MAP_ID,
				"nodes", nodes());
	}

	@GetMapping("/nodes")
	public List<WarehouseMap.Node> nodes() {
		return warehouseMap.all();
	}
}
