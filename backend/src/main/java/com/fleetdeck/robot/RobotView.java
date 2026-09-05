package com.fleetdeck.robot;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;

/**
 * 로봇 상태 + 관제가 판단한 생존 여부.
 *
 * 로봇이 보낸 timestamp 대신 백엔드 수신 시각을 기준으로 삼는다.
 * 로봇 시계가 틀어져 있어도 관제 판단은 흔들리지 않아야 한다.
 *
 * state 는 @JsonUnwrapped 로 평탄화되어, 기존 응답 필드는 그대로 두고
 * online / lastSeenAt 두 개만 늘어난다.
 */
public record RobotView(
		@JsonUnwrapped RobotStateMessage state,
		boolean online,
		Instant lastSeenAt) {
}
