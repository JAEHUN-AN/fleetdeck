package com.fleetdeck.fdc;

import java.util.ArrayList;
import java.util.List;

/**
 * 설비 한 대의 채널 하나를 감시한다.
 *
 * <p>탐지기 셋을 같이 돌리고 <b>억제는 방식마다 다르게</b> 건다. docs/022 의 측정 결과를
 * 그대로 옮긴 설정이다.
 *
 * <table>
 * <tr><th>방식<th>잡는 것<th>억제</tr>
 * <tr><td>THRESHOLD<td>절대 넘으면 안 되는 선<td>연속 2회</tr>
 * <tr><td>MOVING_SIGMA<td>단발 스파이크<td><b>없음</b></tr>
 * <tr><td>EWMA<td>느린 드리프트·계단<td>연속 2회</tr>
 * </table>
 *
 * <p>이동 통계에 억제를 걸면 1틱짜리 이상이 원리적으로 사라진다(측정: 탐지율 100% → 0%).
 * 그래서 여기만 억제를 빼고, 대신 오경보는 창·한계 설정으로 눌렀다.
 *
 * <p>정적 임계를 통계 탐지기로 대체하지 않고 <b>남겨 둔다.</b> 이동 통계는 느린 변화를
 * 재학습해 놓치므로, 무슨 일이 있어도 넘으면 안 되는 선은 따로 있어야 한다.
 *
 * <p>스레드 안전하지 않다. 채널당 인스턴스 하나, 수집 경로가 직렬이라는 전제다.
 */
public class ChannelMonitor {

	/** 정적 임계를 공칭값에서 몇 시그마에 둘지. "확실히 이상한" 수준. */
	private static final double HARD_LIMIT_SIGMA = 8.0;
	private static final int SUPPRESS_CONSECUTIVE = 2;
	/** 경보를 내리는 데 필요한 연속 정상 횟수. 오르내리는 이상에서 경보가 깜빡이는 것을 막는다. */
	private static final int CLEAR_AFTER = 5;
	private static final int MOVING_WINDOW = 50;
	private static final double MOVING_LIMIT = 3.5;
	private static final double EWMA_LAMBDA = 0.2;
	private static final double EWMA_LIMIT = 3.0;

	/**
	 * @param detector 올린 탐지기 이름
	 * @param value    그때의 측정값
	 * @param score    기준 대비 벗어난 정도
	 */
	public record Alarm(String detector, double value, double score) {
	}

	private record Stage(Detector detector, AlarmSuppressor suppressor) {
	}

	private final List<Stage> stages;

	public ChannelMonitor(ChannelSpec spec) {
		double hard = HARD_LIMIT_SIGMA * spec.sigma();
		this.stages = List.of(
				new Stage(new ThresholdDetector(spec.nominal() - hard, spec.nominal() + hard),
						new AlarmSuppressor(SUPPRESS_CONSECUTIVE, CLEAR_AFTER)),
				// 올릴 때도 내릴 때도 즉시 - 단발 이상은 하나하나가 별개 사건이라
				// 해제를 늦추면 다음 스파이크를 삼킨다.
				new Stage(new MovingSigmaDetector(MOVING_WINDOW, MOVING_LIMIT),
						new AlarmSuppressor(1, 1)),
				new Stage(new EwmaDetector(EWMA_LAMBDA, EWMA_LIMIT, spec.nominal(), spec.sigma()),
						new AlarmSuppressor(SUPPRESS_CONSECUTIVE, CLEAR_AFTER)));
	}

	/** @return 이번 표본에서 <b>새로 올라간</b> 경보들. 이미 올라가 있는 경보는 담기지 않는다. */
	public List<Alarm> accept(double value) {
		List<Alarm> raised = new ArrayList<>(stages.size());
		for (Stage stage : stages) {
			Detector.Verdict verdict = stage.detector().accept(value);
			if (stage.suppressor().escalate(verdict.violated())) {
				raised.add(new Alarm(stage.detector().name(), value, verdict.score()));
			}
		}
		return raised;
	}

	public List<String> detectorNames() {
		return stages.stream().map(s -> s.detector().name()).toList();
	}
}
