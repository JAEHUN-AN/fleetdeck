package com.fleetdeck.mission;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 상위 시스템(WMS 모의)이 보내는 미션 생성 요청.
 */
public record MissionRequest(
		@NotNull Mission.MissionType type,
		@NotBlank String fromNode,
		@NotBlank String toNode,
		String sourceRef) {
}
