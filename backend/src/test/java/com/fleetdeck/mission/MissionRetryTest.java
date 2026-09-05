package com.fleetdeck.mission;

import static org.assertj.core.api.Assertions.assertThat;

import com.fleetdeck.mission.Mission.MissionStatus;
import com.fleetdeck.mission.Mission.MissionType;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class MissionRetryTest {

	private static final int MAX_RETRIES = 3;

	private static Mission mission(MissionStatus status, String robot, int retryCount) {
		OffsetDateTime now = OffsetDateTime.now();
		return new Mission(7L, MissionType.TRANSPORT, "P01", "D02", status, robot, "WMS-1",
				retryCount, now, now);
	}

	@Test
	void reclaimReturnsToPendingAndCountsTheRetry() {
		Mission stuck = mission(MissionStatus.ASSIGNED, "AMR-001", 0);

		Mission reclaimed = stuck.reclaimed(MAX_RETRIES);

		assertThat(reclaimed.status()).isEqualTo(MissionStatus.PENDING);
		assertThat(reclaimed.retryCount()).isEqualTo(1);
		assertThat(reclaimed.assignedRobot()).isNull();
		assertThat(stuck.retryCount()).isZero();  // 원본 불변
	}

	@Test
	void retriesAccumulateUpToTheCap() {
		Mission m = mission(MissionStatus.ASSIGNED, "AMR-001", 0);

		for (int expected = 1; expected <= MAX_RETRIES; expected++) {
			m = m.reclaimed(MAX_RETRIES);
			assertThat(m.status()).isEqualTo(MissionStatus.PENDING);
			assertThat(m.retryCount()).isEqualTo(expected);
			m = m.assignedTo("AMR-00" + expected);  // 재배정
		}

		assertThat(m.retryCount()).isEqualTo(MAX_RETRIES);
	}

	@Test
	void exceedingTheCapGivesUpWithFailed() {
		Mission exhausted = mission(MissionStatus.ASSIGNED, "AMR-001", MAX_RETRIES);

		Mission givenUp = exhausted.reclaimed(MAX_RETRIES);

		assertThat(givenUp.status()).isEqualTo(MissionStatus.FAILED);
		assertThat(givenUp.retryCount()).isEqualTo(MAX_RETRIES + 1);
		assertThat(givenUp.assignedRobot()).isNull();
		assertThat(givenUp.isGivenUp(MAX_RETRIES)).isTrue();
	}

	@Test
	void zeroCapGivesUpOnFirstReclaim() {
		Mission stuck = mission(MissionStatus.ASSIGNED, "AMR-001", 0);

		assertThat(stuck.reclaimed(0).status()).isEqualTo(MissionStatus.FAILED);
	}

	@Test
	void retryCountSurvivesNormalTransitions() {
		Mission retried = mission(MissionStatus.PENDING, null, 2);

		Mission assigned = retried.assignedTo("AMR-005");
		Mission running = assigned.running();
		Mission done = running.completed();

		assertThat(assigned.retryCount()).isEqualTo(2);
		assertThat(running.retryCount()).isEqualTo(2);
		assertThat(done.retryCount()).isEqualTo(2);
		assertThat(done.status()).isEqualTo(MissionStatus.DONE);
	}

	@Test
	void robotErrorFailureIsNotCountedAsGivingUpOnRetries() {
		// 로봇 에러로 인한 FAILED 는 재시도 소진과 구분된다.
		Mission broken = mission(MissionStatus.RUNNING, "AMR-001", 1).failed();

		assertThat(broken.status()).isEqualTo(MissionStatus.FAILED);
		assertThat(broken.retryCount()).isEqualTo(1);
		assertThat(broken.isGivenUp(MAX_RETRIES)).isFalse();
		assertThat(broken.assignedRobot()).isEqualTo("AMR-001");  // 원인 추적용으로 남긴다
	}
}
