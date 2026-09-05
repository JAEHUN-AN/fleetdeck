import { useEffect, useMemo, useState } from 'react';
import { fetchHistory, groupBySeries, type HistoryMetric, type HistoryPoint } from '../api/history';

interface HistoryChartProps {
  metric: HistoryMetric;
  title: string;
  unit: string;
  /** y축 상한을 고정한다. 비우면 데이터 최대값에 맞춘다. */
  yMax?: number;
  /** 갱신 주기(ms). 시계열이라 실시간일 필요는 없다. */
  refreshMs?: number;
}

const VIEW_W = 320;
const VIEW_H = 90;
const PAD_LEFT = 4;
const PAD_BOTTOM = 4;
const DEFAULT_REFRESH_MS = 15_000;

/** 계열마다 다른 색. 로봇 8대면 충분하고, 넘치면 순환한다. */
const SERIES_COLORS = [
  'oklch(78% 0.15 200)',
  'oklch(76% 0.17 150)',
  'oklch(80% 0.16 80)',
  'oklch(72% 0.18 320)',
  'oklch(70% 0.16 30)',
  'oklch(74% 0.13 250)',
  'oklch(68% 0.15 120)',
  'oklch(76% 0.12 280)',
];

function toPath(points: HistoryPoint[], minX: number, spanX: number, maxY: number): string {
  return points
    .map((p, i) => {
      const t = new Date(p.bucket).getTime();
      const x = PAD_LEFT + ((t - minX) / spanX) * (VIEW_W - PAD_LEFT);
      const y = (VIEW_H - PAD_BOTTOM) * (1 - Math.min(1, p.value / maxY));
      return `${i === 0 ? 'M' : 'L'} ${x.toFixed(2)} ${y.toFixed(2)}`;
    })
    .join(' ');
}

/**
 * TimescaleDB 의 time_bucket 집계를 꺾은선으로 그린다.
 *
 * 차트 라이브러리를 쓰지 않은 이유: 맵이 이미 SVG 라 렌더링 방식이 일관되고,
 * 이 정도 스파크라인에 의존성을 늘릴 이유가 없다.
 */
export function HistoryChart({ metric, title, unit, yMax, refreshMs }: HistoryChartProps) {
  const [points, setPoints] = useState<HistoryPoint[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    const load = () => {
      fetchHistory(metric)
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
    const timer = setInterval(load, refreshMs ?? DEFAULT_REFRESH_MS);
    return () => {
      cancelled = true;
      clearInterval(timer);
    };
  }, [metric, refreshMs]);

  const chart = useMemo(() => {
    if (points.length === 0) return null;

    const times = points.map((p) => new Date(p.bucket).getTime());
    const minX = Math.min(...times);
    const spanX = Math.max(1, Math.max(...times) - minX);
    const dataMax = Math.max(...points.map((p) => p.value));
    const maxY = yMax ?? Math.max(1, dataMax * 1.1);

    const series = [...groupBySeries(points).entries()].map(([name, list], i) => ({
      name,
      color: SERIES_COLORS[i % SERIES_COLORS.length],
      path: toPath(list, minX, spanX, maxY),
      last: list[list.length - 1]?.value ?? 0,
    }));

    return { series, maxY };
  }, [points, yMax]);

  return (
    <div className="chart">
      <div className="chart__head">
        <span className="chart__title">{title}</span>
        {chart && <span className="chart__max mono">최대 {Math.round(chart.maxY)}{unit}</span>}
      </div>

      {error && <p className="chart__error">{error}</p>}
      {!error && !chart && <p className="empty">데이터 수집 중…</p>}

      {chart && (
        <>
          <svg
            className="chart__svg"
            viewBox={`0 0 ${VIEW_W} ${VIEW_H}`}
            preserveAspectRatio="none"
            role="img"
            aria-label={`${title} 추이`}
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
                <span className="mono chart__value">{s.last.toFixed(0)}{unit}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
}
