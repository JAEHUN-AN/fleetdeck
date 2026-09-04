package com.fleetdeck.robot;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/robots")
public class RobotController {

	private final RobotRegistry registry;

	public RobotController(RobotRegistry registry) {
		this.registry = registry;
	}

	@GetMapping
	public List<RobotStateMessage> list() {
		return registry.all();
	}

	@GetMapping("/{serial}")
	public ResponseEntity<RobotStateMessage> get(@PathVariable String serial) {
		return registry.find(serial)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
