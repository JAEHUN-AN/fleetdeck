export interface HistoryPoint {
  /** 구간 시작 시각 (ISO-8601) */
  bucket: string;
  /** 계열 이름 — 로봇 시리얼, 설비 ID, 또는 'fleet' */
  series: string;
  value: number;
}

export type HistoryMetric = 'robot-battery' | 'equipment-throughput' | 'fleet-driving-ratio';

/**
 * window 와 bucket 은 ISO-8601 duration (PT30M, PT1M).
 * 서버가 점 개수를 500 이하로 제한하므로 큰 구간을 요청해도 응답은 유계다.
 */
export async function fetchHistory(
  metric: HistoryMetric,
  window = 'PT30M',
  bucket = 'PT1M',
): Promise<HistoryPoint[]> {
  const params = new URLSearchParams({ window, bucket });
  const res = await fetch(`/api/history/${metric}?${params}`);
  if (!res.ok) {
    throw new Error(`GET /api/history/${metric} failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as HistoryPoint[];
}

/** 계열별로 묶고 시간순으로 정렬한다. */
export function groupBySeries(points: HistoryPoint[]): Map<string, HistoryPoint[]> {
  const grouped = new Map<string, HistoryPoint[]>();
  for (const point of points) {
    const existing = grouped.get(point.series);
    if (existing) {
      existing.push(point);
    } else {
      grouped.set(point.series, [point]);
    }
  }
  for (const list of grouped.values()) {
    list.sort((a, b) => a.bucket.localeCompare(b.bucket));
  }
  return grouped;
}
