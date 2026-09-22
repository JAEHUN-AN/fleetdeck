// backend 의 RobotStateMessage / EquipmentStateMessage / Mission / WarehouseMap.Node 와 1:1.

export interface AgvPosition {
  x: number;
  y: number;
  theta: number;
  mapId: string;
}

export interface BatteryState {
  batteryCharge: number;
  charging: boolean;
}

export interface NodeState {
  nodeId: string;
  sequenceId: number;
  released: boolean;
}

export interface RobotErrorEntry {
  errorType: string;
  errorLevel: string;
  errorDescription?: string;
}

export interface RobotState {
  headerId: number;
  timestamp: string;
  manufacturer: string;
  serialNumber: string;
  orderId?: string;
  lastNodeId?: string;
  driving: boolean;
  paused: boolean;
  agvPosition?: AgvPosition;
  batteryState?: BatteryState;
  operatingMode?: string;
  nodeStates?: NodeState[];
  errors?: RobotErrorEntry[];
  /** 관제가 판단한 생존 여부. 백엔드 수신 시각 기준이며 로봇 시계와 무관하다. */
  online: boolean;
  lastSeenAt?: string;
}

export type EquipmentStatus = 'RUNNING' | 'STOPPED' | 'ALARM';

export interface EquipmentState {
  equipmentId: string;
  equipmentType: string;
  status: EquipmentStatus;
  throughputPerMin?: number;
  alarmCode?: string;
  x?: number;
  y?: number;
  timestamp: string;
}

export type MissionType = 'TRANSPORT' | 'CHARGE' | 'PARK';
export type MissionStatus = 'PENDING' | 'ASSIGNED' | 'RUNNING' | 'DONE' | 'FAILED';

export interface Mission {
  id: number;
  type: MissionType;
  fromNode: string;
  toNode: string;
  status: MissionStatus;
  assignedRobot?: string;
  sourceRef?: string;
  /** 회수되어 재배정된 횟수. 상한을 넘기면 백엔드가 FAILED 로 포기한다. */
  retryCount: number;
  createdAt: string;
  updatedAt: string;
}

export type NodeKind = 'PICK' | 'DROP' | 'WAYPOINT';

export interface MapNode {
  nodeId: string;
  x: number;
  y: number;
  kind: NodeKind;
}

export type RobotActivity = 'offline' | 'charging' | 'driving' | 'returning' | 'paused' | 'idle';

/**
 * 오프라인이 최우선 — 통신이 끊긴 로봇의 마지막 상태는 신뢰할 수 없다.
 * 주행 중이지만 남은 노드가 없으면 대기 슬롯으로 복귀하는 중이다 (배정은 여전히 가능).
 */
export function robotActivity(robot: RobotState): RobotActivity {
  if (!robot.online) return 'offline';
  if (robot.batteryState?.charging) return 'charging';
  if (robot.paused) return 'paused';
  if (robot.driving) return remainingNodes(robot) > 0 ? 'driving' : 'returning';
  return 'idle';
}

/** 로봇이 지금 수행 중인 주문의 남은 노드 수. */
export function remainingNodes(robot: RobotState): number {
  return robot.nodeStates?.length ?? 0;
}

/** 탐지기 이름. backend 의 Detector.name() 과 1:1. */
export type DetectorName = 'THRESHOLD' | 'MOVING_SIGMA' | 'EWMA';

/**
 * FDC 경보. backend 의 SensorTelemetryService.AlarmView 와 1:1.
 *
 * score 는 기준 대비 벗어난 정도(시그마 배수)다. 어느 탐지기가 올렸는지를 같이 보여줘야
 * 한다 — 이동 통계가 올린 단발 경보와 EWMA 가 올린 지속 경보는 대응이 다르다.
 */
export interface SensorAlarm {
  equipmentId: string;
  channel: string;
  detector: DetectorName;
  value: number;
  score: number;
  at: string;
}

/** 탐지기별로 무엇을 잡은 것인지. 화면에 그대로 띄운다. */
export const DETECTOR_LABEL: Record<DetectorName, string> = {
  THRESHOLD: '한계 초과',
  MOVING_SIGMA: '순간 이상',
  EWMA: '추세 이탈',
};
