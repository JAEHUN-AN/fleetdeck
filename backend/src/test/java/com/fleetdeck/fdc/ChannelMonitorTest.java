package com.fleetdeck.fdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * 채널 하나를 감시하는 조합.
 *
 * <p>docs/022 의 결론을 그대로 옮긴 것이다 — 한 방식으로 다 덮이지 않으므로 셋을 같이 걸고,
 * <b>억제는 방식마다 다르게</b> 준다. 이동 통계에까지 억제를 걸면 단발 스파이크가 통째로
 * 사라진다는 것이 측정으로 나왔기 때문이다.
 */
class ChannelMonitorTest {

	private static final ChannelSpec SPEC = new ChannelSpec(12.0, 0.25);

	@Test
	void runsAllThreeDetectors() {
		ChannelMonitor monitor = new ChannelMonitor(SPEC);
		assertThat(monitor.detectorNames())
				.containsExactly("THRESHOLD", "MOVING_SIGMA", "EWMA");
	}

	@Test
	void singleSpikeIsReportedByMovingSigmaAlone() {
		// 이동 통계에는 억제를 걸지 않는다. 그래서 1틱짜리 이상도 올라온다.
		ChannelMonitor monitor = warmedUp();

		List<ChannelMonitor.Alarm> alarms = monitor.accept(12.0 + 10 * 0.25);

		assertThat(alarms).extracting(ChannelMonitor.Alarm::detector).contains("MOVING_SIGMA");
	}

	@Test
	void sustainedStepIsReportedByEwmaAfterSuppression() {
		ChannelMonitor monitor = warmedUp();

		List<String> detectors = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			monitor.accept(12.0 + 6 * 0.25).forEach(a -> detectors.add(a.detector()));
		}
		assertThat(detectors).contains("EWMA");
	}

	@Test
	void healthySignalRaisesNothing() {
		ChannelMonitor monitor = new ChannelMonitor(SPEC);
		Random rng = new Random(19);

		List<ChannelMonitor.Alarm> alarms = new ArrayList<>();
		for (int i = 0; i < 400; i++) {
			alarms.addAll(monitor.accept(12.0 + rng.nextGaussian() * 0.25));
		}
		assertThat(alarms).hasSizeLessThan(8);
	}

	@Test
	void alarmIsNotRepeatedWhileItStaysUp() {
		// 경보는 상태다. 이상이 이어지는 동안 매 틱 올리면 화면이 덮인다.
		ChannelMonitor monitor = warmedUp();

		long ewmaAlarms = 0;
		for (int i = 0; i < 40; i++) {
			ewmaAlarms += monitor.accept(12.0 + 8 * 0.25).stream()
					.filter(a -> a.detector().equals("EWMA")).count();
		}
		assertThat(ewmaAlarms).isEqualTo(1);
	}

	@Test
	void alarmCarriesTheValueAndScore() {
		ChannelMonitor monitor = warmedUp();

		ChannelMonitor.Alarm alarm = monitor.accept(12.0 + 12 * 0.25).stream()
				.filter(a -> a.detector().equals("MOVING_SIGMA")).findFirst().orElseThrow();

		assertThat(alarm.value()).isEqualTo(12.0 + 12 * 0.25);
		assertThat(Math.abs(alarm.score())).isGreaterThan(3.5);
	}

	private static ChannelMonitor warmedUp() {
		ChannelMonitor monitor = new ChannelMonitor(SPEC);
		Random rng = new Random(23);
		for (int i = 0; i < 120; i++) {
			monitor.accept(12.0 + rng.nextGaussian() * 0.25);
		}
		return monitor;
	}
}
