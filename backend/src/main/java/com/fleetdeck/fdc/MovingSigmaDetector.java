package com.fleetdeck.fdc;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 이동 창 안에서 중앙값과 MAD 로 표준편차를 추정해 k 시그마를 벗어나는지 본다.
 *
 * <p>평균·표준편차 대신 <b>중앙값·MAD</b> 를 쓰는 이유는, 창 안에 이상값이 하나만 들어와도
 * 평균과 표준편차가 그 값에 끌려가 정작 그 값이 정상으로 판정되기 때문이다(가림 효과).
 *
 * <p>스파이크에 강하다. 대신 창 안에서 통계를 다시 잡으므로 <b>지속되는 드리프트는 창이
 * 따라 올라가며 흡수해 버린다</b> - 설비는 계속 나빠지는데 경보가 스스로 꺼진다.
 * 이 성질은 {@code DetectorTest} 가 고정한다.
 *
 * <p>MAD 는 표본이 적으면 시그마를 낮게 잡는 편이라 한계 배수를 3.0 이 아니라 3.5 쯤
 * 둬야 오경보가 1%% 아래로 내려온다 (창 30·한계 3.0 에서 실측 1.17%%).
 */
public class MovingSigmaDetector implements Detector {

	/** 정규분포에서 MAD 를 표준편차로 환산하는 상수. */
	private static final double MAD_TO_SIGMA = 1.4826;

	private final int window;
	private final double limit;
	private final Deque<Double> samples = new ArrayDeque<>();

	public MovingSigmaDetector(int window, double limit) {
		this.window = Math.max(3, window);
		this.limit = limit;
	}

	@Override
	public Verdict accept(double value) {
		if (samples.size() < window) {
			samples.addLast(value);
			// 창이 차기 전에는 판정하지 않는다. 표본이 적으면 무엇이든 이상으로 보인다.
			return Verdict.OK;
		}

		double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
		double median = median(sorted);
		double sigma = MAD_TO_SIGMA * medianAbsoluteDeviation(sorted, median);

		samples.addLast(value);
		samples.removeFirst();

		if (sigma <= 0) {
			// 창이 완전히 평평하다. 값이 다르면 이상, 같으면 정상.
			return new Verdict(value != median, value - median);
		}
		return Verdict.of((value - median) / sigma, limit);
	}

	private static double median(double[] sorted) {
		int mid = sorted.length / 2;
		return sorted.length % 2 == 1 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2.0;
	}

	private static double medianAbsoluteDeviation(double[] sorted, double median) {
		double[] deviations = new double[sorted.length];
		for (int i = 0; i < sorted.length; i++) {
			deviations[i] = Math.abs(sorted[i] - median);
		}
		java.util.Arrays.sort(deviations);
		return median(deviations);
	}

	@Override
	public String name() {
		return "MOVING_SIGMA";
	}
}
