import { useState } from 'react';
import { createMission } from '../api/rest';
import type { MapNode, Mission, MissionStatus } from '../types/telemetry';

interface MissionPanelProps {
  missions: Mission[];
  nodes: MapNode[];
}

const STATUS_LABEL: Record<MissionStatus, string> = {
  PENDING: '대기',
  ASSIGNED: '배정',
  RUNNING: '주행',
  DONE: '완료',
  FAILED: '실패',
};

const VISIBLE_LIMIT = 12;

export function MissionPanel({ missions, nodes }: MissionPanelProps) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const picks = nodes.filter((n) => n.kind === 'PICK');
  const drops = nodes.filter((n) => n.kind === 'DROP');

  async function handleCreate() {
    if (picks.length === 0 || drops.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      const from = picks[Math.floor(Math.random() * picks.length)].nodeId;
      const to = drops[Math.floor(Math.random() * drops.length)].nodeId;
      await createMission({ type: 'TRANSPORT', fromNode: from, toNode: to, sourceRef: 'MANUAL' });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <div className="mission-actions">
        <button type="button" onClick={handleCreate} disabled={busy || picks.length === 0}>
          {busy ? '생성 중…' : '+ 출고 주문'}
        </button>
        {error && <span className="mission-actions__error">{error}</span>}
      </div>

      {missions.length === 0 ? (
        <p className="empty">미션 없음</p>
      ) : (
        <ul className="mission-list">
          {missions.slice(0, VISIBLE_LIMIT).map((m) => (
            <li key={m.id} className={`mission mission--${m.status.toLowerCase()}`}>
              <div className="mission__head">
                <span className="mono mission__route">
                  {m.fromNode} <span className="mission__arrow">→</span> {m.toNode}
                </span>
                <span className={`badge badge--${m.status.toLowerCase()}`}>{STATUS_LABEL[m.status]}</span>
              </div>
              <div className="mission__meta mono">
                <span>#{m.id}</span>
                <span>{m.assignedRobot ?? '미배정'}</span>
                {m.retryCount > 0 && (
                  <span className="mission__retry" title={`재시도 ${m.retryCount}회`}>
                    ↻{m.retryCount}
                  </span>
                )}
                <span className="mission__ref">{m.sourceRef ?? ''}</span>
              </div>
            </li>
          ))}
        </ul>
      )}
    </>
  );
}
