package com.fleetdeck.fdc;

/**
 * 정적 임계. 현장에서 가장 흔하고, 대개 유일하게 걸려 있는 방식이다.
 *
 * <p>큰 값에는 즉시 반응하지만 <b>서서히 오르는 고장은 한계선을 넘기 전까지 보지 못한다.</b>
 * 비교의 기준선으로 둔다 — 나머지 두 방식은 이보다 나아야 존재 이유가 있다.
 */
public class ThresholdDetector implements Detector {

	private final double low;
	private final double high;

	public ThresholdDetector(double low, double high) {
		this.low = low;
		this.high = high;
	}

	@Override
	public Verdict accept(double value) {
		if (value > high) {
			return new Verdict(true, value - high);
		}
		if (value < low) {
			return new Verdict(true, value - low);
		}
		return Verdict.OK;
	}

	@Override
	public String name() {
		return "THRESHOLD";
	}
}
