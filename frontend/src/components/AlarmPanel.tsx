import { DETECTOR_LABEL, type SensorAlarm } from '../types/telemetry';

interface AlarmPanelProps {
  alarms: SensorAlarm[];
}

/** 경보가 얼마나 오래된 것인지. 초 단위는 과하고, 분/시간이면 충분하다. */
function ago(iso: string): string {
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return '방금';
  if (seconds < 3600) return `${Math.floor(seconds / 60)}분 전`;
  return `${Math.floor(seconds / 3600)}시간 전`;
}

/**
 * 벗어난 정도로 심각도를 나눈다. 탐지기마다 한계 배수가 달라(3.0 / 3.5) 절대값을
 * 그대로 비교할 수는 없지만, 크게 벗어날수록 급한 것은 공통이다.
 */
function severity(score: number): 'high' | 'mid' {
  return Math.abs(score) >= 6 ? 'high' : 'mid';
}

/**
 * FDC 경보 목록.
 *
 * 탐지기 이름을 같이 보여주는 것이 요점이다. 이동 통계가 올린 단발 경보와 EWMA 가 올린
 * 추세 경보는 현장 대응이 다르다 — 전자는 확인, 후자는 정비 계획이다.
 * 어느 방식이 울렸는지 감추면 "경보가 떴다" 이상을 말해주지 못한다.
 */
export function AlarmPanel({ alarms }: AlarmPanelProps) {
  return (
    <section className="panel" aria-labelledby="alarm-heading">
      <div className="panel__head">
        <h2 id="alarm-heading">이상 감지</h2>
        <span className="mono panel__count">{alarms.length}</span>
      </div>

      {alarms.length === 0 ? (
        <p className="empty">경보 없음</p>
      ) : (
        <ul className="alarms">
          {alarms.map((alarm, i) => (
            <li
              key={`${alarm.at}-${alarm.equipmentId}-${alarm.channel}-${alarm.detector}-${i}`}
              className={`alarm alarm--${severity(alarm.score)}`}
            >
              <div className="alarm__top">
                <span className="mono alarm__equipment">{alarm.equipmentId}</span>
                <span className="alarm__detector">{DETECTOR_LABEL[alarm.detector]}</span>
                <span className="alarm__ago">{ago(alarm.at)}</span>
              </div>
              <div className="alarm__bottom mono">
                <span>{alarm.channel}</span>
                <span className="alarm__value">{alarm.value.toFixed(2)}</span>
                <span className="alarm__score">{alarm.score >= 0 ? '+' : ''}
                  {alarm.score.toFixed(1)}σ</span>
              </div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
