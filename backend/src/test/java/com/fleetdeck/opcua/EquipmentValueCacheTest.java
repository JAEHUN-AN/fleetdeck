package com.fleetdeck.opcua;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 구독은 변수 단위로 도착한다. 값 하나가 바뀔 때마다 설비 상태를 한 건씩 적재하면
 * 한 틱에 6건이 쌓인다. 캐시가 조각을 모아 한 번만 내보내는지 본다.
 */
class EquipmentValueCacheTest {

	private final EquipmentValueCache cache = new EquipmentValueCache();

	@Test
	void partialSnapshotIsNotEmitted() {
		cache.put("UA-SORTER-01", "PositionX", 8.0, Instant.EPOCH);
		assertThat(cache.snapshot("UA-SORTER-01")).isEmpty();
	}

	@Test
	void emitsOnceStatusHasArrived() {
		cache.put("UA-SORTER-01", "PositionX", 8.0, Instant.EPOCH);
		cache.put("UA-SORTER-01", "Status", "RUNNING", Instant.EPOCH);

		Optional<EquipmentValueCache.Snapshot> snapshot = cache.snapshot("UA-SORTER-01");
		assertThat(snapshot).isPresent();
		assertThat(snapshot.get().values()).containsEntry("Status", "RUNNING")
				.containsEntry("PositionX", 8.0);
	}

	@Test
	void laterValueReplacesEarlierOne() {
		cache.put("E", "Status", "RUNNING", Instant.EPOCH);
		cache.put("E", "Status", "ALARM", Instant.EPOCH.plusSeconds(1));

		assertThat(cache.snapshot("E").orElseThrow().values()).containsEntry("Status", "ALARM");
	}

	@Test
	void snapshotCarriesTheNewestSourceTimestamp() {
		cache.put("E", "Status", "RUNNING", Instant.EPOCH.plusSeconds(5));
		cache.put("E", "PositionX", 1.0, Instant.EPOCH.plusSeconds(9));
		cache.put("E", "PositionY", 2.0, Instant.EPOCH.plusSeconds(3));

		assertThat(cache.snapshot("E").orElseThrow().sourceTime()).isEqualTo(Instant.EPOCH.plusSeconds(9));
	}

	@Test
	void equipmentAreIsolated() {
		cache.put("A", "Status", "RUNNING", Instant.EPOCH);
		cache.put("B", "PositionX", 1.0, Instant.EPOCH);

		assertThat(cache.snapshot("A")).isPresent();
		assertThat(cache.snapshot("B")).isEmpty();
	}

	@Test
	void nullValueIsIgnoredSoItCannotErasePreviousState() {
		// BadNodeIdUnknown 등으로 값이 비어 오는 경우. 마지막 정상값을 지우면 안 된다.
		cache.put("E", "Status", "RUNNING", Instant.EPOCH);
		cache.put("E", "Status", null, Instant.EPOCH.plusSeconds(1));

		assertThat(cache.snapshot("E").orElseThrow().values()).containsEntry("Status", "RUNNING");
	}
}
