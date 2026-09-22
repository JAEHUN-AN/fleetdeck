package com.fleetdeck.fdc;

/**
 * 지수가중이동평균(EWMA) 관리도.
 *
 * <p>과거를 지수적으로 잊으면서 평균을 따라간다. 관리한계는 고정된 공칭값·시그마에서
 * 계산하므로 <b>창이 이상을 흡수하지 않는다</b> — 그래서 느린 드리프트를 정적 임계보다
 * 훨씬 일찍 잡는다. 반대로 단발 스파이크는 가중 {@code lambda} 만큼만 반영되어 눌린다.
 *
 * <p>관리한계는 EWMA 통계량의 점근 분산을 쓴다: sigma × sqrt(lambda / (2 - lambda)).
 */
public class EwmaDetector implements Detector {

	private final double lambda;
	private final double limit;
	private final double center;
	private final double controlLimit;

	private double ewma;

	/**
	 * @param lambda 가중치. 작을수록 과거를 오래 본다(드리프트에 민감, 스파이크에 둔감).
	 * @param limit  관리한계 배수. 통상 3.
	 * @param center 공칭값
	 * @param sigma  정상 상태의 표준편차
	 */
	public EwmaDetector(double lambda, double limit, double center, double sigma) {
		this.lambda = lambda;
		this.limit = limit;
		this.center = center;
		this.ewma = center;
		this.controlLimit = sigma * Math.sqrt(lambda / (2.0 - lambda));
	}

	@Override
	public Verdict accept(double value) {
		ewma = lambda * value + (1 - lambda) * ewma;
		if (controlLimit <= 0) {
			return Verdict.OK;
		}
		return Verdict.of((ewma - center) / controlLimit, limit);
	}

	@Override
	public String name() {
		return "EWMA";
	}
}
