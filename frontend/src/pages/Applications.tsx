import { useEffect, useState } from 'react';
import { api, Application } from '../api';

export default function Applications() {
  const [rows, setRows] = useState<Application[]>([]);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => { api.applications().then(setRows).catch((e) => setErr(String(e))); }, []);

  return (
    <>
      <h2>Applications</h2>
      <p className="subtitle">All rows from tracker.xlsx → Applications. Read-only for now; editing arrives in a later chunk.</p>
      {err && <div className="error">{err}</div>}
      <div className="section" style={{ overflowX: 'auto' }}>
        <table>
          <thead>
            <tr>
              <th>Date</th>
              <th>Company</th>
              <th>Title</th>
              <th>Location</th>
              <th>Mode</th>
              <th># at submit</th>
              <th>Path</th>
              <th>Status</th>
              <th>Recruiter</th>
            </tr>
          </thead>
          <tbody>
            {rows.length === 0 && (
              <tr><td colSpan={9} style={{ color: '#6b7280', fontStyle: 'italic' }}>No applications yet.</td></tr>
            )}
            {rows.map((r) => (
              <tr key={r.rowId}>
                <td>{(r.applicationDate ?? '').toString().slice(0, 16).replace('T', ' ')}</td>
                <td>{r.company}</td>
                <td>{r.jobTitle}</td>
                <td>{r.location}</td>
                <td>{r.workMode}</td>
                <td className="num">{r.applicantCountAtSubmit}</td>
                <td>{r.applicationPath}</td>
                <td>{r.status}</td>
                <td>{r.recruiterContact}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  );
}
