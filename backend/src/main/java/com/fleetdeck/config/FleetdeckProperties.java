package com.fleetdeck.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fleetdeck")
public record FleetdeckProperties(Mqtt mqtt, Dispatch dispatch, Wms wms, Robot robot) {

	public record Mqtt(String url, String clientId, String manufacturer) {
	}

	/**
	 * 로봇 생존 판정.
	 *
	 * @param offlineAfter 이 시간 동안 텔레메트리가 없으면 OFFLINE 으로 본다. 배정 후보에서 빠진다.
	 * @param evictAfter   이 시간 동안 소식이 없으면 레지스트리에서 지운다.
	 *                     규모를 줄였을 때 유령 로봇이 남지 않게 한다.
	 */
	public record Robot(Duration offlineAfter, Duration evictAfter) {
	}

	/**
	 * 배정 관련 설정.
	 *
	 * @param batchSize  한 주기에 처리할 PENDING 건수
	 * @param staleAfter 이 시간 동안 진전이 없는 ASSIGNED/RUNNING 미션을 회수한다.
	 *                   로봇 예약 TTL 로도 같은 값을 쓴다.
	 * @param maxRetries 회수 후 재배정을 허용하는 최대 횟수. 넘기면 FAILED 로 포기한다.
	 */
	public record Dispatch(int batchSize, Duration staleAfter, int maxRetries) {
	}

	/**
	 * 모의 WMS 주문 생성기. 상위 시스템이 없는 학습 환경에서
	 * 출고 주문이 계속 들어오는 상황을 흉내낸다.
	 */
	public record Wms(boolean enabled, int maxOpenMissions) {
	}
}
