import { robotActivity, type EquipmentState, type RobotState } from '../types/telemetry';

interface FleetMapProps {
  robots: RobotState[];
  equipment: EquipmentState[];
  width?: number;
  height?: number;
}

const GRID_STEP = 5;
const ROBOT_RADIUS = 0.55;
const HEADING_LENGTH = 1.1;
const EQUIPMENT_W = 6;
const EQUIPMENT_H = 1.6;

/** 시뮬레이터 좌표(m)를 그대로 viewBox 단위로 쓰는 2D 맵. W6 에서 Three.js 뷰로 확장. */
export function FleetMap({ robots, equipment, width = 40, height = 25 }: FleetMapProps) {
  const verticals = Array.from({ length: Math.floor(width / GRID_STEP) + 1 }, (_, i) => i * GRID_STEP);
  const horizontals = Array.from({ length: Math.floor(height / GRID_STEP) + 1 }, (_, i) => i * GRID_STEP);

  return (
    <svg
      className="fleet-map"
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="xMidYMid meet"
      role="img"
      aria-label="로봇과 설비 위치 맵"
    >
      <rect className="map-floor" x={0} y={0} width={width} height={height} />

      {verticals.map((x) => (
        <line key={`v${x}`} className="map-grid" x1={x} y1={0} x2={x} y2={height} />
      ))}
      {horizontals.map((y) => (
        <line key={`h${y}`} className="map-grid" x1={0} y1={y} x2={width} y2={y} />
      ))}

      {equipment.map((eq) =>
        eq.x === undefined || eq.y === undefined ? null : (
          <g key={eq.equipmentId} className={`equipment equipment--${eq.status.toLowerCase()}`}>
            <rect x={eq.x - EQUIPMENT_W / 2} y={eq.y - EQUIPMENT_H / 2} width={EQUIPMENT_W} height={EQUIPMENT_H} rx={0.2} />
            <text x={eq.x} y={eq.y + 0.25} textAnchor="middle">{eq.equipmentId}</text>
          </g>
        ),
      )}

      {robots.map((robot) => {
        const pos = robot.agvPosition;
        if (!pos) return null;
        const hx = pos.x + Math.cos(pos.theta) * HEADING_LENGTH;
        const hy = pos.y + Math.sin(pos.theta) * HEADING_LENGTH;
        return (
          <g key={robot.serialNumber} className={`robot robot--${robotActivity(robot)}`}>
            <line x1={pos.x} y1={pos.y} x2={hx} y2={hy} className="robot-heading" />
            <circle cx={pos.x} cy={pos.y} r={ROBOT_RADIUS} />
            <text x={pos.x} y={pos.y - ROBOT_RADIUS - 0.35} textAnchor="middle">{robot.serialNumber}</text>
          </g>
        );
      })}
    </svg>
  );
}
