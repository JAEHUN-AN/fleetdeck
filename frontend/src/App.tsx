import { EquipmentPanel } from './components/EquipmentPanel';
import { FleetMap } from './components/FleetMap';
import { MissionPanel } from './components/MissionPanel';
import { RobotList } from './components/RobotList';
import { useFleet } from './hooks/useFleet';

export function App() {
  const { robots, equipment, missions, nodes, connected, lastError } = useFleet();

  const alarms = equipment.filter((e) => e.status === 'ALARM').length;
  const openMissions = missions.filter((m) => m.status !== 'DONE' && m.status !== 'FAILED').length;
  const doneMissions = missions.filter((m) => m.status === 'DONE').length;

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand__mark">fleetdeck</span>
          <span className="brand__sub">Robot Control System · warehouse-a</span>
        </div>
        <div className="stats">
          <Stat label="로봇" value={robots.length} />
          <Stat label="설비" value={equipment.length} />
          <Stat label="알람" value={alarms} tone={alarms > 0 ? 'alarm' : undefined} />
          <Stat label="진행" value={openMissions} />
          <Stat label="완료" value={doneMissions} tone="ok" />
          <span className={`conn ${connected ? 'conn--on' : 'conn--off'}`}>
            {connected ? 'LIVE' : 'RECONNECTING'}
          </span>
        </div>
      </header>

      {lastError && <div className="banner banner--error">{lastError}</div>}

      <main className="layout">
        <section className="panel panel--map" aria-labelledby="map-heading">
          <h2 id="map-heading">현장 맵</h2>
          <FleetMap robots={robots} equipment={equipment} nodes={nodes} />
        </section>
        <aside className="side">
          <section className="panel" aria-labelledby="missions-heading">
            <h2 id="missions-heading">미션</h2>
            <MissionPanel missions={missions} nodes={nodes} />
          </section>
          <section className="panel" aria-labelledby="robots-heading">
            <h2 id="robots-heading">로봇</h2>
            <RobotList robots={robots} />
          </section>
          <section className="panel" aria-labelledby="equipment-heading">
            <h2 id="equipment-heading">설비</h2>
            <EquipmentPanel equipment={equipment} />
          </section>
        </aside>
      </main>
    </div>
  );
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'alarm' | 'ok' }) {
  return (
    <div className={`stat ${tone ? `stat--${tone}` : ''}`}>
      <span className="stat__value mono">{value}</span>
      <span className="stat__label">{label}</span>
    </div>
  );
}
