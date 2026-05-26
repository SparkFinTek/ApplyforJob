import { useEffect, useState } from 'react';
import { api } from '../api';

export default function ConfigPage() {
  const [text, setText] = useState<string>('');
  const [err, setErr] = useState<string | null>(null);
  const [saved, setSaved] = useState<string | null>(null);

  useEffect(() => {
    api.getConfig()
      .then((c) => setText(JSON.stringify(c, null, 2)))
      .catch((e) => setErr(String(e)));
  }, []);

  async function save() {
    setErr(null); setSaved(null);
    let parsed: unknown;
    try { parsed = JSON.parse(text); }
    catch (e) { setErr('Invalid JSON: ' + (e as Error).message); return; }
    try {
      await api.putConfig(parsed);
      setSaved('Saved at ' + new Date().toLocaleTimeString());
    } catch (e) {
      setErr(String(e));
    }
  }

  return (
    <>
      <h2>Config</h2>
      <p className="subtitle">Edit config.json directly. Validated as JSON before saving. The Java backend writes atomically to the file.</p>
      {err && <div className="error">{err}</div>}
      {saved && <div style={{ background: 'var(--start)', padding: '8px 12px', borderRadius: 4, fontSize: 13 }}>{saved}</div>}
      <div className="section">
        <textarea
          value={text}
          onChange={(e) => setText(e.target.value)}
          spellCheck={false}
          style={{
            width: '100%', minHeight: 460,
            fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
            fontSize: 12, padding: 12,
            border: '1px solid var(--line)', borderRadius: 4, resize: 'vertical',
          }}
        />
        <div style={{ marginTop: 10 }}>
          <button onClick={save} style={{ padding: '8px 18px', background: 'var(--accent)', color: '#fff', border: 'none', borderRadius: 4, cursor: 'pointer', fontSize: 13 }}>
            Save config.json
          </button>
        </div>
      </div>
    </>
  );
}
