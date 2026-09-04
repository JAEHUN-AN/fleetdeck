import { useEffect, useMemo, useState } from 'react';
import { fetchEquipment, fetchMapNodes, fetchMissions, fetchRobots } from '../api/rest';
import { connectFleetSocket } from '../api/ws';
import type { EquipmentState, MapNode, Mission, RobotState } from '../types/telemetry';

const MAX_MISSIONS = 50;

type ById<T> = Readonly<Record<string, T>>;

function indexBy<T>(items: T[], key: (item: T) => string): ById<T> {
  return Object.fromEntries(items.map((item) => [key(item), item]));
}

function sortedValues<T>(byId: ById<T>, key: (item: T) => string): T[] {
  return Object.values(byId).sort((a, b) => key(a).localeCompare(key(b)));
}

/** 새 미션을 앞에 두고 같은 id 의 옛 항목은 걷어낸다. 목록은 최신순. */
function mergeMission(prev: Mission[], incoming: Mission): Mission[] {
  return [incoming, ...prev.filter((m) => m.id !== incoming.id)].slice(0, MAX_MISSIONS);
}

export interface FleetSnapshot {
  robots: RobotState[];
  equipment: EquipmentState[];
  missions: Mission[];
  nodes: MapNode[];
  connected: boolean;
  lastError: string | null;
}

/** 초기 REST 스냅샷 + STOMP 실시간 갱신을 합쳐 현재 플릿 상태를 돌려준다. */
export function useFleet(): FleetSnapshot {
  const [robots, setRobots] = useState<ById<RobotState>>({});
  const [equipment, setEquipment] = useState<ById<EquipmentState>>({});
  const [missions, setMissions] = useState<Mission[]>([]);
  const [nodes, setNodes] = useState<MapNode[]>([]);
  const [connected, setConnected] = useState(false);
  const [lastError, setLastError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    Promise.all([fetchRobots(), fetchEquipment(), fetchMissions(), fetchMapNodes()])
      .then(([r, e, m, n]) => {
        if (cancelled) return;
        setRobots(indexBy(r, (x) => x.serialNumber));
        setEquipment(indexBy(e, (x) => x.equipmentId));
        setMissions(m);
        setNodes(n);
      })
      .catch((err: unknown) => {
        if (!cancelled) setLastError(err instanceof Error ? err.message : String(err));
      });

    const disconnect = connectFleetSocket({
      onRobot: (robot) => setRobots((prev) => ({ ...prev, [robot.serialNumber]: robot })),
      onEquipment: (eq) => setEquipment((prev) => ({ ...prev, [eq.equipmentId]: eq })),
      onMission: (mission) => setMissions((prev) => mergeMission(prev, mission)),
      onStatus: setConnected,
      onError: setLastError,
    });

    return () => {
      cancelled = true;
      disconnect();
    };
  }, []);

  const robotList = useMemo(() => sortedValues(robots, (r) => r.serialNumber), [robots]);
  const equipmentList = useMemo(() => sortedValues(equipment, (e) => e.equipmentId), [equipment]);

  return { robots: robotList, equipment: equipmentList, missions, nodes, connected, lastError };
}
