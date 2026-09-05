import { robotActivity, type RobotState } from '../types/telemetry';

interface RobotListProps {
  robots: RobotState[];
}

const ACTIVITY_LABEL: Record<ReturnType<typeof robotActivity>, string> = {
  offline: '오프라인',
  charging: '충전',
  driving: '주행',
  returning: '복귀',
  paused: '일시정지',
  idle: '대기',
};

/** 마지막 수신으로부터 흐른 시간. 오프라인 로봇이 언제 끊겼는지 보여준다. */
function sinceLastSeen(lastSeenAt: string | undefined): string {
  if (!lastSeenAt) return '–';
  const seconds = Math.max(0, Math.round((Date.now() - new Date(lastSeenAt).getTime()) / 1000));
  if (seconds < 60) return `${seconds}초 전`;
  const minutes = Math.round(seconds / 60);
  return minutes < 60 ? `${minutes}분 전` : `${Math.round(minutes / 60)}시간 전`;
}

export function RobotList({ robots }: RobotListProps) {
  if (robots.length === 0) {
    return <p className="empty">로봇 텔레메트리 대기 중…</p>;
  }

  return (
    <table className="robot-table">
      <thead>
        <tr>
          <th>로봇</th>
          <th>상태</th>
          <th>배터리</th>
          <th>위치</th>
        </tr>
      </thead>
      <tbody>
        {robots.map((robot) => {
          const activity = robotActivity(robot);
          const battery = robot.batteryState?.batteryCharge ?? 0;
          const pos = robot.agvPosition;
          return (
            <tr key={robot.serialNumber} className={activity === 'offline' ? 'row--offline' : undefined}>
              <td className="mono">{robot.serialNumber}</td>
              <td>
                <span className={`badge badge--${activity}`}>{ACTIVITY_LABEL[activity]}</span>
              </td>
              <td>
                {/* 오프라인이면 마지막 값이 이미 낡았으므로 배터리 대신 경과 시간을 보여준다. */}
                {activity === 'offline' ? (
                  <span className="mono robot-table__stale">{sinceLastSeen(robot.lastSeenAt)}</span>
                ) : (
                  <div className="battery" aria-label={`배터리 ${battery.toFixed(0)}%`}>
                    <div className="battery-fill" style={{ width: `${Math.min(100, battery)}%` }} />
                    <span className="mono">{battery.toFixed(0)}%</span>
                  </div>
                )}
              </td>
              <td className="mono">{pos ? `${pos.x.toFixed(1)}, ${pos.y.toFixed(1)}` : '–'}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
