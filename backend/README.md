# jobflow-backend

Spring Boot REST window into Project Gamma files.

## Run

```bash
cd backend
mvn spring-boot:run
```

The server binds to `127.0.0.1:8090`. CORS allows any localhost port so Vite
can fall back to 5174 / 5175 when 5173 is busy.

## Endpoints

| Method | Path                                | Returns                                    |
|--------|-------------------------------------|--------------------------------------------|
| GET    | `/api/health`                       | Status + which Project Gamma files exist.  |
| GET    | `/api/config`                       | `config.json` parsed.                      |
| PUT    | `/api/config`                       | Overwrites `config.json` (validated JSON). |
| GET    | `/api/workflow`                     | `workflow.json` parsed.                    |
| PUT    | `/api/workflow`                     | Overwrites `workflow.json`.                |
| GET    | `/api/state`                        | `state.json` parsed.                       |
| GET    | `/api/applications`                 | All rows from tracker.xlsx → Applications. |
| GET    | `/api/applications/{rowId}`         | One row.                                   |
| GET    | `/api/reporting/snapshot`           | Pipeline bucket counts + rollups.          |
| GET    | `/api/reporting/daily?days=30`      | Daily metrics (default 30 days).           |
| GET    | `/api/reporting/weekly?weeks=12`    | Weekly metrics (week starts Monday).       |
| GET    | `/api/reporting/monthly?months=12`  | Monthly metrics.                           |

## Configuration

`application.yml` controls:

- `server.port` (default 8090)
- `server.address` (default 127.0.0.1 — localhost only)
- `jobflow.gamma.root` (default `..` — Project Gamma is the parent of `backend/`)

Override via env var: `JOBFLOW_GAMMA_ROOT=/path/to/Project Gamma`.

## What this service does NOT do

- Drive the browser. A separate browser-side script captures postings and
  POSTs them to `/api/pending`; this service is invoked per-posting via
  `POST /api/pending/{id}/process`.
- Delete any files in the working folder.
- Modify resume files or archives — those are append-only via the executor.
