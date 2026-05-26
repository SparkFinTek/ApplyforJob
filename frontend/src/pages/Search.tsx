import { useEffect, useState } from 'react';
import { api, PendingJd } from '../api';

const WORK_MODES = ['Remote', 'Hybrid', 'On-site'] as const;
type WorkMode = typeof WORK_MODES[number];

// LinkedIn job-search URL params:
//   keywords=...   exact-phrase boolean: ("Director of Engineering" OR "VP of Engineering")
//   location=...   text location, e.g. "United States"
//   f_TPR=r7200    posted within last 2 hours (Spark's settled window)
//   f_WT=2,3       work mode codes: 1=on-site, 2=remote, 3=hybrid
//   sortBy=DD      most recent first
// Parse LinkedIn-style relative time strings ("30 minutes ago", "2 hours ago",
// "1 hour ago", "15 min", "1 day ago") into minutes. Returns null if unparseable.
function parsePostedMinutesAgo(text: string): number | null {
  const t = text.trim().toLowerCase();
  if (!t) return null;
  // First, "now"/"just now" → 0
  if (/^(just\s+)?now\b/.test(t)) return 0;
  const m = t.match(/(\d+)\s*(min|minute|minutes|hr|hour|hours|day|days|week|weeks|mo|month|months|yr|year|years)/);
  if (!m) {
    const num = Number(t);
    if (!isNaN(num) && num >= 0) return Math.round(num); // bare number = minutes
    return null;
  }
  const n = parseInt(m[1], 10);
  const u = m[2];
  if (u.startsWith('min')) return n;
  if (u.startsWith('hr') || u.startsWith('hour')) return n * 60;
  if (u.startsWith('day')) return n * 60 * 24;
  if (u.startsWith('week')) return n * 60 * 24 * 7;
  if (u.startsWith('mo') || u.startsWith('month')) return n * 60 * 24 * 30;
  if (u.startsWith('yr') || u.startsWith('year')) return n * 60 * 24 * 365;
  return null;
}

function buildLinkedInUrl(titles: string[], location: string, modes: WorkMode[], maxAgeMin: number): string {
  const cleanTitles = titles.map(t => t.trim()).filter(Boolean);
  const keywords = cleanTitles.length === 1
    ? `"${cleanTitles[0]}"`
    : '(' + cleanTitles.map(t => `"${t}"`).join(' OR ') + ')';
  const params = new URLSearchParams();
  if (keywords) params.set('keywords', keywords);
  if (location.trim()) params.set('location', location.trim());
  params.set('sortBy', 'DD');
  if (maxAgeMin > 0) {
    const seconds = Math.max(60, maxAgeMin * 60);
    params.set('f_TPR', `r${seconds}`);
  }
  const wtCode: Record<WorkMode, string> = { 'On-site': '1', 'Remote': '2', 'Hybrid': '3' };
  const wtVals = modes.map(m => wtCode[m]).filter(Boolean);
  if (wtVals.length > 0 && wtVals.length < 3) params.set('f_WT', wtVals.join(','));
  return `https://www.linkedin.com/jobs/search/?${params.toString()}`;
}

export default function Search() {
  // Form state — defaults match Spark's filter rules.
  const [titlesText, setTitlesText] = useState<string>(
    "Director of Engineering\nVP of Engineering"
  );
  const [location, setLocation] = useState<string>('United States');
  const [modes, setModes] = useState<WorkMode[]>(['Remote', 'Hybrid']);
  const [maxAgeMin, setMaxAgeMin] = useState<number>(120);   // hydrated from config.json on mount
  const [configLoaded, setConfigLoaded] = useState(false);
  const [savingConfig, setSavingConfig] = useState(false);

  // Capture box
  const [capCompany, setCapCompany] = useState('');
  const [capTitle, setCapTitle] = useState('');
  const [capLocation, setCapLocation] = useState('');
  const [capMode, setCapMode] = useState<WorkMode>('Remote');
  const [capUrl, setCapUrl] = useState('');
  const [capApplicants, setCapApplicants] = useState<string>('');
  const [capPostedRelative, setCapPostedRelative] = useState<string>('');
  const [capReposted, setCapReposted] = useState<boolean>(false);
  const [capUseBaseResume, setCapUseBaseResume] = useState<boolean>(false);
  // Auto-run frequency in minutes (0 = off). Persisted in config.json → schedule.autoRunMinutes.
  const [autoRunMin, setAutoRunMin] = useState<number>(0);
  const [capJd, setCapJd] = useState('');

  // Pending list
  const [pending, setPending] = useState<PendingJd[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<string | null>(null);
  const [allBusy, setAllBusy] = useState<boolean>(false);
  const [lastResult, setLastResult] = useState<unknown>(null);
  // Mark-submitted dialog state
  const [submitDlg, setSubmitDlg] = useState<{ id: string; company: string } | null>(null);
  const [dlgConfirmationId, setDlgConfirmationId] = useState('');
  const [dlgApplicationPath, setDlgApplicationPath] = useState('LinkedIn Easy Apply');
  const [dlgNote, setDlgNote] = useState('');

  async function loadPending() {
    try { setPending(await api.pendingList()); }
    catch (e) { setErr(String(e)); }
  }
  useEffect(() => { loadPending(); }, []);

  // Track the values we LOADED from config so we don't spuriously re-save them.
  const loadedRef = (typeof window !== 'undefined') ? ((window as any).__jobflowLoaded ||= { maxAgeMin: -1, autoRunMin: -1 }) : { maxAgeMin: -1, autoRunMin: -1 };

  // Hydrate maxAgeMin + autoRunMin from config.json so the UI matches backend state.
  useEffect(() => {
    api.getConfig().then((cfg: any) => {
      const v = cfg?.targeting?.postingMaxAgeMinutes;
      if (typeof v === 'number' && v > 0) { setMaxAgeMin(v); loadedRef.maxAgeMin = v; }
      const ar = cfg?.schedule?.autoRunMinutes;
      if (typeof ar === 'number' && ar >= 0) { setAutoRunMin(ar); loadedRef.autoRunMin = ar; }
      setConfigLoaded(true);
    }).catch(() => setConfigLoaded(true));
  }, []);

  // Auto-run loop — while page is open and autoRunMin > 0, fire processAll on interval.
  useEffect(() => {
    if (autoRunMin <= 0) return;
    const tick = () => {
      const unprocessedNow = pending.filter(p => !p.processed).length;
      if (unprocessedNow === 0 || allBusy || busyId) return;
      setInfo(`Auto-run firing at ${new Date().toLocaleTimeString()} — processing ${unprocessedNow} pending.`);
      processAll();
    };
    const ms = autoRunMin * 60 * 1000;
    const handle = setInterval(tick, ms);
    return () => clearInterval(handle);
  }, [autoRunMin, pending, allBusy, busyId]);

  // Persist autoRunMin to config.json under schedule.autoRunMinutes (debounced).
  // Skips spurious saves on initial hydrate by comparing against the loaded value.
  useEffect(() => {
    if (!configLoaded) return;
    if (loadedRef.autoRunMin === autoRunMin) return;
    const handle = setTimeout(async () => {
      try {
        const cfg: any = await api.getConfig();
        cfg.schedule = cfg.schedule || {};
        cfg.schedule.autoRunMinutes = autoRunMin;
        await api.putConfig(cfg);
        loadedRef.autoRunMin = autoRunMin;
      } catch (e) {
        setErr('Failed to persist auto-run setting: ' + String(e));
      }
    }, 600);
    return () => clearTimeout(handle);
  }, [autoRunMin, configLoaded]);

  // Whenever maxAgeMin changes after first load (and differs from what was loaded),
  // persist to config.json (debounced). Skips spurious saves on initial hydrate.
  useEffect(() => {
    if (!configLoaded) return;
    if (loadedRef.maxAgeMin === maxAgeMin) return;
    const handle = setTimeout(async () => {
      setSavingConfig(true);
      try {
        const cfg: any = await api.getConfig();
        cfg.targeting = cfg.targeting || {};
        cfg.targeting.postingMaxAgeMinutes = maxAgeMin;
        await api.putConfig(cfg);
        loadedRef.maxAgeMin = maxAgeMin;
      } catch (e) {
        setErr('Failed to persist posting-window to config.json: ' + String(e));
      } finally {
        setSavingConfig(false);
      }
    }, 600);
    return () => clearTimeout(handle);
  }, [maxAgeMin, configLoaded]);

  const titles = titlesText.split('\n').map(t => t.trim()).filter(Boolean);
  const linkedInUrl = buildLinkedInUrl(titles, location, modes, maxAgeMin);

  function toggleMode(m: WorkMode) {
    setModes(prev => prev.includes(m) ? prev.filter(x => x !== m) : [...prev, m]);
  }

  async function addToQueue() {
    setErr(null); setInfo(null);
    if (!capJd.trim() || !capCompany.trim() || !capTitle.trim()) {
      setErr('Company, title, and JD text are all required.');
      return;
    }
    if (capReposted) {
      setErr('This listing is marked Reposted — per policy, reposted listings are excluded. Pick another posting.');
      return;
    }
    const applicants = capApplicants ? Number(capApplicants) : null;
    if (applicants !== null && applicants >= 10) {
      setErr(`Applicant count is ${applicants} — past the top-10 window. Pick a fresher posting.`);
      return;
    }
    const postedMin = capPostedRelative.trim() ? parsePostedMinutesAgo(capPostedRelative) : null;
    if (capPostedRelative.trim() && postedMin === null) {
      setErr(`Could not parse "${capPostedRelative}" — try "30 minutes ago", "1 hour ago", or a number of minutes.`);
      return;
    }
    if (postedMin !== null && postedMin > maxAgeMin) {
      setErr(`Posted ${postedMin} min ago — outside the ${maxAgeMin}-min window. Skip this one.`);
      return;
    }
    try {
      await api.pendingAdd({
        title: capTitle, company: capCompany,
        location: capLocation || null, workMode: capMode,
        postingUrl: capUrl || null,
        applicantCount: applicants,
        postedMinutesAgo: postedMin,
        reposted: capReposted,
        useBaseResume: capUseBaseResume,
        jd: capJd,
      });
      setInfo(`Queued "${capTitle}" at ${capCompany}. ${capUseBaseResume ? 'Will submit base resume as-is.' : 'Will be tailored to the JD on Process.'}`);
      setCapCompany(''); setCapTitle(''); setCapLocation(''); setCapUrl(''); setCapApplicants(''); setCapPostedRelative(''); setCapReposted(false); setCapUseBaseResume(false); setCapJd('');
      loadPending();
    } catch (e) { setErr(String(e)); }
  }

  async function processOne(id: string) {
    setBusyId(id); setLastResult(null); setErr(null); setInfo(null);
    try {
      const res = await api.pendingProcess(id);
      setLastResult(res);
      const ok = (res as any).ok;
      setInfo(ok
        ? `Processed: archived to ${(res as any).archivePath}, tracker row ${(res as any).trackerRowId}`
        : `Skipped: ${(res as any).reason}`);
      loadPending();
    } catch (e) { setErr(String(e)); }
    finally { setBusyId(null); }
  }

  async function reprocessOne(id: string, useBaseResume: boolean) {
    setBusyId(id); setLastResult(null); setErr(null); setInfo(null);
    try {
      const res = await api.pendingReprocess(id, { useBaseResume });
      setLastResult(res);
      const ok = (res as any).ok;
      setInfo(ok
        ? `Re-ran (${useBaseResume ? 'Base' : 'Customized'}): archived to ${(res as any).archivePath}, tracker row ${(res as any).trackerRowId}`
        : `Re-run failed: ${(res as any).reason}`);
      loadPending();
    } catch (e) { setErr(String(e)); }
    finally { setBusyId(null); }
  }

  async function markSubmitted() {
    if (!submitDlg) return;
    setBusyId(submitDlg.id); setErr(null); setInfo(null);
    try {
      const res = await api.pendingMarkSubmitted(submitDlg.id, {
        confirmationId: dlgConfirmationId || undefined,
        applicationPath: dlgApplicationPath || undefined,
        note: dlgNote || undefined,
      });
      const ok = (res as any).ok;
      setInfo(ok
        ? `Marked submitted: ${submitDlg.company} → tracker row ${(res as any).trackerRowId}, status now Applied`
        : `Could not mark submitted: ${(res as any).reason}`);
      setSubmitDlg(null);
      setDlgConfirmationId(''); setDlgApplicationPath('LinkedIn Easy Apply'); setDlgNote('');
      loadPending();
    } catch (e) { setErr(String(e)); }
    finally { setBusyId(null); }
  }

  async function processAll() {
    setAllBusy(true); setLastResult(null); setErr(null); setInfo(null);
    try {
      const res = await api.pendingProcessAll();
      setLastResult(res);
      const okCount = (res as any[]).filter(r => r.ok).length;
      setInfo(`Process all complete. ${okCount} of ${(res as any[]).length} succeeded.`);
      loadPending();
    } catch (e) { setErr(String(e)); }
    finally { setAllBusy(false); }
  }

  const unprocessed = pending.filter(p => !p.processed).length;

  return (
    <>
      <h2>Search & queue</h2>
      <p className="subtitle">
        Enter target titles, open LinkedIn pre-filtered (last 2h, US, remote/hybrid), capture each posting, then click Process.
        The Java backend walks workflow.json: match → tailor → render PDF → archive → tracker row.
      </p>

      {err && <div className="error">{err}</div>}
      {info && <div style={{ background: 'var(--start)', padding: '10px 14px', borderRadius: 4, fontSize: 13, marginBottom: 12 }}>{info}</div>}

      <div className="section">
        <h3>Step 1 — Search filters</h3>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
          <div>
            <label style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Titles (one per line)</label>
            <textarea value={titlesText} onChange={e => setTitlesText(e.target.value)}
              style={{ width: '100%', minHeight: 80, fontFamily: 'inherit', fontSize: 13, padding: 8, border: '1px solid var(--line)', borderRadius: 4 }} />
          </div>
          <div>
            <label style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Location</label>
            <input value={location} onChange={e => setLocation(e.target.value)}
              style={{ width: '100%', padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13, marginBottom: 12 }} />
            <label style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Work mode</label>
            <div style={{ display: 'flex', gap: 12, marginBottom: 12, marginTop: 4 }}>
              {WORK_MODES.map(m => (
                <label key={m} style={{ fontSize: 13, display: 'flex', gap: 4, alignItems: 'center', cursor: 'pointer' }}>
                  <input type="checkbox" checked={modes.includes(m)} onChange={() => toggleMode(m)} /> {m}
                </label>
              ))}
            </div>
            <label style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
              Strict posting window {savingConfig && <span style={{ color: 'var(--accent)', fontSize: 10, marginLeft: 6 }}>· saving…</span>}
            </label>
            <select value={maxAgeMin} onChange={e => setMaxAgeMin(Number(e.target.value))}
              style={{ width: '100%', padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13 }}>
              <option value={30}>Last 30 minutes</option>
              <option value={60}>Last hour</option>
              <option value={120}>Last 2 hours (recommended)</option>
              <option value={240}>Last 4 hours</option>
              <option value={360}>Last 6 hours</option>
              <option value={720}>Last 12 hours</option>
              <option value={1440}>Last 24 hours</option>
            </select>
            <p style={{ fontSize: 11, color: 'var(--muted)', marginTop: 4 }}>
              Source of truth: <code>config.json → targeting.postingMaxAgeMinutes</code>. Selection here saves to that file. The Java backend reads it on every Process call.
            </p>
          </div>
        </div>
        <div style={{ marginTop: 14, display: 'flex', gap: 10, flexWrap: 'wrap' }}>
          <a href={linkedInUrl} target="_blank" rel="noopener noreferrer"
            style={{ padding: '10px 18px', background: 'var(--accent)', color: '#fff', textDecoration: 'none', borderRadius: 4, fontSize: 13, fontWeight: 500 }}>
            Open LinkedIn search ↗
          </a>
          <button onClick={() => navigator.clipboard.writeText(linkedInUrl)}
            style={{ padding: '10px 18px', background: 'var(--panel)', border: '1px solid var(--line)', borderRadius: 4, fontSize: 13, cursor: 'pointer' }}>
            Copy URL
          </button>
        </div>
        <details style={{ marginTop: 12 }}>
          <summary style={{ fontSize: 12, color: 'var(--muted)', cursor: 'pointer' }}>Preview URL</summary>
          <pre style={{ fontSize: 11, background: 'var(--bg)', padding: 8, borderRadius: 4, overflowX: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>{linkedInUrl}</pre>
        </details>
      </div>

      <div className="section">
        <h3>Step 2 — Capture a posting</h3>
        <p style={{ fontSize: 12, color: 'var(--muted)', marginTop: 0 }}>
          From the LinkedIn tab: click into a posting, copy company, title, URL, applicant count, JD body. Mark Reposted if LinkedIn shows it as such (it will be rejected).
        </p>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
          <input placeholder="Company *" value={capCompany} onChange={e => setCapCompany(e.target.value)} style={{ padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13 }} />
          <input placeholder="Job title *" value={capTitle} onChange={e => setCapTitle(e.target.value)} style={{ padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13 }} />
          <input placeholder="Location" value={capLocation} onChange={e => setCapLocation(e.target.value)} style={{ padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13 }} />
          <select value={capMode} onChange={e => setCapMode(e.target.value as WorkMode)} style={{ padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13 }}>
            {WORK_MODES.map(m => <option key={m} value={m}>{m}</option>)}
          </select>
          <input placeholder="Posting URL" value={capUrl} onChange={e => setCapUrl(e.target.value)} style={{ padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13 }} />
          <input placeholder="Applicant count (must be < 10)" type="number" value={capApplicants}
            onChange={e => setCapApplicants(e.target.value)} style={{ padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13 }} />
          <input
            placeholder={`Posted (e.g. "30 minutes ago" — must be ≤ ${maxAgeMin} min)`}
            value={capPostedRelative}
            onChange={e => setCapPostedRelative(e.target.value)}
            style={{ padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13, gridColumn: '1 / -1' }}
          />
        </div>
        <label style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 10, fontSize: 13, cursor: 'pointer' }}>
          <input type="checkbox" checked={capReposted} onChange={e => setCapReposted(e.target.checked)} /> This listing is marked "Reposted" on LinkedIn (will be rejected)
        </label>
        <div style={{ marginTop: 10, padding: 10, background: 'var(--accent-soft)', borderRadius: 4, fontSize: 13 }}>
          <strong style={{ fontSize: 11, textTransform: 'uppercase', letterSpacing: '0.04em', color: 'var(--accent)' }}>Resume strategy</strong>
          <div style={{ marginTop: 6, display: 'flex', gap: 16 }}>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <input type="radio" name="resume-strategy" checked={!capUseBaseResume} onChange={() => setCapUseBaseResume(false)} />
              <span><strong>Customized</strong> — AI tailors the resume to this JD (~10–60s, recommended)</span>
            </label>
            <label style={{ display: 'flex', alignItems: 'center', gap: 6, cursor: 'pointer' }}>
              <input type="radio" name="resume-strategy" checked={capUseBaseResume} onChange={() => setCapUseBaseResume(true)} />
              <span><strong>Base resume as-is</strong> — skip AI, use the original PDF (instant)</span>
            </label>
          </div>
        </div>
        <textarea placeholder="Paste the full job description here…" value={capJd} onChange={e => setCapJd(e.target.value)}
          style={{ width: '100%', minHeight: 200, marginTop: 12, padding: 10, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13, fontFamily: 'inherit', resize: 'vertical' }} />
        <button onClick={addToQueue}
          style={{ marginTop: 12, padding: '10px 22px', background: 'var(--accent)', color: '#fff', border: 'none', borderRadius: 4, fontSize: 13, cursor: 'pointer' }}>
          Add to queue
        </button>
      </div>

      <div className="section">
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 12 }}>
          <h3 style={{ margin: 0 }}>Pending queue ({unprocessed} unprocessed)</h3>
          <div style={{ display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
            <label style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
              Auto-run every
            </label>
            <select value={autoRunMin} onChange={e => setAutoRunMin(Number(e.target.value))}
              style={{ padding: '6px 10px', border: '1px solid var(--line)', borderRadius: 4, fontSize: 13 }}
              title="Periodic auto-run while this page is open. Set to Off for manual-only.">
              <option value={0}>Off</option>
              <option value={5}>5 min</option>
              <option value={15}>15 min</option>
              <option value={30}>30 min</option>
              <option value={60}>1 hour (recommended)</option>
              <option value={120}>2 hours</option>
            </select>
            {autoRunMin > 0 && (
              <span style={{ fontSize: 11, color: '#1f8a4e', fontWeight: 600 }}>
                ● Auto-run active (page must stay open)
              </span>
            )}
            <button onClick={processAll} disabled={allBusy || unprocessed === 0}
              style={{
                padding: '12px 28px',
                // Always visible green, even when disabled — only grays out while running.
                background: allBusy ? 'var(--muted)' : '#1f8a4e',
                color: '#fff', border: 'none', borderRadius: 6, fontSize: 15, fontWeight: 700,
                cursor: allBusy ? 'wait' : (unprocessed === 0 ? 'not-allowed' : 'pointer'),
                opacity: unprocessed === 0 && !allBusy ? 0.55 : 1,
                boxShadow: unprocessed > 0 ? '0 2px 12px rgba(31,138,78,0.45)' : '0 2px 6px rgba(31,138,78,0.2)',
                letterSpacing: '0.02em',
                transition: 'all 200ms',
              }}
              title={unprocessed === 0 ? 'Queue is empty — capture a posting first' : 'Run every unprocessed posting through the workflow now'}>
              {allBusy
                ? '⚙ Running…'
                : (unprocessed === 0 ? '▶ RUN (queue empty)' : `▶ RUN (${unprocessed})`)}
            </button>
          </div>
        </div>
        <p style={{ fontSize: 12, color: 'var(--muted)', marginTop: 6 }}>
          Java backend walks workflow.json node-by-node. Tailoring runs against the Anthropic API (set ANTHROPIC_API_KEY before launch); without a key it falls back to the base resume.
        </p>
        <table>
          <thead>
            <tr>
              <th>Captured</th><th>Company</th><th>Title</th><th>Location</th>
              <th>Mode</th><th># at submit</th><th>Status</th><th>Action</th>
            </tr>
          </thead>
          <tbody>
            {pending.length === 0 && (
              <tr><td colSpan={8} style={{ color: 'var(--muted)', fontStyle: 'italic' }}>Queue empty — capture a posting above.</td></tr>
            )}
            {pending.map(p => (
              <tr key={p.id}>
                <td>{p.capturedAt?.slice(0, 16).replace('T', ' ')}</td>
                <td>{p.company}</td>
                <td>{p.title}</td>
                <td>{p.location}</td>
                <td>{p.workMode}</td>
                <td className="num">{p.applicantCount ?? ''}</td>
                <td>{p.processed
                  ? (p.skipped
                      ? <span style={{ color: '#b08400' }}>skipped: {p.skipReason}</span>
                      : <span style={{ color: '#1f8a4e' }}>processed</span>)
                  : <span style={{ color: '#b08400' }}>pending</span>}
                </td>
                <td style={{ whiteSpace: 'nowrap' }}>
                  {!p.processed && (
                    <button onClick={() => processOne(p.id)} disabled={busyId === p.id || allBusy}
                      style={{ padding: '5px 12px', background: busyId === p.id ? 'var(--muted)' : 'var(--accent)', color: '#fff', border: 'none', borderRadius: 4, fontSize: 12, cursor: busyId === p.id ? 'wait' : 'pointer' }}>
                      {busyId === p.id ? 'Processing…' : 'Process'}
                    </button>
                  )}
                  {p.processed && p.archivePath && (
                    <span style={{ display: 'inline-flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
                      {p.postingUrl && (
                        <a href={p.postingUrl} target="_blank" rel="noopener noreferrer"
                           style={{ padding: '4px 8px', background: 'var(--panel)', border: '1px solid var(--accent)', color: 'var(--accent)', borderRadius: 4, fontSize: 11, textDecoration: 'none' }}
                           title="Open posting on LinkedIn / company portal">
                          Posting ↗
                        </a>
                      )}
                      <a href={`file:///Users/unknownunknown/Documents/Spark Invesco LLC/Resume Flow/Project Gamma/${p.archivePath}`}
                         target="_blank" rel="noopener noreferrer"
                         style={{ padding: '4px 8px', background: 'var(--panel)', border: '1px solid var(--accent)', color: 'var(--accent)', borderRadius: 4, fontSize: 11, textDecoration: 'none' }}
                         title={p.archivePath}>
                        Resume PDF
                      </a>
                      {!p.submitted && (
                        <>
                          {/* Re-run with current strategy */}
                          <button
                            onClick={() => reprocessOne(p.id, !!p.useBaseResume)}
                            disabled={busyId === p.id || allBusy}
                            style={{ padding: '4px 10px', background: 'var(--panel)', border: '1px solid var(--accent)', color: 'var(--accent)', borderRadius: 4, fontSize: 11, cursor: 'pointer' }}
                            title={`Re-run with ${p.useBaseResume ? 'Base' : 'Customized'} (current setting)`}>
                            ↻ Re-run ({p.useBaseResume ? 'Base' : 'AI'})
                          </button>
                          {/* Switch + re-run with the OTHER strategy */}
                          <button
                            onClick={() => reprocessOne(p.id, !p.useBaseResume)}
                            disabled={busyId === p.id || allBusy}
                            style={{ padding: '4px 10px', background: '#fff4cc', border: '1px solid #b08400', color: '#7a5a00', borderRadius: 4, fontSize: 11, cursor: 'pointer' }}
                            title={`Switch to ${p.useBaseResume ? 'AI tailoring' : 'Base resume'} and re-run`}>
                            ↺ Re-run as {p.useBaseResume ? 'AI' : 'Base'}
                          </button>
                          <button
                            onClick={() => setSubmitDlg({ id: p.id, company: p.company || '' })}
                            disabled={busyId === p.id}
                            style={{ padding: '4px 10px', background: '#7d3c98', color: '#fff', border: 'none', borderRadius: 4, fontSize: 11, cursor: 'pointer' }}
                            title="After you click Submit on the company portal, click here to flip status to Applied">
                            Mark submitted
                          </button>
                        </>
                      )}
                      {p.submitted && (
                        <span style={{ fontSize: 11, color: '#1f8a4e' }} title={p.confirmationId || ''}>
                          ✓ submitted (row {p.trackerRowId ?? ''})
                        </span>
                      )}
                    </span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        {lastResult ? (
          <details style={{ marginTop: 12 }}>
            <summary style={{ fontSize: 12, color: 'var(--muted)', cursor: 'pointer' }}>Last execution detail (audit log)</summary>
            <pre style={{ fontSize: 11, background: 'var(--bg)', padding: 8, borderRadius: 4, overflowX: 'auto', maxHeight: 300 }}>
{JSON.stringify(lastResult, null, 2)}
            </pre>
          </details>
        ) : null}
      </div>

      {submitDlg && (
        <div style={{
          position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.5)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 100,
        }} onClick={() => setSubmitDlg(null)}>
          <div onClick={e => e.stopPropagation()} style={{
            background: 'var(--panel)', borderRadius: 8, padding: 22, width: 480, maxWidth: '90vw',
          }}>
            <h3 style={{ margin: 0, fontSize: 16, color: 'var(--accent)' }}>Mark "{submitDlg.company}" as submitted</h3>
            <p style={{ fontSize: 12, color: 'var(--muted)', marginTop: 6 }}>
              Use this AFTER you've clicked Submit in the browser. The status flips from
              "Ready for Submit" → "Applied" and the tracker row updates.
            </p>
            <label style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Confirmation ID (optional)</label>
            <input value={dlgConfirmationId} onChange={e => setDlgConfirmationId(e.target.value)}
              placeholder="From the ATS confirmation page"
              style={{ width: '100%', padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13, marginBottom: 12 }} />
            <label style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Application path</label>
            <select value={dlgApplicationPath} onChange={e => setDlgApplicationPath(e.target.value)}
              style={{ width: '100%', padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13, marginBottom: 12 }}>
              <option>LinkedIn Easy Apply</option>
              <option>Workday</option>
              <option>Greenhouse</option>
              <option>Lever</option>
              <option>iCIMS</option>
              <option>Taleo</option>
              <option>SmartRecruiters</option>
              <option>Jobvite</option>
              <option>Other</option>
            </select>
            <label style={{ fontSize: 11, color: 'var(--muted)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Note (optional)</label>
            <textarea value={dlgNote} onChange={e => setDlgNote(e.target.value)}
              placeholder="Anything to remember about this submission"
              style={{ width: '100%', padding: 8, border: '1px solid var(--line)', borderRadius: 4, fontSize: 13, minHeight: 60, resize: 'vertical', fontFamily: 'inherit' }} />
            <div style={{ marginTop: 14, display: 'flex', gap: 8, justifyContent: 'flex-end' }}>
              <button onClick={() => setSubmitDlg(null)} style={{ padding: '8px 14px', border: '1px solid var(--line)', background: 'var(--panel)', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>Cancel</button>
              <button onClick={markSubmitted} disabled={busyId === submitDlg.id}
                style={{ padding: '8px 18px', background: '#7d3c98', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>
                {busyId === submitDlg.id ? 'Updating…' : 'Mark Applied'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
