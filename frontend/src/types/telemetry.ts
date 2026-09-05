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

export type RobotActivity = 'charging' | 'driving' | 'paused' | 'idle';

export function robotActivity(robot: RobotState): RobotActivity {
  if (robot.batteryState?.charging) return 'charging';
  if (robot.paused) return 'paused';
  if (robot.driving) return 'driving';
  return 'idle';
}

/** 로봇이 지금 수행 중인 주문의 남은 노드 수. */
export function remainingNodes(robot: RobotState): number {
  return robot.nodeStates?.length ?? 0;
}
