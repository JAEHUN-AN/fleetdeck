package com.fleetdeck.fdc;

/**
 * 채널 하나의 정상 동작 사양. 관리한계 계산의 기준이다.
 *
 * <p>규격한계(제품이 만족해야 하는 값)가 아니라 <b>이 설비가 정상일 때 실제로 내는 값</b>이다.
 * 둘을 섞으면 SPC 도 FDC 도 전부 틀어진다.
 *
 * @param nominal 정상 상태의 평균
 * @param sigma   정상 상태의 표준편차
 */
public record ChannelSpec(double nominal, double sigma) {
}
