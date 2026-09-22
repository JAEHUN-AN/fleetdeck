import type {
  EquipmentState,
  MapNode,
  Mission,
  MissionType,
  RobotState,
  SensorAlarm,
} from '../types/telemetry';

const API_BASE = '/api';

async function getJson<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`);
  if (!res.ok) {
    throw new Error(`GET ${path} failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as T;
}

export function fetchRobots(): Promise<RobotState[]> {
  return getJson<RobotState[]>('/robots');
}

export function fetchEquipment(): Promise<EquipmentState[]> {
  return getJson<EquipmentState[]>('/equipment');
}

export function fetchMissions(): Promise<Mission[]> {
  return getJson<Mission[]>('/missions');
}

export function fetchMapNodes(): Promise<MapNode[]> {
  return getJson<MapNode[]>('/map/nodes');
}

export interface CreateMissionInput {
  type: MissionType;
  fromNode: string;
  toNode: string;
  sourceRef?: string;
}

export async function createMission(input: CreateMissionInput): Promise<Mission> {
  const res = await fetch(`${API_BASE}/missions`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(input),
  });
  if (!res.ok) {
    throw new Error(`POST /missions failed: ${res.status} ${res.statusText}`);
  }
  return (await res.json()) as Mission;
}

/**
 * 최근 FDC 경보. 실시간은 STOMP 로 오지만, 화면을 새로 열었을 때 비어 보이면
 * 아무 일도 없었던 것처럼 읽힌다.
 */
export function fetchAlarms(limit = 50): Promise<SensorAlarm[]> {
  return getJson<SensorAlarm[]>(`/alarms?limit=${limit}`);
}
