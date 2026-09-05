package com.fleetdeck.common;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * 키별 최신 상태만 보관하는 인메모리 저장소. 히스토리는 DB, 현재값은 여기.
 */
public class LatestStateRegistry<T> {

	private final ConcurrentMap<String, T> latest = new ConcurrentHashMap<>();
	private final Function<T, String> keyOf;

	public LatestStateRegistry(Function<T, String> keyOf) {
		this.keyOf = keyOf;
	}

	public void upsert(T value) {
		latest.put(keyOf.apply(value), value);
	}

	public Optional<T> find(String key) {
		return Optional.ofNullable(latest.get(key));
	}

	public void remove(String key) {
		latest.remove(key);
	}

	public List<T> all() {
		return latest.values().stream()
				.sorted(Comparator.comparing(keyOf))
				.toList();
	}

	public int size() {
		return latest.size();
	}
}
