package com.fleetdeck.history;

import java.time.Duration;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 시계열 조회 API. 대시보드 차트가 쓴다.
 *
 * window 는 조회 구간, bucket 은 집계 단위다. 점 개수 = window / bucket 이므로
 * 구간을 늘리면 bucket 도 같이 늘려야 응답이 커지지 않는다.
 */
@RestController
@RequestMapping("/api/history")
public class HistoryController {

	private static final Duration MAX_WINDOW = Duration.ofDays(7);
	private static final Duration MIN_BUCKET = Duration.ofSeconds(5);
	private static final int MAX_POINTS = 500;

	private final HistoryRepository repository;

	public HistoryController(HistoryRepository repository) {
		this.repository = repository;
	}

	@GetMapping("/robot-battery")
	public List<HistoryPoint> robotBattery(
			@RequestParam(defaultValue = "PT30M") Duration window,
			@RequestParam(defaultValue = "PT1M") Duration bucket) {
		return repository.robotBattery(clampWindow(window), clampBucket(window, bucket));
	}

	@GetMapping("/equipment-throughput")
	public List<HistoryPoint> equipmentThroughput(
			@RequestParam(defaultValue = "PT30M") Duration window,
			@RequestParam(defaultValue = "PT1M") Duration bucket) {
		return repository.equipmentThroughput(clampWindow(window), clampBucket(window, bucket));
	}

	@GetMapping("/fleet-driving-ratio")
	public List<HistoryPoint> fleetDrivingRatio(
			@RequestParam(defaultValue = "PT30M") Duration window,
			@RequestParam(defaultValue = "PT1M") Duration bucket) {
		return repository.fleetDrivingRatio(clampWindow(window), clampBucket(window, bucket));
	}

	static Duration clampWindow(Duration window) {
		if (window == null || window.isNegative() || window.isZero()) {
			return Duration.ofMinutes(30);
		}
		return window.compareTo(MAX_WINDOW) > 0 ? MAX_WINDOW : window;
	}

	/**
	 * 점이 MAX_POINTS 를 넘지 않도록 bucket 을 키운다.
	 * 클라이언트가 하루치를 5초 단위로 요청해도 응답이 폭발하지 않는다.
	 */
	static Duration clampBucket(Duration window, Duration bucket) {
		Duration safeWindow = clampWindow(window);
		Duration floor = bucket == null || bucket.compareTo(MIN_BUCKET) < 0 ? MIN_BUCKET : bucket;
		// 올림해야 한다. 내림하면 버킷이 필요한 것보다 작아져 점이 상한을 넘는다
		// (하루 86,400초 / 500 = 172.8 -> 172 로 내리면 502점).
		long minSeconds = Math.max(1, ceilDiv(safeWindow.toSeconds(), MAX_POINTS));
		return floor.toSeconds() < minSeconds ? Duration.ofSeconds(minSeconds) : floor;
	}

	private static long ceilDiv(long dividend, long divisor) {
		return (dividend + divisor - 1) / divisor;
	}
}
