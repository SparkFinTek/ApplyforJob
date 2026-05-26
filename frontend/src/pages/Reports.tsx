import { useEffect, useState } from 'react';
import { api, PeriodBucket, Snapshot } from '../api';

type Tab = 'daily' | 'weekly' | 'monthly';

export default function Reports() {
  const [tab, setTab] = useState<Tab>('daily');
  const [snap, setSnap] = useState<Snapshot | null>(null);
  const [rows, setRows] = useState<PeriodBucket[]>([]);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => { api.snapshot().then(setSnap).catch((e) => setErr(String(e))); }, []);
  useEffect(() => {
    setErr(null); setRows([]);
    const fetcher = tab === 'daily' ? api.daily(30) : tab === 'weekly' ? api.weekly(12) : api.monthly(12);
    fetcher.then(setRows).catch((e) => setErr(String(e)));
  }, [tab]);

  return (
    <>
      <h2>Reports</h2>
      <p className="subtitle">Daily, weekly, and monthly counts of applied / in-progress / interviewed / yet-to-be-interviewed.</p>

      {err && <div className="error">Backend not reachable: {err}. Start the backend: <code>cd backend && mvn spring-boot:run</code>.</div>}

      {snap && (
        <div className="section">
          <h3>Pipeline (current)</h3>
          <div className="kpi-grid">
            <Kpi label="Total applications" value={snap.totalApplications ?? 0} />
            <Kpi label="In progress" value={snap.inProgress ?? 0} />
            <Kpi label="Interviewed" value={snap.interviewed ?? 0} />
            <Kpi label="Yet to be interviewed" value={snap.yetToBeInterviewed ?? 0} />
            <Kpi label="Top-10 hits" value={snap.top10Hits ?? 0} />
            <Kpi label="Applied" value={snap.Applied ?? 0} />
            <Kpi label="Interview Scheduled" value={snap['Interview Scheduled'] ?? 0} />
            <Kpi label="Offer" value={snap.Offer ?? 0} />
            <Kpi label="Hired" value={snap.Hired ?? 0} />
            <Kpi label="Rejected" value={snap.Rejected ?? 0} />
          </div>
        </div>
      )}

      <div className="section">
        <h3>By period</h3>
        <div className="tab-strip">
          <button className={tab === 'daily' ? 'active' : ''} onClick={() => setTab('daily')}>Daily (last 30d)</button>
          <button className={tab === 'weekly' ? 'active' : ''} onClick={() => setTab('weekly')}>Weekly (last 12w)</button>
          <button className={tab === 'monthly' ? 'active' : ''} onClick={() => setTab('monthly')}>Monthly (last 12m)</button>
        </div>
        <table>
          <thead>
            <tr>
              <th>Period</th>
              <th>Submitted</th>
              <th>Interviewed</th>
              <th>Yet to be interviewed</th>
              <th>In progress</th>
              <th>Top-10 hits</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr><td colSpan={6} style={{ color: '#6b7280', fontStyle: 'italic' }}>No data yet — apply to a job and the row will appear here.</td></tr>
            )}
            {rows.map((b) => (
              <tr key={b.period}>
                <td>{b.period}</td>
                <td className="num">{b.submitted}</td>
                <td className="num">{b.interviewed}</td>
                <td className="num">{b.yetToBeInterviewed}</td>
                <td className="num">{b.inProgress}</td>
                <td className="num">{b.top10Hits}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
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
