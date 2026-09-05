package com.fleetdeck.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fleetdeck")
public record FleetdeckProperties(Mqtt mqtt, Dispatch dispatch, Wms wms) {

	public record Mqtt(String url, String clientId, String manufacturer) {
	}

	/**
	 * 배정 관련 설정.
	 *
	 * @param batchSize  한 주기에 처리할 PENDING 건수
	 * @param staleAfter 이 시간 동안 진전이 없는 ASSIGNED/RUNNING 미션을 PENDING 으로 회수한다.
	 *                   로봇 예약 TTL 로도 같은 값을 쓴다.
	 */
	public record Dispatch(int batchSize, Duration staleAfter) {
	}

	/**
	 * 모의 WMS 주문 생성기. 상위 시스템이 없는 학습 환경에서
	 * 출고 주문이 계속 들어오는 상황을 흉내낸다.
	 */
	public record Wms(boolean enabled, int maxOpenMissions) {
	}
}
