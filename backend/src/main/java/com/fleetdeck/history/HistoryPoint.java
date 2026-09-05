package com.fleetdeck.history;

import java.time.OffsetDateTime;

/**
 * 시계열 한 점. TimescaleDB 의 time_bucket 으로 묶은 결과다.
 *
 * @param bucket 구간 시작 시각
 * @param series 계열 이름 (로봇 시리얼 또는 설비 ID)
 * @param value  구간 평균값
 */
public record HistoryPoint(OffsetDateTime bucket, String series, double value) {
}
