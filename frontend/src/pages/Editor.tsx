import { useState } from 'react';

export default function Editor() {
  const [reloadKey, setReloadKey] = useState(0);
  return (
    <>
      <h2>Workflow editor</h2>
      <p className="subtitle">
        Drag-drop editor with connector metadata, multi-layer flows, and JSON export.
        Embedded from <code>frontend/public/JobApplicationWorkflow_Editor.html</code> — same source the Spring Boot backend also serves at <code>:8090/JobApplicationWorkflow_Editor.html</code>.
      </p>
      <div className="section" style={{ padding: 0, overflow: 'hidden' }}>
        <div style={{ display: 'flex', gap: 10, padding: '8px 14px', borderBottom: '1px solid var(--line)', alignItems: 'center' }}>
          <span style={{ fontSize: 12, color: 'var(--muted)' }}>
            Tip: click <strong>Download workflow.json</strong> in the editor toolbar; drop the file into Project Gamma to update the runtime.
          </span>
          <button
            onClick={() => setReloadKey(k => k + 1)}
            style={{ marginLeft: 'auto', padding: '5px 12px', border: '1px solid var(--line)', background: 'var(--panel)', borderRadius: 4, cursor: 'pointer', fontSize: 12 }}>
            Reload editor
          </button>
          <a
            href="/JobApplicationWorkflow_Editor.html"
            target="_blank"
            rel="noopener noreferrer"
            style={{ padding: '5px 12px', background: 'var(--accent)', color: '#fff', textDecoration: 'none', borderRadius: 4, fontSize: 12 }}>
            Open in new tab ↗
          </a>
        </div>
        <iframe
          key={reloadKey}
          src="/JobApplicationWorkflow_Editor.html"
          title="Job Application Workflow Editor"
          style={{ width: '100%', height: 'calc(100vh - 230px)', minHeight: 600, border: 'none' }}
        />
      </div>
    </>
  );
}
