package com.fleetdeck.fdc;

/**
 * 센서 값 하나를 받아 이상 여부를 판정한다.
 *
 * <p>상태를 들고 있으므로 채널마다 인스턴스가 하나다. 스레드 안전하지 않다 —
 * 수집 경로가 채널별로 직렬이라는 전제다.
 */
public interface Detector {

	Verdict accept(double value);

	/** 이름은 비교표의 행 머리로 쓴다. */
	String name();

	/**
	 * @param violated 이번 값이 판정 기준을 벗어났는가
	 * @param score    기준 대비 얼마나 벗어났는가(시그마 배수). 정렬과 원인 추적에 쓴다.
	 */
	record Verdict(boolean violated, double score) {

		static final Verdict OK = new Verdict(false, 0.0);

		static Verdict of(double score, double limit) {
			return new Verdict(Math.abs(score) > limit, score);
		}
	}
}
