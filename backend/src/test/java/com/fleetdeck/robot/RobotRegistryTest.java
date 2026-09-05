package com.fleetdeck.robot;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetdeck.config.FleetdeckProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RobotRegistryTest {

	private static final Instant T0 = Instant.parse("2026-09-05T09:00:00Z");
	private static final Duration OFFLINE_AFTER = Duration.ofSeconds(15);
	private static final Duration EVICT_AFTER = Duration.ofMinutes(5);

	private static RobotRegistry newRegistry() {
		FleetdeckProperties props = new FleetdeckProperties(
				null, null, null, new FleetdeckProperties.Robot(OFFLINE_AFTER, EVICT_AFTER));
		return new RobotRegistry(props);
	}

	@Test
	void robotIsOnlineRightAfterReporting() {
		RobotRegistry registry = newRegistry();

		registry.upsert(RobotStates.idle("AMR-001", 80.0), T0);

		assertThat(registry.isOnline("AMR-001", T0)).isTrue();
		assertThat(registry.onlineStates(T0)).extracting(RobotStateMessage::serialNumber)
				.containsExactly("AMR-001");
	}

	@Test
	void robotGoesOfflineAfterTheThreshold() {
		RobotRegistry registry = newRegistry();
		registry.upsert(RobotStates.idle("AMR-001", 80.0), T0);

		assertThat(registry.isOnline("AMR-001", T0.plusSeconds(14))).isTrue();
		assertThat(registry.isOnline("AMR-001", T0.plusSeconds(16))).isFalse();
	}

	@Test
	void offlineRobotIsExcludedFromDispatchCandidatesButStillListed() {
		// 핵심: 마지막 상태가 "유휴" 라도 통신이 끊겼으면 배정하면 안 된다.
		RobotRegistry registry = newRegistry();
		registry.upsert(RobotStates.idle("AMR-001", 90.0), T0);
		registry.upsert(RobotStates.idle("AMR-002", 50.0), T0.plusSeconds(20));

		Instant now = T0.plusSeconds(20);

		assertThat(registry.onlineStates(now)).extracting(RobotStateMessage::serialNumber)
				.containsExactly("AMR-002");
		// 화면에서는 사라지지 않고 오프라인으로 보여야 한다.
		assertThat(registry.views(now)).extracting(v -> v.state().serialNumber())
				.containsExactly("AMR-001", "AMR-002");
		assertThat(registry.views(now)).extracting(RobotView::online)
				.containsExactly(false, true);
	}

	@Test
	void reportingAgainBringsARobotBackOnline() {
		RobotRegistry registry = newRegistry();
		registry.upsert(RobotStates.idle("AMR-001", 80.0), T0);
		assertThat(registry.isOnline("AMR-001", T0.plusSeconds(30))).isFalse();

		registry.upsert(RobotStates.idle("AMR-001", 79.0), T0.plusSeconds(30));

		assertThat(registry.isOnline("AMR-001", T0.plusSeconds(31))).isTrue();
	}

	@Test
	void viewCarriesLastSeenTime() {
		RobotRegistry registry = newRegistry();
		registry.upsert(RobotStates.idle("AMR-001", 80.0), T0);

		assertThat(registry.view("AMR-001", T0)).isPresent()
				.hasValueSatisfying(v -> {
					assertThat(v.lastSeenAt()).isEqualTo(T0);
					assertThat(v.online()).isTrue();
				});
	}

	@Test
	void unknownRobotHasNoViewAndIsNotOnline() {
		RobotRegistry registry = newRegistry();

		assertThat(registry.view("AMR-999", T0)).isEmpty();
		assertThat(registry.isOnline("AMR-999", T0)).isFalse();
	}

	@Test
	void evictRemovesRobotsSilentBeyondEvictAfter() {
		// 부하 테스트에서 50대를 8대로 줄였을 때 유령 42대가 남던 상황.
		RobotRegistry registry = newRegistry();
		registry.upsert(RobotStates.idle("AMR-001", 80.0), T0);
		registry.upsert(RobotStates.idle("AMR-002", 80.0), T0);
		registry.upsert(RobotStates.idle("AMR-003", 80.0), T0.plusSeconds(400));

		List<String> gone = registry.evictStale(T0.plusSeconds(400));

		assertThat(gone).containsExactlyInAnyOrder("AMR-001", "AMR-002");
		assertThat(registry.all()).extracting(RobotStateMessage::serialNumber)
				.containsExactly("AMR-003");
		assertThat(registry.view("AMR-001", T0.plusSeconds(400))).isEmpty();
	}

	@Test
	void evictKeepsRobotsWithinTheWindow() {
		RobotRegistry registry = newRegistry();
		registry.upsert(RobotStates.idle("AMR-001", 80.0), T0);

		assertThat(registry.evictStale(T0.plusSeconds(60))).isEmpty();
		assertThat(registry.all()).hasSize(1);
	}
}
