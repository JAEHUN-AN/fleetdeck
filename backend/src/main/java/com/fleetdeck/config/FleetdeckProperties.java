package com.fleetdeck.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fleetdeck")
public record FleetdeckProperties(Mqtt mqtt, OpcUa opcua, Fdc fdc, Dispatch dispatch, Wms wms,
		Robot robot) {

	public record Mqtt(String url, String clientId, String manufacturer) {
	}

	/**
	 * OPC UA 설비 연동.
	 *
	 *  endpointUrl       설비(시뮬레이터) 서버 엔드포인트
	 *  namespaceUri      설비 주소공간의 네임스페이스. 인덱스는 서버가 정하므로 URI 로 찾는다.
	 *  folderName        설비 객체들이 매달린 폴더의 BrowseName
	 *  mode              SUBSCRIBE(서버가 변화를 통보) 또는 POLL(주기 읽기)
	 *  publishingInterval 구독 모드에서 서버가 알림을 모아 보내는 주기
	 *  pollInterval      폴링 모드에서 값을 읽는 주기
	 *  reconnectDelay    연결 실패 시 재시도 간격
	 */
	public record OpcUa(boolean enabled, String endpointUrl, String namespaceUri, String folderName,
			OpcUaMode mode, Duration publishingInterval, Duration pollInterval,
			Duration reconnectDelay) {
	}

	/**
	 * FDC(이상 감지).
	 *
	 * @param channels 채널별 정상 동작 사양. 규격한계가 아니라 이 설비가 정상일 때 내는 값이다.
	 *                 여기 없는 채널은 적재만 하고 탐지는 건너뛴다.
	 */
	public record Fdc(boolean enabled, java.util.Map<String, com.fleetdeck.fdc.ChannelSpec> channels) {
	}

	/** 값을 가져오는 방식. 지연과 부하를 비교하려고 둘 다 남겨 둔다. */
	public enum OpcUaMode {

		SUBSCRIBE,
		POLL
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
