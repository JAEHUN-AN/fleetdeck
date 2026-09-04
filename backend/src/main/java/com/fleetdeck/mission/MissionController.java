package com.fleetdeck.mission;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/missions")
public class MissionController {

	private final MissionService service;

	public MissionController(MissionService service) {
		this.service = service;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public Mission create(@Valid @RequestBody MissionRequest request) {
		return service.create(request);
	}

	@GetMapping
	public List<Mission> recent() {
		return service.recent();
	}
}
