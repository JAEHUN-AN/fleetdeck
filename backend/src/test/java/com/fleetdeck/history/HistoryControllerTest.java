package com.fleetdeck.history;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class HistoryControllerTest {

	@Test
	void defaultsWhenWindowIsMissingOrNonsensical() {
		assertThat(HistoryController.clampWindow(null)).isEqualTo(Duration.ofMinutes(30));
		assertThat(HistoryController.clampWindow(Duration.ZERO)).isEqualTo(Duration.ofMinutes(30));
		assertThat(HistoryController.clampWindow(Duration.ofMinutes(-5))).isEqualTo(Duration.ofMinutes(30));
	}

	@Test
	void windowIsCappedAtOneWeek() {
		assertThat(HistoryController.clampWindow(Duration.ofDays(30))).isEqualTo(Duration.ofDays(7));
		assertThat(HistoryController.clampWindow(Duration.ofHours(2))).isEqualTo(Duration.ofHours(2));
	}

	@Test
	void bucketIsWidenedSoThePointCountStaysBounded() {
		// 하루치를 5초 단위로 요청하면 17,280 점이 된다. bucket 을 키워 막는다.
		Duration bucket = HistoryController.clampBucket(Duration.ofDays(1), Duration.ofSeconds(5));

		long points = Duration.ofDays(1).toSeconds() / bucket.toSeconds();
		assertThat(points).isLessThanOrEqualTo(500);
	}

	@Test
	void reasonableBucketIsLeftAlone() {
		assertThat(HistoryController.clampBucket(Duration.ofMinutes(30), Duration.ofMinutes(1)))
				.isEqualTo(Duration.ofMinutes(1));
	}

	@Test
	void bucketHasAFloorSoTheQueryCannotDegenerate() {
		assertThat(HistoryController.clampBucket(Duration.ofMinutes(1), Duration.ofMillis(1)))
				.isGreaterThanOrEqualTo(Duration.ofSeconds(5));
		assertThat(HistoryController.clampBucket(Duration.ofMinutes(1), null))
				.isGreaterThanOrEqualTo(Duration.ofSeconds(5));
	}

	@Test
	void intervalStringIsPostgresCompatible() {
		assertThat(HistoryRepository.toInterval(Duration.ofMinutes(2))).isEqualTo("120 seconds");
		assertThat(HistoryRepository.toInterval(Duration.ofSeconds(30))).isEqualTo("30 seconds");
	}
}
