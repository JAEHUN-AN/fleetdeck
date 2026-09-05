package com.fleetdeck.robot;

import java.time.Instant;
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
	public List<RobotView> list() {
		return registry.views(Instant.now());
	}

	@GetMapping("/{serial}")
	public ResponseEntity<RobotView> get(@PathVariable String serial) {
		return registry.view(serial, Instant.now())
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}
}
