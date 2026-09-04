import { EquipmentPanel } from './components/EquipmentPanel';
import { FleetMap } from './components/FleetMap';
import { RobotList } from './components/RobotList';
import { useFleet } from './hooks/useFleet';

export function App() {
  const { robots, equipment, missions, connected, lastError } = useFleet();
  const alarms = equipment.filter((e) => e.status === 'ALARM').length;

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
          <Stat label="미션" value={missions.length} />
          <span className={`conn ${connected ? 'conn--on' : 'conn--off'}`}>
            {connected ? 'LIVE' : 'RECONNECTING'}
          </span>
        </div>
      </header>

      {lastError && <div className="banner banner--error">{lastError}</div>}

      <main className="layout">
        <section className="panel panel--map" aria-labelledby="map-heading">
          <h2 id="map-heading">현장 맵</h2>
          <FleetMap robots={robots} equipment={equipment} />
        </section>
        <aside className="side">
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

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'alarm' }) {
  return (
    <div className={`stat ${tone ? `stat--${tone}` : ''}`}>
      <span className="stat__value mono">{value}</span>
      <span className="stat__label">{label}</span>
    </div>
  );
}
