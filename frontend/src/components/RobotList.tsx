import { robotActivity, type RobotState } from '../types/telemetry';

interface RobotListProps {
  robots: RobotState[];
}

const ACTIVITY_LABEL: Record<ReturnType<typeof robotActivity>, string> = {
  charging: '충전',
  driving: '주행',
  paused: '일시정지',
  idle: '대기',
};

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
            <tr key={robot.serialNumber}>
              <td className="mono">{robot.serialNumber}</td>
              <td>
                <span className={`badge badge--${activity}`}>{ACTIVITY_LABEL[activity]}</span>
              </td>
              <td>
                <div className="battery" aria-label={`배터리 ${battery.toFixed(0)}%`}>
                  <div className="battery-fill" style={{ width: `${Math.min(100, battery)}%` }} />
                  <span className="mono">{battery.toFixed(0)}%</span>
                </div>
              </td>
              <td className="mono">{pos ? `${pos.x.toFixed(1)}, ${pos.y.toFixed(1)}` : '–'}</td>
            </tr>
          );
        })}
      </tbody>
    </table>
  );
}
