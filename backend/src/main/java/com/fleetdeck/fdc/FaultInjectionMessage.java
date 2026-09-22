package com.fleetdeck.fdc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 시뮬레이터가 넣은 이상의 정답 라벨.
 *
 * <p>실제 설비에는 없는 것이다 — 학습·평가 환경에서만 들어온다. 이게 있어야 탐지율과
 * 오경보율을 운영 데이터 위에서 채점할 수 있다. QoS 1 로 받는다(유실되면 그 구간을 못 쓴다).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FaultInjectionMessage(
		String equipmentId,
		String channel,
		String kind,
		Double magnitude,
		String phase,
		String timestamp) {

	public boolean isUsable() {
		return equipmentId != null && !equipmentId.isBlank()
				&& channel != null && !channel.isBlank()
				&& kind != null && phase != null;
	}
}
