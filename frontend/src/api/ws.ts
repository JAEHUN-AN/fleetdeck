import { Client, type IMessage } from '@stomp/stompjs';
import type { EquipmentState, Mission, RobotState, SensorAlarm } from '../types/telemetry';

const RECONNECT_DELAY_MS = 3000;

export interface FleetSocketHandlers {
  onRobot: (robot: RobotState) => void;
  onEquipment: (equipment: EquipmentState) => void;
  onMission: (mission: Mission) => void;
  onAlarm: (alarm: SensorAlarm) => void;
  onStatus: (connected: boolean) => void;
  onError: (message: string) => void;
}

function parse<T>(message: IMessage, onError: (m: string) => void): T | null {
  try {
    return JSON.parse(message.body) as T;
  } catch {
    onError(`invalid JSON on ${message.headers.destination ?? 'unknown topic'}`);
    return null;
  }
}

/** STOMP over WebSocket 연결. 반환된 함수를 호출하면 연결을 끊는다. */
export function connectFleetSocket(handlers: FleetSocketHandlers): () => void {
  const protocol = window.location.protocol === 'https:' ? 'wss' : 'ws';
  const client = new Client({
    brokerURL: `${protocol}://${window.location.host}/ws`,
    reconnectDelay: RECONNECT_DELAY_MS,
    onConnect: () => {
      handlers.onStatus(true);
      client.subscribe('/topic/robots', (m) => {
        const robot = parse<RobotState>(m, handlers.onError);
        if (robot) handlers.onRobot(robot);
      });
      client.subscribe('/topic/equipment', (m) => {
        const eq = parse<EquipmentState>(m, handlers.onError);
        if (eq) handlers.onEquipment(eq);
      });
      client.subscribe('/topic/missions', (m) => {
        const mission = parse<Mission>(m, handlers.onError);
        if (mission) handlers.onMission(mission);
      });
      client.subscribe('/topic/alarms', (m) => {
        const alarm = parse<SensorAlarm>(m, handlers.onError);
        if (alarm) handlers.onAlarm(alarm);
      });
    },
    onWebSocketClose: () => handlers.onStatus(false),
    onStompError: (frame) => handlers.onError(frame.headers.message ?? 'STOMP error'),
  });

  client.activate();
  return () => {
    void client.deactivate();
  };
}
