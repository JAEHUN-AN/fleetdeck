package com.fleetdeck.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetdeck.history.HistoryPoint;
import com.fleetdeck.history.HistoryRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * time_bucket 집계가 실제 TimescaleDB 에서 도는지 확인한다.
 *
 * 이 쿼리는 TimescaleDB 확장 함수라 순수 Postgres 로는 검증할 수 없고,
 * 하이퍼테이블이 제대로 만들어졌는지도 여기서만 드러난다.
 */
class HistoryQueryIT extends IntegrationTestBase {

	private static final String SERIAL = "AMR-960";

	@Autowired
	private HistoryRepository history;

	@Autowired
	private JdbcTemplate jdbc;

	@BeforeEach
	void seed() {
		jdbc.update("DELETE FROM robot_state_log WHERE serial_number = ?", SERIAL);

		// 같은 버킷 안에 두 점을 넣는다. 평균이 나와야 한다.
		OffsetDateTime now = OffsetDateTime.now();
		insert(now.minusSeconds(20), 90.0, true);
		insert(now.minusSeconds(10), 80.0, false);
	}

	private void insert(OffsetDateTime ts, double battery, boolean driving) {
		jdbc.update("""
				INSERT INTO robot_state_log
				  (ts, serial_number, manufacturer, x, y, theta, battery_charge, driving,
				   operating_mode, order_id, error_count, payload)
				VALUES (?, ?, 'fleetdeck', 1.0, 2.0, 0.0, ?, ?, 'AUTOMATIC', '', 0, '{}'::jsonb)
				""", ts, SERIAL, battery, driving);
	}

	@Test
	@DisplayName("time_bucket 이 같은 구간의 배터리를 평균낸다")
	void batteryIsAveragedPerBucket() {
		List<HistoryPoint> points = history.robotBattery(Duration.ofMinutes(5), Duration.ofMinutes(5));

		HistoryPoint mine = points.stream()
				.filter(p -> p.series().equals(SERIAL))
				.findFirst().orElseThrow(() -> new AssertionError("계열 %s 가 없다".formatted(SERIAL)));

		assertThat(mine.value()).isEqualTo(85.0);  // (90 + 80) / 2
		assertThat(mine.bucket()).isNotNull();
	}

	@Test
	@DisplayName("버킷을 좁히면 점이 나뉜다")
	void narrowerBucketSplitsThePoints() {
		List<HistoryPoint> coarse = history.robotBattery(Duration.ofMinutes(5), Duration.ofMinutes(5));
		List<HistoryPoint> fine = history.robotBattery(Duration.ofMinutes(5), Duration.ofSeconds(5));

		long coarseCount = coarse.stream().filter(p -> p.series().equals(SERIAL)).count();
		long fineCount = fine.stream().filter(p -> p.series().equals(SERIAL)).count();

		assertThat(coarseCount).isEqualTo(1);
		assertThat(fineCount).isGreaterThan(coarseCount);
	}

	@Test
	@DisplayName("조회 구간 밖의 데이터는 빠진다")
	void dataOutsideTheWindowIsExcluded() {
		insert(OffsetDateTime.now().minusHours(2), 10.0, false);

		List<HistoryPoint> recent = history.robotBattery(Duration.ofMinutes(5), Duration.ofMinutes(5));

		HistoryPoint mine = recent.stream()
				.filter(p -> p.series().equals(SERIAL)).findFirst().orElseThrow();
		// 2시간 전의 10% 가 섞였다면 평균이 85 보다 훨씬 낮아진다.
		assertThat(mine.value()).isEqualTo(85.0);
	}

	@Test
	@DisplayName("주행 비율은 백분율로 나온다")
	void drivingRatioIsAPercentage() {
		List<HistoryPoint> points = history.fleetDrivingRatio(
				Duration.ofMinutes(5), Duration.ofMinutes(5));

		assertThat(points).isNotEmpty();
		assertThat(points).allSatisfy(p -> {
			assertThat(p.series()).isEqualTo("fleet");
			assertThat(p.value()).isBetween(0.0, 100.0);
		});
	}

	@Test
	@DisplayName("데이터가 없으면 그 계열은 나오지 않는다")
	void emptyResultWhenNothingInWindow() {
		jdbc.update("DELETE FROM robot_state_log WHERE serial_number = ?", SERIAL);

		List<HistoryPoint> points = history.robotBattery(Duration.ofSeconds(5), Duration.ofSeconds(5));

		assertThat(points).noneMatch(p -> p.series().equals(SERIAL));
	}
}
