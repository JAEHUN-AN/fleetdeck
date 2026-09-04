package com.fleetdeck.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LatestStateRegistryTest {

	record Sample(String id, int value) {
	}

	@Test
	void upsertKeepsOnlyLatestPerKey() {
		LatestStateRegistry<Sample> registry = new LatestStateRegistry<>(Sample::id);

		registry.upsert(new Sample("b", 1));
		registry.upsert(new Sample("a", 1));
		registry.upsert(new Sample("b", 2));

		assertThat(registry.size()).isEqualTo(2);
		assertThat(registry.find("b")).map(Sample::value).hasValue(2);
		assertThat(registry.all()).extracting(Sample::id).containsExactly("a", "b");
		assertThat(registry.find("zzz")).isEmpty();
	}
}
