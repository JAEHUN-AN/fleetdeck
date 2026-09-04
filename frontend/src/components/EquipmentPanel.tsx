import type { EquipmentState } from '../types/telemetry';

interface EquipmentPanelProps {
  equipment: EquipmentState[];
}

const STATUS_LABEL: Record<EquipmentState['status'], string> = {
  RUNNING: '가동',
  STOPPED: '정지',
  ALARM: '알람',
};

export function EquipmentPanel({ equipment }: EquipmentPanelProps) {
  if (equipment.length === 0) {
    return <p className="empty">설비 상태 대기 중…</p>;
  }

  return (
    <ul className="equipment-list">
      {equipment.map((eq) => (
        <li key={eq.equipmentId} className={`equipment-card equipment-card--${eq.status.toLowerCase()}`}>
          <div className="equipment-card__head">
            <span className="mono">{eq.equipmentId}</span>
            <span className="badge">{STATUS_LABEL[eq.status]}</span>
          </div>
          <div className="equipment-card__body">
            <span className="equipment-card__throughput mono">{eq.throughputPerMin ?? 0}</span>
            <span className="equipment-card__unit">개/분</span>
          </div>
          {eq.alarmCode && <div className="equipment-card__alarm mono">{eq.alarmCode}</div>}
        </li>
      ))}
    </ul>
  );
}
