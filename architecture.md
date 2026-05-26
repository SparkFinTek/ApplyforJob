# Runtime contract

This document is the contract between the **workflow editor** (where Spark
makes changes) and **Claude** (who executes the workflow). When Spark asks
Claude to run, advance, or troubleshoot the workflow, Claude follows this
contract.

## Files Claude reads on every run

| File           | Role                                                    | Owner                        |
|----------------|---------------------------------------------------------|------------------------------|
| `config.json`  | Static knobs (titles, locations, thresholds, schedule). | Spark edits.                 |
| `workflow.json`| Graph definition exported from the editor.              | Editor exports; Spark saves. |
| `state.json`   | Runtime state (active applications, lifecycle buckets). | Claude updates.              |
| `tracker.xlsx` | Human-readable application log.                         | Claude updates.              |
| `resumes/*`    | Base resumes per role family.                           | Spark edits.                 |
| `archives/*`   | Per-company folders with the exact PDF submitted.       | Claude appends.              |

## `workflow.json` shape

```jsonc
{
  "version": "1",
  "exportedAt": "2026-05-07T20:30:00Z",
  "layers": [
    {
      "id": "overview",
      "name": "Overview",
      "nodes": [
        {
          "id": "o6",
          "type": "process",          // start | process | decision | data | note
          "x": 60, "y": 460,
          "label": "Match against base resumes",
          "connector": "claude.match_resume",   // ← what tool runs at this node
          "params": {                           // ← parameters passed to the tool
            "resumeFolder": "resumes/",
            "minScore": 0.70
          },
          "approval": false                     // ← does this node require user OK
        }
      ],
      "edges": [
        { "id": "e6", "from": "o6", "to": "o7", "label": "" }
      ]
    }
  ]
}
```

## Connector vocabulary

The `connector` field on a node tells Claude what to do. Allowed values today:

| Connector                 | What it does                                                                   |
|---------------------------|--------------------------------------------------------------------------------|
| `noop`                    | Pure flow node — no action. Use on `start` and `note`.                         |
| `claude.match_resume`     | Score a JD against base resumes; pick the best one.                            |
| `claude.tailor_resume`    | Produce a tailored resume tied to the JD (truthful only).                      |
| `claude.export_pdf`       | Render the tailored resume to PDF with neutral metadata.                       |
| `fs.archive_to_company`   | Create/append `archives/<Company>/` and place the PDF inside.                  |
| `xlsx.append_tracker_row` | Append a row to `tracker.xlsx` with the application metadata.                  |
| `claude.classify_email`   | Classify one inbound email; return one of {ack, reject, recruiter, interview, offer, unrelated}. |
| `xlsx.update_status`      | Update the lifecycle status of a tracker row.                                  |
| `chrome.linkedin_search`  | Use Claude in Chrome to search LinkedIn for new postings.                      |
| `chrome.read_posting`     | Open a posting and extract JD, applicant count, posting timestamp.             |
| `chrome.easy_apply`       | Submit via LinkedIn Easy Apply (always behind an approval gate).               |
| `chrome.ats_apply`        | Submit via external ATS (always behind an approval gate).                      |
| `human.handoff`           | Pause the workflow and notify the user. Resumes when user returns.             |

New connectors are added by editing this file plus the editor's connector
dropdown. Claude must never invoke a connector that isn't in this list.

## Decision nodes

A `type: "decision"` node has multiple outgoing edges. Each outgoing edge's
`label` field is the branch condition. Claude evaluates the condition (numeric
threshold, boolean, or judgement call) and follows the matching edge. Decision
nodes do not call connectors; their job is purely routing.

If two branches could both apply, Claude picks the more specific one. If none
apply, Claude pauses and asks the user.

## Approval gates

A node with `approval: true` always pauses the workflow before executing. Claude
shows the user what the node would do (the connector + its params + any
artifacts that have been produced so far), and only proceeds after explicit
chat approval. `chrome.easy_apply` and `chrome.ats_apply` are always treated as
approval-gated regardless of the flag, because submission is irreversible.

## State machine

`state.json` shape:

```jsonc
{
  "version": "1",
  "lastPollAt": "2026-05-07T13:00:00-05:00",
  "applications": {
    "<postingId>": {
      "company": "Acme",
      "title": "Senior Data Engineer",
      "url": "https://www.linkedin.com/jobs/view/...",
      "submittedAt": "2026-05-07T13:08:11-05:00",
      "resumeUsed": "ResumeSpark_SeniorDataEngineer_20260507.pdf",
      "archivePath": "archives/Acme/",
      "status": "Applied",
      "lastStatusChange": "2026-05-07T13:08:11-05:00",
      "applicantCountAtSubmit": 4,
      "confirmationId": "ATS-9182371",
      "recruiter": null,
      "watching": true
    }
  },
  "skipped": [
    { "postingId": "...", "reason": "low_match", "score": 0.42, "atTs": "..." }
  ]
}
```

The lifecycle states (`Applied`, `Acknowledged`, `In Review`, `Recruiter
Outreach`, `Interview Scheduled`, `Offer`, `Hired`, `Rejected`, `Withdrawn`,
`Stalled`) and their transition rules are defined in §12.4 of the requirements
document. Claude must keep `tracker.xlsx` and `state.json` in sync — they are
two views of the same data, with `state.json` being the structured source of
truth and `tracker.xlsx` being the human-readable report.

## Error handling

If a connector fails, Claude:

1. Logs the failure to `state.json` under `applications.<id>.lastError`.
2. Marks the node as failed in the per-run log.
3. Continues with the remaining postings in the cycle (one failure does not
   abort the whole cycle).
4. Surfaces the failure in the cycle summary at end-of-run.

If `workflow.json` or `config.json` is malformed, Claude refuses to run and
asks Spark to fix it. Claude never auto-repairs the workflow files — they are
Spark's source of truth.

## Tech stack (locked)

```
┌──────────────────────────┐    REST     ┌──────────────────────────┐
│  React + Vite (browser)  │ ──────────▶ │  Spring Boot (localhost) │
│  http://localhost:5173   │    JSON     │  http://localhost:8090   │
└──────────────────────────┘             └────────────┬─────────────┘
                                                      │ reads / writes
                                                      ▼
                                         ┌──────────────────────────┐
                                         │  Project Gamma files     │
                                         │  config.json             │
                                         │  workflow.json           │
                                         │  state.json              │
                                         │  tracker.xlsx            │
                                         │  resumes/, archives/     │
                                         └────────────┬─────────────┘
                                                      ▲
                                                      │ reads / writes
                                                      │
                                         ┌──────────────────────────┐
                                         │  Claude (executor)       │
                                         │  walks workflow, tailors │
                                         │  resumes, classifies     │
                                         │  emails, updates state   │
                                         └──────────────────────────┘
```

**Stack:**

- **Backend:** Spring Boot, Java 17+, Maven build, runs on `localhost:8090`.
  Hosting decision deferred (local-first; can move to a home server / cloud
  later without changing the API surface).
- **Frontend:** React + Vite + TypeScript, browser dev server on
  `localhost:5173`, calls the Java REST API.
- **Executor:** Claude (this chat) continues to run the workflow. The Java
  layer is a window into the same files — it does not orchestrate execution.
- **CORS:** Spring Boot allows `http://localhost:5173` for dev only.
- **Auth:** none for v1 (single user, localhost only). Add when hosting moves
  off the local machine.

**Code lives in:**

```
Project Gamma/
├── backend/                  ← Spring Boot project (Maven)
│   ├── pom.xml
│   └── src/main/java/...
└── frontend/                 ← Vite + React project
    ├── package.json
    └── src/...
```

## REST endpoint catalog

| Method | Path                          | Purpose                                                |
|--------|-------------------------------|--------------------------------------------------------|
| GET    | `/api/health`                 | Liveness probe.                                        |
| GET    | `/api/config`                 | Read `config.json`.                                    |
| PUT    | `/api/config`                 | Write `config.json` (validated).                       |
| GET    | `/api/workflow`               | Read `workflow.json`.                                  |
| PUT    | `/api/workflow`               | Write `workflow.json` (the React editor saves here).   |
| GET    | `/api/state`                  | Read `state.json` (executor state).                    |
| GET    | `/api/applications`           | Read all rows from `tracker.xlsx → Applications`.      |
| GET    | `/api/applications/{id}`      | Read a single application by row id / posting id.      |
| POST   | `/api/applications`           | Append a new application row (Claude or React triggers).|
| PATCH  | `/api/applications/{id}`      | Update status, recruiter, or notes for one row.        |
| GET    | `/api/reporting/snapshot`     | Pipeline snapshot (current bucket counts).             |
| GET    | `/api/reporting/daily?days=30` | Daily metrics window.                                  |
| GET    | `/api/reporting/weekly?weeks=12` | Weekly metrics window.                              |
| GET    | `/api/reporting/monthly?months=12` | Monthly metrics window.                          |

The Java backend is intentionally thin — it is mostly serialization plus
validation. Heavy logic (matching, tailoring, classifying, navigating
LinkedIn) stays with Claude. When/if Java grows an executor of its own,
the endpoints above are stable and the FE doesn't change.

## Reporting

The reporting layer lives in `tracker.xlsx` on the **Reporting** sheet:

- **Pipeline (current snapshot)** — counts in each lifecycle bucket plus three
  computed rollups: in-progress (non-terminal), interviewed (≥ Interview
  Scheduled), yet-to-be-interviewed (open and pre-interview).
- **Daily (last 30 days)** — per-day submitted, of-which interviewed,
  yet-to-be-interviewed, in-progress, top-10 hits.
- **Weekly (last 12 weeks, week starts Monday)** — same columns, weekly buckets.
- **Monthly (last 12 months)** — same columns, monthly buckets.

All formulas pull from the `Applications` sheet and update automatically as
rows are added or status changes. No manual refresh needed.

When the Java backend exists, it produces the same metrics from `state.json` +
historical event log, exposing them via a REST endpoint the React UI consumes.
The xlsx remains the human-readable export.

## What Claude never does

- Delete any file in this folder.
- Create accounts on third-party portals.
- Enter banking, SSN, passport, or similar identity data into forms.
- Modify the inbox (delete, archive, reply, forward).
- Subscribe to marketing emails or accept optional consents.
- Fabricate experience, dates, employers, titles, or skills in any resume.
- Submit an application without an explicit approval gate being satisfied.
