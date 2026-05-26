import { useEffect, useState } from 'react';
import { api, Snapshot } from '../api';

export default function Home() {
  const [snap, setSnap] = useState<Snapshot | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    api.snapshot().then(setSnap).catch((e) => setErr(String(e)));
  }, []);

  return (
    <>
      <h2>Overview</h2>
      <p className="subtitle">Live pipeline at a glance — pulled from tracker.xlsx via the Java API.</p>

      {err && <div className="error">Backend not reachable: {err}. Start it with <code>cd backend && mvn spring-boot:run</code>.</div>}

      {snap && (
        <div className="section">
          <h3>Pipeline snapshot</h3>
          <div className="kpi-grid">
            <Kpi label="Total applications" value={snap.totalApplications ?? 0} />
            <Kpi label="In progress" value={snap.inProgress ?? 0} />
            <Kpi label="Interviewed" value={snap.interviewed ?? 0} />
            <Kpi label="Yet to be interviewed" value={snap.yetToBeInterviewed ?? 0} />
            <Kpi label="Top-10 hits" value={snap.top10Hits ?? 0} />
            <Kpi label="Offer" value={snap.Offer ?? 0} />
            <Kpi label="Hired" value={snap.Hired ?? 0} />
            <Kpi label="Rejected" value={snap.Rejected ?? 0} />
          </div>
        </div>
      )}
    </>
  );
}

function Kpi({ label, value }: { label: string; value: number }) {
  return (
    <div className="kpi">
      <div className="label">{label}</div>
      <div className="value">{value.toLocaleString()}</div>
    </div>
  );
}
