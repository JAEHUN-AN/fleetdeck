import { useEffect, useMemo, useState } from 'react';
import { fetchSensorChannels, fetchSensorHistory, groupBySeries } from '../api/history';
import type { HistoryPoint } from '../api/history';

const VIEW_W = 320;
const VIEW_H = 90;
const PAD_LEFT = 4;
const PAD_BOTTOM = 4;
const REFRESH_MS = 10_000;
/** 채널 목록은 설비가 바뀔 때나 달라진다. 값보다 훨씬 드물게 다시 묻는다. */
const CHANNEL_REFRESH_MS = 60_000;

const SERIES_COLORS = [
  'oklch(78% 0.15 200)',
  'oklch(76% 0.17 150)',
  'oklch(80% 0.16 80)',
  'oklch(72% 0.18 320)',
  'oklch(70% 0.16 30)',
  'oklch(74% 0.13 250)',
];

/**
 * 센서는 0 에서 시작하지 않는다. 베어링 온도 45℃ 를 0 기준으로 그리면 변동이
 * 선 두께에 묻힌다. 데이터 범위에 여유를 붙여 y축을 잡는다.
 */
function scaleOf(points: HistoryPoint[]): { min: number; max: number } {
  const values = points.map((p) => p.value);
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const margin = Math.max((hi - lo) * 0.2, Math.abs(hi) * 0.01, 0.001);
  return { min: lo - margin, max: hi + margin };
}

function toPath(points: HistoryPoint[], minX: number, spanX: number, min: number, max: number) {
  const span = Math.max(1e-9, max - min);
  return points
    .map((p, i) => {
      const t = new Date(p.bucket).getTime();
      const x = PAD_LEFT + ((t - minX) / spanX) * (VIEW_W - PAD_LEFT);
      const y = (VIEW_H - PAD_BOTTOM) * (1 - (p.value - min) / span);
      return `${i === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(' ');
}

/**
 * 설비 센서 채널 추이.
 *
 * <p>채널 목록을 코드에 박지 않고 서버에 묻는다. OPC UA 쪽은 브라우징으로 채널을 찾아오므로
 * 설비가 채널을 늘리면 백엔드도 화면도 손대지 않고 탭이 하나 늘어난다.
 */
export function SensorChart() {
  const [channels, setChannels] = useState<string[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [points, setPoints] = useState<HistoryPoint[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    const loadChannels = () => {
      fetchSensorChannels()
        .then((list) => {
          if (cancelled) return;
          setChannels(list);
          // 선택이 없거나 그 채널이 사라졌을 때만 바꾼다. 사용자가 고른 탭을 뺏지 않는다.
          setSelected((prev) => (prev && list.includes(prev) ? prev : (list[0] ?? null)));
          setError(null);
        })
        .catch((e: unknown) => {
          if (!cancelled) setError(e instanceof Error ? e.message : String(e));
        });
    };

    loadChannels();
    const timer = setInterval(loadChannels, CHANNEL_REFRESH_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, []);

  useEffect(() => {
    if (!selected) {
      setPoints([]);
      return;
    }
    let cancelled = false;

    const load = () => {
      fetchSensorHistory(selected)
        .then((data) => {
          if (cancelled) return;
          setPoints(data);
          setError(null);
        })
        .catch((e: unknown) => {
          if (!cancelled) setError(e instanceof Error ? e.message : String(e));
        });
    };

    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [selected]);

  const chart = useMemo(() => {
    if (points.length === 0) return null;

    const times = points.map((p) => new Date(p.bucket).getTime());
    const minX = Math.min(...times);
    const spanX = Math.max(1, Math.max(...times) - minX);
    const { min, max } = scaleOf(points);

    const series = [...groupBySeries(points).entries()].map(([name, list], i) => ({
      name,
      color: SERIES_COLORS[i % SERIES_COLORS.length],
      path: toPath(list, minX, spanX, min, max),
      last: list[list.length - 1]?.value ?? 0,
    }));

    return { series, min, max };
  }, [points]);

  return (
    <div className="chart">
      <div className="chart__head">
        <span className="chart__title">설비 센서</span>
        {chart && (
          <span className="chart__max mono">
            {chart.min.toFixed(1)} – {chart.max.toFixed(1)}
          </span>
        )}
      </div>

      {channels.length > 0 && (
        <div className="chart__tabs" role="tablist" aria-label="센서 채널">
          {channels.map((channel) => (
            <button
              key={channel}
              type="button"
              role="tab"
              aria-selected={channel === selected}
              className={`chart__tab${channel === selected ? ' chart__tab--on' : ''}`}
              onClick={() => setSelected(channel)}
            >
              {channel}
            </button>
          ))}
        </div>
      )}

      {error && <p className="chart__error">{error}</p>}
      {!error && !chart && <p className="empty">센서 데이터 수집 중…</p>}

      {chart && (
        <>
          <svg
            className="chart__svg"
            viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
            preserveAspectRatio="none"
            role="img"
            aria-label={`${selected ?? ''} 추이`}
          >
            {[0.25, 0.5, 0.75].map((f) => (
              <line
                key={f}
                className="chart__grid"
                x1={0}
                y1={(VIEW_H - PAD_BOTTOM) * f}
                x2={VIEW_W}
                y2={(VIEW_H - PAD_BOTTOM) * f}
              />
            ))}
            {chart.series.map((s) => (
              <path key={s.name} d={s.path} fill="none" stroke={s.color} strokeWidth={1.5} />
            ))}
          </svg>

          <ul className="chart__legend">
            {chart.series.map((s) => (
              <li key={s.name}>
                <span className="chart__swatch" style={{ background: s.color }} />
                <span className="mono">{s.name}</span>
                <span className="mono chart__value">{s.last.toFixed(2)}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
