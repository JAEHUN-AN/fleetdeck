import { robotActivity, type EquipmentState, type MapNode, type RobotState } from '../types/telemetry';

interface FleetMapProps {
  robots: RobotState[];
  equipment: EquipmentState[];
  nodes: MapNode[];
  width?: number;
  height?: number;
}

const GRID_STEP = 5;
const ROBOT_RADIUS = 0.55;
const HEADING_LENGTH = 1.1;
const EQUIPMENT_W = 6;
const EQUIPMENT_H = 1.6;
const NODE_RADIUS = 0.42;

/**
 * 시뮬레이터 좌표(m)를 그대로 viewBox 단위로 쓰는 2D 맵.
 *
 * 현장 좌표계는 y 가 위로 증가하는데 SVG 는 아래로 증가한다. 바깥 <g> 에서 한 번 뒤집어
 * 안쪽 요소가 물리 좌표를 그대로 쓰게 하고, 글자만 다시 뒤집어 정방향으로 읽히게 한다.
 */
export function FleetMap({ robots, equipment, nodes, width = 40, height = 25 }: FleetMapProps) {
  const verticals = Array.from({ length: Math.floor(width / GRID_STEP) + 1 }, (_, i) => i * GRID_STEP);
  const horizontals = Array.from({ length: Math.floor(height / GRID_STEP) + 1 }, (_, i) => i * GRID_STEP);

  /** 뒤집힌 좌표계 안의 라벨은 다시 뒤집어야 바로 보인다. */
  const label = (x: number, y: number) => `translate(${x} ${y}) scale(1 -1)`;

  return (
    <svg
      className="fleet-map"
      viewBox={`0 0 ${width} ${height}`}
      preserveAspectRatio="xMidYMid meet"
      role="img"
      aria-label="로봇, 설비, 노드 위치 맵"
    >
      <rect className="map-floor" x={0} y={0} width={width} height={height} />

      <g transform={`translate(0 ${height}) scale(1 -1)`}>
        {verticals.map((x) => (
          <line key={`v${x}`} className="map-grid" x1={x} y1={0} x2={x} y2={height} />
        ))}
        {horizontals.map((y) => (
          <line key={`h${y}`} className="map-grid" x1={0} y1={y} x2={width} y2={y} />
        ))}

        {/* 창고 노드: 입고(P) / 투입(D) / 경유(W) */}
        {nodes.map((n) => (
          <g key={n.nodeId} className={`node node--${n.kind.toLowerCase()}`}>
            <circle cx={n.x} cy={n.y} r={NODE_RADIUS} />
            {/* 노드 라벨은 아래에. 로봇이 노드에 정차하면 로봇 라벨(위)과 겹치기 때문. */}
            <text transform={label(n.x, n.y - NODE_RADIUS - 0.45)} textAnchor="middle">{n.nodeId}</text>
          </g>
        ))}

        {equipment.map((eq) =>
          eq.x === undefined || eq.y === undefined ? null : (
            <g key={eq.equipmentId} className={`equipment equipment--${eq.status.toLowerCase()}`}>
              <rect
                x={eq.x - EQUIPMENT_W / 2}
                y={eq.y - EQUIPMENT_H / 2}
                width={EQUIPMENT_W}
                height={EQUIPMENT_H}
                rx={0.2}
              />
              <text transform={label(eq.x, eq.y - 0.25)} textAnchor="middle">{eq.equipmentId}</text>
            </g>
          ),
        )}

        {/* 주행 중인 로봇의 남은 경로 */}
        {robots.map((robot) => {
          const pos = robot.agvPosition;
          const remaining = robot.nodeStates ?? [];
          if (!pos || remaining.length === 0) return null;
          const points = remaining
            .map((ns) => nodes.find((n) => n.nodeId === ns.nodeId))
            .filter((n): n is MapNode => n !== undefined);
          if (points.length === 0) return null;
          const d = [`M ${pos.x} ${pos.y}`, ...points.map((p) => `L ${p.x} ${p.y}`)].join(' ');
          return <path key={`route-${robot.serialNumber}`} className="robot-route" d={d} />;
        })}

        {robots.map((robot) => {
          const pos = robot.agvPosition;
          if (!pos) return null;
          const hx = pos.x + Math.cos(pos.theta) * HEADING_LENGTH;
          const hy = pos.y + Math.sin(pos.theta) * HEADING_LENGTH;
          return (
            <g key={robot.serialNumber} className={`robot robot--${robotActivity(robot)}`}>
              <line x1={pos.x} y1={pos.y} x2={hx} y2={hy} className="robot-heading" />
              <circle cx={pos.x} cy={pos.y} r={ROBOT_RADIUS} />
              <text transform={label(pos.x, pos.y + ROBOT_RADIUS + 0.7)} textAnchor="middle">
                {robot.serialNumber}
              </text>
            </g>
          );
        })}
      </g>
    </svg>
  );
}
