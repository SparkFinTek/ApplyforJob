# Getting Started — Project Gamma Job-Application Workflow

This is the operator's manual for using the system day-to-day. Read once,
then keep it open the first few times you run a cycle.

## What this system is (and isn't)

**Is.** An AI-assisted preparation pipeline. It searches LinkedIn for fresh
postings that match a small target list, captures them into a queue, tailors
your resume to each posting using the Anthropic API (truthfully — no invented
experience), renders the tailored PDF, archives it per company, and tracks
the application lifecycle in `tracker.xlsx`.

**Isn't.** An autonomous-submitter. It does NOT click Submit on LinkedIn or
any ATS portal. That step stays human-driven for ToS compliance, account
safety, and judgment-laden form fields. After you click Submit yourself, you
hit the "Mark submitted" button to flip status from "Ready for Submit" →
"Applied". The lifecycle continues from there.

## One-time setup

### Prerequisites

- macOS (this guide assumes Apple Silicon Mac)
- **Java 17+** — `java -version`
- **Maven** — `mvn -version`
- **Node 18+** — `node -v`
- **An Anthropic API key** from https://console.anthropic.com/settings/keys

### Configure your API key

In your shell profile (`~/.zshrc` typically), add:

```bash
export ANTHROPIC_API_KEY=sk-ant-…
```

Then `source ~/.zshrc`. Verify with `echo "key set: ${ANTHROPIC_API_KEY:+yes}"`
(this avoids printing the key itself).

If the key isn't set, the system falls back to "submit base resume as-is" mode
— no LLM tailoring, but the rest of the pipeline still works.

### Drop your base resume

Put your base resume (PDF or DOCX) into `resumes/`. The system uses the first
PDF it finds. Name it however you like; example: `Jay_Chimata.pdf`.

If you have multiple base resumes (one per role family), register them in
`config.json → resumes` with their match titles. The matcher will pick the
best fit per posting.

### First-time install of frontend deps

```bash
cd "/Users/unknownunknown/Documents/Spark Invesco LLC/Resume Flow/Project Gamma/frontend"
npm install
```

## Autonomous Submit — opt-in, with real risks

By default the system prepares applications and stops at **Ready for Submit**.
You click the actual Submit button on each posting yourself.

You can enable autonomous Submit for **LinkedIn Easy Apply only** by editing
`config.json → submission`:

```json
"submission": {
  "autoSubmit": true,                       ← change to true to enable
  "autoSubmitEasyApplyOnly": true,
  "dailyMaxAutoSubmissions": 3,             ← hard cap per day
  "perCycleMaxSubmissions": 1,              ← hard cap per cycle
  "excludeCompanies": ["DreamCorp", ...]    ← never auto-submit to these
}
```

### Read this carefully before flipping autoSubmit to true:

- **LinkedIn ToS prohibits automation.** They detect patterns. Accounts get
  suspended. The strict daily cap (default 3) is set deliberately low to
  stay below detection thresholds, but it's not a guarantee.
- **External ATS portals (Workday, Jobvite, Greenhouse, etc.) are NEVER
  auto-submitted**, regardless of this flag. They vary too much per company,
  often have CAPTCHAs, and forms include judgment-laden questions.
- **Easy Apply forms occasionally have additional questions.** The system
  answers numeric / yes-no / multi-choice questions from your resume and
  config, but **STOPS and pauses for you** on anything requiring judgment
  (salary expectations, "why this role?", essay questions, etc.). It never
  guesses.
- **Submit is irreversible.** Once clicked, that company sees your application.
- **Mistakes happen.** With autoSubmit on, mistakes mean real applications
  with imperfect answers. Audit `state.json → autoSubmissionLog` after every
  cycle.

### The kill switch

To disable instantly: set `submission.autoSubmit: false`. Takes effect on the
next scheduled-task run.

### Recommended progression

1. Run with `autoSubmit: false` for at least a week. Review the prepared PDFs
   in `archives/` — make sure the tailoring quality is what you expect.
2. Add 2–3 dream-company names to `excludeCompanies` so they never auto-submit.
3. Enable `autoSubmit: true` with `dailyMaxAutoSubmissions: 1` for a few days.
4. After verifying every auto-submitted application looked right, raise to 3.
5. Watch your inbox for confirmation emails and `autoSubmissionLog` for the
   full audit.

## Auto-cycle (the hourly Cowork scheduled task)

A Cowork scheduled task called **`project-gamma-job-cycle`** runs every hour,
Monday through Friday, 8 AM to 6 PM local time. Each run drives Chrome to
your LinkedIn search, captures matching postings, runs them through the
Java pipeline (filter → match → tailor → render → archive → tracker), and
sends you a notification with a summary.

To make the auto-cycle work:

1. **Stay logged in to LinkedIn** in your default Chrome profile. The
   Claude-in-Chrome extension uses your already-authenticated session.
2. **Keep the backend running** (`mvn spring-boot:run` on port 8090).
3. **First run: click "Run now"** in the Cowork → Scheduled section.
   This pre-approves the Chrome and HTTP tool permissions, so future
   automatic runs don't pause on permission dialogs.
4. After each run, open the React UI at `localhost:5173/search`. New
   postings will be in the queue at status "Ready for Submit". Review
   each tailored PDF in `archives/<Company>/`, click Submit on the
   company portal yourself, then click "Mark submitted" in the queue
   row.

To pause, change the schedule, or edit the task prompt: open the
**Scheduled** section in the Cowork sidebar, click the task, and use
the controls there. The task file is at
`~/Documents/Claude/Scheduled/project-gamma-job-cycle/SKILL.md`.

Critically: this auto-cycle does NOT click Submit on any portal. It only
prepares applications. The human-driven Submit step is intentional and
not going away.

## Daily flow

### Start the system (two terminals)

**Terminal 1 — backend:**

```bash
cd "/Users/unknownunknown/Documents/Spark Invesco LLC/Resume Flow/Project Gamma/backend"
mvn spring-boot:run
```

Wait for `Tomcat started on port(s): 8090 (http)` and `Started JobflowApplication`.

**Terminal 2 — frontend:**

```bash
cd "/Users/unknownunknown/Documents/Spark Invesco LLC/Resume Flow/Project Gamma/frontend"
npm run dev
```

Wait for `Local: http://localhost:5173/` and open it in your browser.

> **Important — never `Ctrl+Z` the backend.** That suspends the JVM and locks
> port 8090. To stop the backend cleanly, use `Ctrl+C` in its terminal.

### Capture a posting

1. Open the **Search & queue** page in the React app.
2. Set your **Strict posting window** (recommended: "Last 2 hours"). The
   selection saves to `config.json` automatically and the Java backend uses
   it on every Process call.
3. Confirm titles, location, and work modes in Step 1.
4. Click **Open LinkedIn search ↗** — a new tab opens with your filters
   pre-applied.
5. Click into a posting that meets your criteria:
   - Title contains "Director of Engineering" or "VP of Engineering" (or
     comma forms like "Director, Engineering")
   - Posted within your selected window
   - Fewer than 10 applicants
   - NOT marked "Reposted"
6. Copy the company, title, location, posting URL, applicant count, and the
   full JD body.
7. Paste into Step 2 — Capture a posting. Fill in "Posted" with the
   relative-time string LinkedIn shows (e.g., `30 minutes ago`,
   `1 hour ago`).
8. Click **Add to queue**. The posting appears in the Pending Queue table.

### Process a posting

Click **Process** on the row. The backend will:

1. Run all hard filters: not reposted, applicants < 10, posted within window,
   title contains a target phrase.
2. If any filter fails, the row is marked skipped with the reason. Stop.
3. Otherwise, score the JD against your base resume (PDFBox text + JD-coverage).
4. If score < `targeting.minMatchScore` (default 0.10), skip with low_match.
5. Tailor the resume using the Anthropic API (~10–30 seconds).
6. Render the tailored HTML to PDF with neutral metadata.
7. Move the PDF to `archives/<Company>/`.
8. Append a row to `tracker.xlsx → Applications` with status
   **"Ready for Submit"**.
9. Update `state.json`.

The row in the UI now shows status `processed` with a purple **Mark submitted**
button.

### Actually submit (you, in your browser)

1. Open the company's posting URL in your browser.
2. Click Apply (LinkedIn Easy Apply or external ATS).
3. Attach the tailored PDF from `archives/<Company>/`.
4. Fill the form fields (judgment-laden answers stay with you).
5. Click Submit.
6. Capture any confirmation ID shown.

### Mark it submitted in the UI

1. Click **Mark submitted** on the same row in the Pending Queue.
2. Fill in the dialog: confirmation ID (optional), application path
   (LinkedIn Easy Apply / Workday / Jobvite / etc.), note (optional).
3. Click **Mark Applied**.

The status flips: `Ready for Submit` → `Applied`. The tracker row updates,
state.json updates, and the Reports page reflects the change.

### Watch the Reports page

Go to **Reports** in the sidebar to see:

- **Pipeline (current snapshot)** — KPI tiles for total apps, in-progress,
  interviewed, yet-to-be-interviewed, top-10 hits, plus per-status counts.
- **By period** — daily (last 30d), weekly (last 12w), monthly (last 12m)
  breakdown.

The numbers come straight from the Java API which iterates the tracker rows;
they update on page reload after every action.

## Pages in the UI

| Page              | What it does                                                          |
|-------------------|-----------------------------------------------------------------------|
| Overview          | Pipeline KPIs at a glance                                             |
| Search & queue    | LinkedIn URL builder, capture form, pending queue, Mark-submitted     |
| Reports           | Daily / weekly / monthly tables                                       |
| Applications      | All rows from `tracker.xlsx` as a table                               |
| Workflow editor   | Drag-drop editor (embedded HTML); export `workflow.json`              |
| Config            | Edit `config.json` directly                                           |

## Troubleshooting

**Port 8090 already in use** when starting the backend
- An old JVM is still bound. `lsof -ti :8090 | xargs kill -9`, then re-run `mvn spring-boot:run`.

**The "Process" button hangs and the page errors out**
- Anthropic calls can take 20–60s. Check the backend terminal for activity.
- If the backend printed a stack trace, paste the first error line and ask Claude.

**404 on `/api/pending` or `/api/...`**
- Backend isn't running, or it's running on a different port.
- Verify with `curl http://localhost:8090/api/health`.

**The match score is suspiciously low (e.g. 0.0012)**
- The matcher couldn't read the PDF — make sure your resume is a real PDF
  (not a scan / image). PDFBox needs extractable text.

**The tailored PDF lands at row 1001 in `tracker.xlsx`**
- This bug was fixed; if it recurs, recompile the backend (`mvn compile` in
  the backend folder while it's running) and check that the running JVM has
  the updated `TrackerService`.

**My LinkedIn account got a warning email**
- Stop using the system. The system itself doesn't automate LinkedIn submits,
  but if you've been browsing rapidly with automation tooling, LinkedIn may
  still flag the activity. Step away for a few hours and resume manually.

## Safety rules — non-negotiable

The system, by design:

- **Never deletes any file** — pending items, archives, and tracker rows are
  append-only.
- **Never creates accounts** on third-party portals. If a portal requires a
  new account, you create it manually.
- **Never enters banking, SSN, passport, or similar identity data** in any form.
- **Never auto-submits applications** — you click the actual Submit button.
- **Resume tailoring is truthful** — no fabricated employers, dates, titles,
  certifications, or skills.
- **Email access is read-only** when the email pipeline is wired up.

## Where things live

```
Project Gamma/
├── README.md                  ← high-level overview
├── GETTING_STARTED.md         ← this file
├── architecture.md            ← runtime contract for Java + React
├── runtime.md                 ← Claude execution playbook
├── config.json                ← knobs (titles, threshold, posting window, …)
├── workflow.json              ← exported from the workflow editor
├── state.json                 ← live application state across runs
├── tracker.xlsx               ← the human-readable application log
├── JobApplicationWorkflow_*   ← requirements doc + workflow editor HTML
├── resumes/                   ← base resumes (drop yours here)
├── archives/<Company>/        ← per-company tailored PDFs
├── pending/                   ← queue of captured postings
├── backend/                   ← Spring Boot Java service (port 8090)
└── frontend/                  ← React + Vite UI (port 5173)
```

## A reminder

This system is a force multiplier for your job search, not a replacement for
your judgment. Use it to prepare quickly, then read every JD before you submit,
review every tailored resume, and keep the actual Submit click yours.
