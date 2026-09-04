// backend 의 RobotStateMessage / EquipmentStateMessage / Mission 과 1:1.

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
  createdAt: string;
  updatedAt: string;
}

export type RobotActivity = 'charging' | 'driving' | 'paused' | 'idle';

export function robotActivity(robot: RobotState): RobotActivity {
  if (robot.batteryState?.charging) return 'charging';
  if (robot.paused) return 'paused';
  if (robot.driving) return 'driving';
  return 'idle';
}
