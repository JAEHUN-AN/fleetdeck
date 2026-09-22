package com.fleetdeck.fdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 탐지기 세 가지의 성질.
 *
 * <p>비교 측정은 따로 하고(docs/022), 여기서는 각 탐지기가 "무엇에 강하고 무엇에 약한가"를
 * 고정한다. 그 성질이 깨지면 비교표의 해석이 통째로 무너지기 때문이다.
 *
 * <ul>
 * <li>정적 임계 — 큰 값에 즉시 반응. 서서히 오르는 것은 한계를 넘기 전까지 못 본다.
 * <li>이동 3σ — 스파이크에 강하다. 느린 드리프트도 처음에는 잡지만, 그 값이 창을 채우면
 * 통계를 다시 잡아 "정상"으로 재학습하고 경보가 스스로 꺼진다.
 * <li>EWMA — 드리프트에 강하다. 관리한계가 고정 공칭값에서 나오므로 재학습하지 않는다.
 * 단발 스파이크는 가중이 낮아 눌린다.
 * </ul>
 */
class DetectorTest {

	private static final double NOMINAL = 12.0;
	private static final double SIGMA = 0.25;

	static List<Supplier<Detector>> detectors() {
		return List.of(
				() -> new ThresholdDetector(NOMINAL - 8 * SIGMA, NOMINAL + 8 * SIGMA),
				() -> new MovingSigmaDetector(50, 3.5),
				() -> new EwmaDetector(0.2, 3.0, NOMINAL, SIGMA));
	}

	@ParameterizedTest
	@MethodSource("detectors")
	void staysQuietOnHealthySignal(Supplier<Detector> factory) {
		Detector detector = factory.get();
		Random rng = new Random(17);

		long alarms = IntStream.range(0, 600)
				.filter(i -> detector.accept(rng.nextGaussian() * SIGMA + NOMINAL).violated())
				.count();

		// 정상 신호에서 오경보가 1% 를 넘으면 현장에서 아무도 안 본다.
		assertThat(alarms).as("오경보").isLessThan(6);
	}

	@ParameterizedTest
	@MethodSource("detectors")
	void everyDetectorCatchesALargeStep(Supplier<Detector> factory) {
		Detector detector = factory.get();
		Random rng = new Random(23);
		warmUp(detector, rng, 120);

		boolean caught = false;
		for (int i = 0; i < 40 && !caught; i++) {
			caught = detector.accept(NOMINAL + 8 * SIGMA + rng.nextGaussian() * SIGMA).violated();
		}
		assertThat(caught).isTrue();
	}

	@Test
	void movingSigmaCatchesASingleSpike() {
		Detector detector = new MovingSigmaDetector(50, 3.5);
		Random rng = new Random(29);
		warmUp(detector, rng, 60);

		assertThat(detector.accept(NOMINAL + 10 * SIGMA).violated()).isTrue();
	}

	@Test
	void thresholdIsLateOnSlowDrift() {
		// 정적 임계는 한계선을 넘을 때까지 아무 말도 하지 않는다. 그게 이 방식의 한계다.
		Detector detector = new ThresholdDetector(NOMINAL - 8 * SIGMA, NOMINAL + 8 * SIGMA);
		int firstAlarm = driftUntilAlarm(detector, 0.2);

		assertThat(firstAlarm).isGreaterThan(30);
	}

	@Test
	void ewmaCatchesSlowDriftEarlierThanThreshold() {
		int ewma = driftUntilAlarm(new EwmaDetector(0.2, 3.0, NOMINAL, SIGMA), 0.2);
		int threshold = driftUntilAlarm(
				new ThresholdDetector(NOMINAL - 8 * SIGMA, NOMINAL + 8 * SIGMA), 0.2);

		assertThat(ewma).isLessThan(threshold);
	}

	@Test
	void movingSigmaGoesQuietOnceItHasAbsorbedTheDrift() {
		// 이동 통계는 드리프트를 처음에는 잡는다. 그러나 그 값이 창을 채우고 나면
		// 중앙값과 MAD 가 따라 올라가 같은 기울기가 "정상"이 된다.
		// 설비는 계속 나빠지는데 경보가 스스로 꺼진다 - 이동 3σ 하나만 걸면 안 되는 이유다.
		long movingLate = lateAlarms(new MovingSigmaDetector(50, 3.5), 0.08);
		long ewmaLate = lateAlarms(new EwmaDetector(0.2, 3.0, NOMINAL, SIGMA), 0.08);

		assertThat(movingLate).as("이동 3σ 후반 경보").isLessThan(10);
		assertThat(ewmaLate).as("EWMA 후반 경보").isGreaterThan(90);
	}

	/** 드리프트를 400틱 이어간 뒤 마지막 100틱에서 경보가 몇 번 나는지. */
	private static long lateAlarms(Detector detector, double slope) {
		Random rng = new Random(37);
		warmUp(detector, rng, 120);

		long alarms = 0;
		for (int tick = 1; tick <= 400; tick++) {
			double value = NOMINAL + SIGMA * slope * tick + rng.nextGaussian() * SIGMA;
			boolean violated = detector.accept(value).violated();
			if (tick > 300 && violated) {
				alarms++;
			}
		}
		return alarms;
	}

	@Test
	void suppressorHoldsUntilConsecutiveViolations() {
		// 한 번 튄 값마다 경보를 올리면 현장 화면이 경보로 덮인다.
		AlarmSuppressor suppressor = new AlarmSuppressor(3);

		assertThat(suppressor.escalate(true)).isFalse();
		assertThat(suppressor.escalate(true)).isFalse();
		assertThat(suppressor.escalate(true)).isTrue();
	}

	@Test
	void suppressorResetsOnANormalSample() {
		AlarmSuppressor suppressor = new AlarmSuppressor(3);
		suppressor.escalate(true);
		suppressor.escalate(true);

		assertThat(suppressor.escalate(false)).isFalse();
		assertThat(suppressor.escalate(true)).isFalse();
	}

	@Test
	void suppressorDoesNotRepeatWhileTheAlarmStaysUp() {
		// 경보는 상태이지 이벤트가 아니다. 올라간 뒤에는 다시 올리지 않는다.
		AlarmSuppressor suppressor = new AlarmSuppressor(2);
		suppressor.escalate(true);

		assertThat(suppressor.escalate(true)).isTrue();
		assertThat(suppressor.escalate(true)).isFalse();
	}

	@Test
	void suppressorNeedsConsecutiveNormalSamplesToClear() {
		// 정상값 하나에 바로 내리면 기준선을 오르내리는 이상에서 경보가 깜빡인다.
		// 실제로 VARIANCE 이상 1건에 EWMA 경보가 8번 올라왔다 (docs/023).
		AlarmSuppressor suppressor = new AlarmSuppressor(2, 3);
		suppressor.escalate(true);
		assertThat(suppressor.escalate(true)).isTrue();

		suppressor.escalate(false);
		suppressor.escalate(false);
		assertThat(suppressor.isRaised()).as("정상 2회로는 안 내린다").isTrue();

		suppressor.escalate(false);
		assertThat(suppressor.isRaised()).as("정상 3회에 내린다").isFalse();
	}

	@Test
	void oscillatingSignalDoesNotFlapTheAlarm() {
		// 위반-정상-위반-정상... 을 반복해도 경보는 한 번만 올라야 한다.
		AlarmSuppressor suppressor = new AlarmSuppressor(2, 5);
		suppressor.escalate(true);
		suppressor.escalate(true);

		long raises = 0;
		for (int i = 0; i < 40; i++) {
			if (suppressor.escalate(i % 2 == 0)) {
				raises++;
			}
		}
		assertThat(raises).isZero();
	}

	@Test
	void immediateClearLetsEverySpikeThrough() {
		// 단발 이상을 세는 탐지기는 해제도 즉시여야 한다. 늦추면 다음 스파이크를 삼킨다.
		AlarmSuppressor suppressor = new AlarmSuppressor(1, 1);

		assertThat(suppressor.escalate(true)).isTrue();
		suppressor.escalate(false);
		assertThat(suppressor.escalate(true)).isTrue();
	}

	/** 공칭값에서 시작해 틱당 {@code slope}×sigma 씩 올린다. 처음 경보가 난 틱을 돌려준다. */
	private static int driftUntilAlarm(Detector detector, double slope) {
		Random rng = new Random(31);
		warmUp(detector, rng, 120);

		for (int tick = 1; tick <= 400; tick++) {
			double value = NOMINAL + SIGMA * slope * tick + rng.nextGaussian() * SIGMA;
			if (detector.accept(value).violated()) {
				return tick;
			}
		}
		return Integer.MAX_VALUE;
	}

	private static void warmUp(Detector detector, Random rng, int ticks) {
		List<Double> ignored = new ArrayList<>();
		for (int i = 0; i < ticks; i++) {
			ignored.add(rng.nextGaussian() * SIGMA + NOMINAL);
			detector.accept(ignored.get(i));
		}
	}
}
