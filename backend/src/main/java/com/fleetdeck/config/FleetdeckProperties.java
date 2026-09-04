package com.fleetdeck.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fleetdeck")
public record FleetdeckProperties(Mqtt mqtt, Dispatch dispatch, Wms wms) {

	public record Mqtt(String url, String clientId, String manufacturer) {
	}

	/** PENDING 미션 재시도 설정. 유휴 로봇이 없어 배정에 실패한 미션을 주기적으로 다시 시도한다. */
	public record Dispatch(int batchSize) {
	}

	/**
	 * 모의 WMS 주문 생성기. 상위 시스템이 없는 학습 환경에서
	 * 출고 주문이 계속 들어오는 상황을 흉내낸다.
	 */
	public record Wms(boolean enabled, int maxOpenMissions) {
	}
}
