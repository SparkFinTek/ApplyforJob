# jobflow-frontend

React + Vite + TypeScript UI. Talks to the Spring Boot backend at
`http://localhost:8090` (proxied via Vite during dev).

## Run

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173.

## Pages

- `/` — Overview (KPIs from `/api/reporting/snapshot`).
- `/reports` — Daily / weekly / monthly metrics (the new ask).
- `/applications` — All rows from `tracker.xlsx`.
- `/editor` — placeholder; the drag-drop editor is being ported here.
- `/config` — Edit `config.json` directly with JSON validation.

## Build

```bash
npm run build
```

Static output goes to `dist/`. The Spring Boot backend can serve it as
static resources later (when we package both as a single artifact).
