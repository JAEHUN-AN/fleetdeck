package com.fleetdeck.fdc;

/**
 * 경보 승격과 해제.
 *
 * <p>올릴 때와 내릴 때의 조건이 다르다 — 히스테리시스다.
 *
 * <ul>
 * <li>올리기: 연속 {@code raiseAfter} 회 위반. 값 하나가 튈 때마다 올리면 화면이 덮인다.
 * <li>내리기: 연속 {@code clearAfter} 회 정상. <b>한 번 정상이라고 바로 내리면 안 된다.</b>
 * </ul>
 *
 * <p>해제 조건이 없으면 산포가 커지는 고장(VARIANCE)처럼 기준선을 오르내리는 이상에서
 * 경보가 올랐다 내렸다를 반복한다. 실제로 그렇게 됐다 — 이상 1건에 EWMA 경보가 8번
 * 올라왔다(2026-09-22, compose 전체 기동). 막으려던 chattering 이 해제 쪽에서 그대로 났다.
 *
 * <p>다만 해제를 늦추면 그동안 같은 채널의 다음 이상을 못 올린다. 단발 이상을 세어야 하는
 * 탐지기는 {@code clearAfter = 1} 로 두고 쓴다.
 */
public class AlarmSuppressor {

	private final int raiseAfter;
	private final int clearAfter;

	private int violationStreak;
	private int normalStreak;
	private boolean raised;

	public AlarmSuppressor(int raiseAfter) {
		this(raiseAfter, 1);
	}

	/**
	 * @param raiseAfter 경보를 올리는 데 필요한 연속 위반 횟수
	 * @param clearAfter 경보를 내리는 데 필요한 연속 정상 횟수
	 */
	public AlarmSuppressor(int raiseAfter, int clearAfter) {
		this.raiseAfter = Math.max(1, raiseAfter);
		this.clearAfter = Math.max(1, clearAfter);
	}

	/** @return 이번 표본에서 경보를 새로 올려야 하면 true */
	public boolean escalate(boolean violated) {
		if (violated) {
			normalStreak = 0;
			violationStreak++;
			if (raised || violationStreak < raiseAfter) {
				return false;
			}
			raised = true;
			return true;
		}

		violationStreak = 0;
		normalStreak++;
		if (normalStreak >= clearAfter) {
			raised = false;
		}
		return false;
	}

	public boolean isRaised() {
		return raised;
	}
}
