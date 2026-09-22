package com.fleetdeck.fdc;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FDC 경보 조회.
 *
 * <p>실시간은 STOMP({@code /topic/alarms}) 로 간다. 이 API 는 화면을 새로 열었을 때
 * 최근 이력을 채우는 용도다 — 없으면 새로고침 직후 화면이 비어 아무 일도 없는 것처럼 보인다.
 */
@RestController
@RequestMapping("/api/alarms")
public class AlarmController {

	private static final int DEFAULT_LIMIT = 50;
	private static final int MAX_LIMIT = 200;

	private final SensorRepository repository;

	public AlarmController(SensorRepository repository) {
		this.repository = repository;
	}

	@GetMapping
	public List<SensorTelemetryService.AlarmView> recent(
			@RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit) {
		return repository.recentAlarms(Math.min(Math.max(limit, 1), MAX_LIMIT));
	}
}
