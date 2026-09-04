import type { EquipmentState, Mission, MissionType, RobotState } from '../types/telemetry';

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
