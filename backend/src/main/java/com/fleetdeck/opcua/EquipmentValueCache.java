package com.fleetdeck.opcua;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 설비별로 변수값 조각을 모은다.
 *
 * <p>OPC UA 구독은 변수 단위로 알림이 온다. 위치만 바뀌면 위치 하나만 온다.
 * 조각마다 설비 상태를 만들어 적재하면 한 틱에 변수 수만큼 행이 쌓이고,
 * 그 행들은 대부분 필드가 빈 채로 남는다. 여기서 마지막 값을 들고 있다가
 * 완성된 스냅샷만 내보낸다.
 */
class EquipmentValueCache {

	private final ConcurrentMap<String, Entry> entries = new ConcurrentHashMap<>();

	/** 변수 하나의 새 값. {@code value} 가 null 이면 무시한다 (이전 정상값을 지우지 않는다). */
	void put(String equipmentId, String variable, Object value, Instant sourceTime) {
		if (value == null) {
			return;
		}
		entries.computeIfAbsent(equipmentId, id -> new Entry()).put(variable, value, sourceTime);
	}

	Optional<Snapshot> snapshot(String equipmentId) {
		Entry entry = entries.get(equipmentId);
		return entry == null ? Optional.empty() : entry.snapshot();
	}

	void clear() {
		entries.clear();
	}

	/** 설비 하나의 최신 변수값과, 그 중 가장 최근의 sourceTimestamp. */
	record Snapshot(Map<String, Object> values, Instant sourceTime) {
	}

	private static final class Entry {

		private final Map<String, Object> values = new HashMap<>();
		private Instant newest;

		synchronized void put(String variable, Object value, Instant sourceTime) {
			values.put(variable, value);
			if (sourceTime != null && (newest == null || sourceTime.isAfter(newest))) {
				newest = sourceTime;
			}
		}

		synchronized Optional<Snapshot> snapshot() {
			if (!OpcUaValueMapper.isComplete(values)) {
				return Optional.empty();
			}
			return Optional.of(new Snapshot(Map.copyOf(values), newest));
		}
	}
}
