package com.fleetdeck.fdc;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 탐지기 비교 채점.
 *
 * <p>라벨 데이터셋(`fdc/sensor-eval.csv`, 시뮬레이터의 `fleetsim-dataset` 이 생성) 위에서
 * 탐지율·오경보율·탐지지연을 잰다. 숫자는 docs/022-fdc-detection.md 에 옮긴다.
 *
 * <p>여기서 고정하는 것은 순위이지 소수점이 아니다. 데이터셋을 다시 뽑으면 값은 조금
 * 달라지지만, "무엇이 무엇보다 낫다"가 뒤집히면 설계 근거가 무너진 것이다.
 */
class DetectorEvaluationTest {

	private static final String DATASET = "/fdc/sensor-eval.csv";
	/** 경보 승격 기준. 연속 위반 2회 이상일 때만 경보로 본다. */
	private static final int CONSECUTIVE = 2;

	private record Sample(int episode, String kind, String channel, double value, boolean faulty) {
	}

	private record Score(String detector, String kind, double detectionRate, double falseAlarmRate,
			double meanLatency) {
	}

	@Test
	@DisplayName("탐지기 3종 × 이상 4종 교차 채점")
	void scoreEveryDetectorAgainstEveryFaultKind() throws IOException {
		List<Sample> samples = load();

		List<Score> immediate = scoreAll(samples, 1);
		List<Score> suppressed = scoreAll(samples, CONSECUTIVE);
		print("연속 1회에 경보 (억제 없음)", immediate);
		print("연속 " + CONSECUTIVE + "회에 경보 (chattering 억제)", suppressed);

		// 1. 계단은 통계 기반 두 탐지기가 거의 전부 잡아야 한다. 못 잡으면 설정이 틀린 것이다.
		assertThat(rate(suppressed, "EWMA", "STEP")).isGreaterThan(0.9);
		assertThat(rate(suppressed, "MOVING_SIGMA", "STEP")).isGreaterThan(0.9);

		// 2. 느린 드리프트는 정적 임계도 결국 잡는다 - 갈리는 것은 탐지율이 아니라 지연이다.
		//    EWMA 가 훨씬 먼저 울지 못하면 EWMA 를 둘 이유가 없다.
		assertThat(latency(suppressed, "EWMA", "DRIFT"))
				.isLessThan(latency(suppressed, "THRESHOLD", "DRIFT") / 2);

		// 3. 이동 통계는 드리프트를 흡수해 재학습한다. 탐지율이 EWMA 보다 크게 낮아야 정상이다.
		assertThat(rate(suppressed, "MOVING_SIGMA", "DRIFT"))
				.isLessThan(rate(suppressed, "EWMA", "DRIFT") / 2);

		// 4. 단발 스파이크는 이동 통계만 잡는다. 억제를 걸지 않았을 때의 이야기다.
		assertThat(rate(immediate, "MOVING_SIGMA", "SPIKE")).isGreaterThan(0.9);
		assertThat(rate(immediate, "MOVING_SIGMA", "SPIKE"))
				.isGreaterThan(rate(immediate, "EWMA", "SPIKE"));

		// 5. 그리고 연속 2회 승격을 거는 순간 단발 스파이크는 원리적으로 사라진다.
		//    chattering 억제와 단발 이상 탐지는 맞바꾸는 관계다. 공짜가 아니다.
		assertThat(rate(suppressed, "MOVING_SIGMA", "SPIKE")).isZero();

		// 6. 어느 설정에서도 정상 구간 오경보가 5% 를 넘으면 현장에서 아무도 안 본다.
		assertThat(suppressed).allSatisfy(s -> assertThat(s.falseAlarmRate()).isLessThan(0.05));
	}

	private static List<Score> scoreAll(List<Sample> samples, int consecutive) {
		List<Score> scores = new ArrayList<>();
		for (Map.Entry<String, Function<String, Detector>> factory : factories().entrySet()) {
			for (String kind : List.of("SPIKE", "STEP", "DRIFT", "VARIANCE")) {
				scores.add(score(factory.getKey(), factory.getValue(), samples, kind, consecutive));
			}
		}
		return scores;
	}

	private static Map<String, Function<String, Detector>> factories() {
		Map<String, Function<String, Detector>> factories = new LinkedHashMap<>();
		// 정적 임계는 공칭값 ± 8σ. 현장에서 흔히 잡는 "확실히 이상한" 수준이다.
		factories.put("THRESHOLD", channel -> new ThresholdDetector(
				nominal(channel) - 8 * sigma(channel), nominal(channel) + 8 * sigma(channel)));
		factories.put("MOVING_SIGMA", channel -> new MovingSigmaDetector(50, 3.5));
		factories.put("EWMA",
				channel -> new EwmaDetector(0.2, 3.0, nominal(channel), sigma(channel)));
		return factories;
	}

	private static Score score(String name, Function<String, Detector> factory,
			List<Sample> samples, String kind, int consecutive) {
		int episodes = 0;
		int detected = 0;
		long healthyTicks = 0;
		long falseAlarms = 0;
		List<Integer> latencies = new ArrayList<>();

		for (List<Sample> episode : episodesOf(samples, kind)) {
			episodes++;
			Detector detector = factory.apply(episode.get(0).channel());
			AlarmSuppressor suppressor = new AlarmSuppressor(consecutive);

			int faultTick = -1;
			int alarmTick = -1;
			for (int i = 0; i < episode.size(); i++) {
				Sample sample = episode.get(i);
				boolean raised = suppressor.escalate(detector.accept(sample.value()).violated());

				if (sample.faulty() && faultTick < 0) {
					faultTick = i;
				}
				if (!sample.faulty() && faultTick < 0) {
					healthyTicks++;
					if (raised) {
						falseAlarms++;
					}
				}
				if (sample.faulty() && raised && alarmTick < 0) {
					alarmTick = i;
				}
			}
			if (alarmTick >= 0) {
				detected++;
				latencies.add(alarmTick - faultTick);
			}
		}

		double meanLatency = latencies.isEmpty() ? Double.NaN
				: latencies.stream().mapToInt(Integer::intValue).average().orElse(Double.NaN);
		return new Score(name, kind, episodes == 0 ? 0 : (double) detected / episodes,
				healthyTicks == 0 ? 0 : (double) falseAlarms / healthyTicks, meanLatency);
	}

	/** 같은 episode 번호의 표본을 순서대로 묶는다. */
	private static List<List<Sample>> episodesOf(List<Sample> samples, String kind) {
		Map<Integer, List<Sample>> byEpisode = new LinkedHashMap<>();
		for (Sample sample : samples) {
			if (sample.kind().equals(kind)) {
				byEpisode.computeIfAbsent(sample.episode(), k -> new ArrayList<>()).add(sample);
			}
		}
		return List.copyOf(byEpisode.values());
	}

	private static double rate(List<Score> scores, String detector, String kind) {
		return scores.stream()
				.filter(s -> s.detector().equals(detector) && s.kind().equals(kind))
				.findFirst().orElseThrow().detectionRate();
	}

	private static double latency(List<Score> scores, String detector, String kind) {
		return scores.stream()
				.filter(s -> s.detector().equals(detector) && s.kind().equals(kind))
				.findFirst().orElseThrow().meanLatency();
	}

	private static void print(String title, List<Score> scores) {
		System.out.println();
		System.out.println(title);
		System.out.printf("%-14s %-9s %8s %8s %8s%n", "detector", "kind", "detect", "false", "delay");
		for (Score s : scores) {
			System.out.printf("%-14s %-9s %7.0f%% %7.2f%% %8s%n", s.detector(), s.kind(),
					s.detectionRate() * 100, s.falseAlarmRate() * 100,
					Double.isNaN(s.meanLatency()) ? "-" : String.format("%.1f", s.meanLatency()));
		}
	}

	private static double nominal(String channel) {
		return switch (channel) {
			case "MotorCurrent" -> 12.0;
			case "VibrationRms" -> 2.5;
			default -> 45.0;
		};
	}

	private static double sigma(String channel) {
		return switch (channel) {
			case "MotorCurrent" -> 0.25;
			case "VibrationRms" -> 0.15;
			default -> 0.5;
		};
	}

	private static List<Sample> load() throws IOException {
		List<Sample> samples = new ArrayList<>();
		try (InputStream in = DetectorEvaluationTest.class.getResourceAsStream(DATASET)) {
			if (in == null) {
				throw new IOException("데이터셋이 없다: " + DATASET
						+ " (simulator 에서 `uv run fleetsim-dataset` 으로 생성)");
			}
			BufferedReader reader = new BufferedReader(
					new InputStreamReader(in, StandardCharsets.UTF_8));
			reader.readLine();
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split(",");
				samples.add(new Sample(Integer.parseInt(parts[0]), parts[1], parts[2],
						Double.parseDouble(parts[4]), "1".equals(parts[5])));
			}
		}
		return samples;
	}
}
