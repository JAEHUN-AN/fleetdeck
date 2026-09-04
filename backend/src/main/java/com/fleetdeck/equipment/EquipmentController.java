package com.fleetdeck.equipment;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

	private final EquipmentRegistry registry;

	public EquipmentController(EquipmentRegistry registry) {
		this.registry = registry;
	}

	@GetMapping
	public List<EquipmentStateMessage> list() {
		return registry.all();
	}
}
