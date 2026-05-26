# Runtime playbook

This is the operational manual Claude follows when Spark says "run a cycle"
or "process this JD." Every connector listed in `architecture.md` is
implemented as a procedure in this document. When `workflow.json` calls a
connector, Claude executes the corresponding procedure here.

## Submission handoff (Phase 3 — current)

The system does NOT autonomously click Submit on LinkedIn or any ATS portal —
that would risk Spark's account, fail on CAPTCHAs, and deliver mass-application
spam. Instead, the four `chrome.*` connectors are *handoff* connectors:

- `chrome.linkedin_search` and `chrome.read_posting` are read-only no-ops
  inside Java; the actual driving of LinkedIn happens interactively via Cowork's
  Claude-in-Chrome extension when Spark is in chat.
- `chrome.easy_apply` and `chrome.ats_apply` set `awaitingSubmission: true` on
  the workflow context. The walker still continues to archive + tracker, but
  the row is recorded with status `Ready for Submit` (not `Applied`). The
  prepared PDF lives in `archives/<Company>/`, ready to be attached.

To complete a submission:

1. Spark (or Claude-in-chat) opens the posting URL in the browser.
2. Attaches the tailored PDF from `archives/<Company>/`.
3. Fills any form fields (judgment-laden answers stay with the human).
4. Clicks the actual Submit button.
5. POSTs to `/api/pending/{id}/mark-submitted` with `{ confirmationId,
   applicationPath, note }`. The endpoint flips tracker status from
   `Ready for Submit` → `Applied`, sets `submittedAt`, and updates state.json.

Status flow:

```
Captured → (Process) → Ready for Submit
                              │
                              ▼  human clicks Submit, then mark-submitted
                          Applied → Acknowledged → In Review → … → terminal
```

## Autonomous Submit (opt-in, gated)

By default the system stops at "Ready for Submit". Spark can enable
autonomous submission of LinkedIn Easy Apply forms by setting flags in
`config.json → submission`:

```json
"submission": {
  "autoSubmit": false,                      // master switch
  "autoSubmitEasyApplyOnly": true,          // never auto-submit external ATS
  "dailyMaxAutoSubmissions": 3,             // hard cap per day
  "perCycleMaxSubmissions": 1,              // hard cap per scheduled run
  "excludeCompanies": []                    // never auto-submit to these
}
```

The scheduled task reads these on every run. When `autoSubmit: true`:

- For LinkedIn Easy Apply postings only (external ATS always pauses), it
  steps through the form, attaches the tailored PDF, answers
  yes/no/numeric questions from resume facts, and clicks Submit.
- It STOPS and pauses for human review when a question requires judgment
  ("salary expectations", "why this role?", essay questions). Never guesses.
- It STOPS and pauses on CAPTCHA, login prompt, or any anti-automation
  warning.
- It logs every submission to `state.json → autoSubmissionLog` with full
  provenance (timestamp, posting URL, company, title, tracker row,
  confirmation banner text).
- It enforces both daily and per-cycle caps. Submit count resets at
  local midnight.

Risks Spark accepts when enabling this:
- LinkedIn ToS prohibits automation; account suspension is a real risk.
- Submit is irreversible — bugs send real applications.
- Imperfect answers on yes/no questions get applied with the resume.

The defaults are deliberately conservative (`autoSubmit: false`,
`dailyMax: 3`, `perCycleMax: 1`, Easy-Apply-only). Spark turns it on
explicitly by editing config.json or via a future UI toggle.

## Auto-cycle (Cowork scheduled task)

A Cowork scheduled task named **`project-gamma-job-cycle`** fires every hour
between 8:00 AM and 6:00 PM, Monday through Friday, in Spark's local
timezone (cron `0 8-18 * * 1-5`, ~5-minute jitter). The task file lives at
`/Users/unknownunknown/Documents/Claude/Scheduled/project-gamma-job-cycle/SKILL.md`.

What each run does:
1. Health-check the backend on `localhost:8090`. Stop and notify if down.
2. Read filters from `config.json`.
3. Build the LinkedIn search URL, drive Chrome to it via the
   Claude-in-Chrome MCP tools.
4. For up to 5 most-recent postings: extract metadata, apply filters
   (reposted, applicants, age, title), capture passing ones via
   `POST /api/pending`, then `POST /api/pending/{id}/process` (which
   tailors via Anthropic, renders PDF, archives, appends tracker row at
   "Ready for Submit").
5. Notify Spark with a summary: reviewed / prepared / skipped counts plus
   the company-title list ready for human Submit.

Hard rules baked into the task prompt:
- Never click Submit on any application form.
- Never delete any file.
- Never enter banking/SSN/passport data.
- Stop immediately on CAPTCHA, login prompt, or anti-automation warning.
- Stop after 5 successful postings per run (avoid long-running sessions).

The user can manage the task from the **Scheduled** section in the Cowork
sidebar — pause it, edit the prompt, change the schedule, or run it
on-demand. To change the schedule from the API:
`mcp__scheduled-tasks__update_scheduled_task` with a new `cronExpression`.

## Entry points

Spark can trigger work in four ways:

1. **"Process this JD"** — Spark pastes one job description in chat.
   Claude runs the per-posting flow: match → tailor → PDF → archive →
   tracker append → state update.
2. **"Process pending"** — Claude reads `pending/*.json` (queued from the
   React `/search` page), runs the per-posting flow on every unprocessed
   item, then marks each file as `processed: true`. Files are never deleted.
3. **"Run a cycle"** — Claude polls LinkedIn (when Chrome is enabled),
   filters per `config.json`, then runs the per-posting flow for each
   matching new posting.
4. **Scheduled task** — A Cowork scheduled task invokes "Run a cycle" on
   the polling interval. Built once, repeats forever.

Today, entry points 1 and 2 are wired end-to-end. Entry points 3 and 4 are
unblocked once Claude in Chrome is enabled and a scheduled task is created.

## "Process pending" procedure

When Spark says "process pending":

1. List `pending/*.json`. Build the unprocessed set: `processed: false`.
2. If empty, report "queue is empty" and stop.
3. For each unprocessed item, run the per-posting flow with these inputs:
   - JD text from `pending[i].jd`.
   - Company / title / location / work mode / posting URL / applicant count
     from the same file.
4. After the flow completes for that item:
   - PATCH `pending/<id>.json` setting `processed: true`,
     `processedAt: <iso>`, and recording `trackerRowId` and `archivePath`
     so the audit trail joins back to the queue.
5. After draining the queue, summarize: "Processed N postings — X tailored,
   Y skipped (low match), Z waiting on approval. New tracker rows: …"
6. Approval gates inside the per-posting flow continue to apply; if Spark
   has not approved a tailored draft, that item is left as
   `processed: false` (so the next "process pending" picks it up again).

## Cycle invariants

Every cycle, regardless of entry point:

- Reads `config.json`, `workflow.json`, `state.json` before touching anything.
- Refuses to run if any of those files are malformed; reports the parse error
  and stops.
- Writes are atomic — either the full set of artifacts (PDF + folder +
  tracker row + state update) is produced, or none of them are.
- Approval gates pause the cycle and surface the pending action to Spark in
  chat. Cycle resumes only on explicit "approve" / "go" / "yes" reply.
- Files are never deleted, ever.
- Resume tailoring is truthful — no fabricated employers, dates, titles,
  schools, certifications, or skills. Reordering and rewording are fine;
  inventing is not.

## Per-posting flow (the JD-in-tracker-out path)

```
Input: JD text (and optional posting URL, applicant count, posting timestamp)
       + config.json, state.json, base resumes in resumes/.

  1. PRE-CHECK            ← Validate inputs, refuse with reason if invalid.
  2. match_resume         ← Pick best base resume for the JD.
  3. score_threshold?     ← Decision: skip if score < config.minMatchScore.
  4. tailor_resume        ← Produce a tailored resume (truthful).
  5. APPROVAL GATE        ← Pause if config.tailoring.approvalGate is true.
  6. export_pdf           ← Render tailored resume to PDF.
  7. archive_to_company   ← Place PDF in archives/<Company>/.
  8. append_tracker_row   ← Add row to tracker.xlsx, status = "Applied".
  9. update_state         ← Add entry to state.json under applications.<id>.
 10. SUMMARY              ← Tell Spark what was done; link the artifacts.

Output: archives/<Company>/<Resume>.pdf, tracker.xlsx row, state.json entry.
```

## Connector implementations

### `claude.match_resume`

**Purpose.** Pick the best base resume for a given JD and return a numeric
match score in [0, 1].

**Inputs.** JD text. `config.resumes` mapping. `config.targeting.minMatchScore`.

**Procedure.**
1. For each registered base resume, compute coverage score:
   - Count of overlapping skills, experiences, and keywords between resume
     and JD requirements.
   - Title alignment with `matchTitles` and `matchSeniority`.
   - Penalty for each `antiMatchKeyword` that appears in the JD.
   - Recency-weight more recent experience higher.
2. Pick the highest-scoring resume.
3. Score is the normalized coverage value in [0, 1].

**Output.** `{ resumeKey, file, score, rationale }`.

**Refusal.** If no resumes are registered, return error and pause.

### `claude.tailor_resume`

**Purpose.** Produce a tailored version of the chosen base resume for a
specific JD.

**Inputs.** Base resume contents (text). JD text. `config.tailoring`.

**Procedure.**
1. Identify JD's must-haves, nice-to-haves, and signal keywords.
2. Lift relevant achievements from the base resume to the top of each section.
3. Rewrite bullets to use the JD's vocabulary where it matches truthfully.
4. Drop or de-emphasize bullets the JD clearly doesn't care about.
5. Tighten to one to two pages depending on seniority (executive resumes
   may run two pages).

**Truthfulness rules — non-negotiable.**
- Employers, titles, dates, degrees, certifications, and named projects are
  copied verbatim from the base resume. They are never invented or "rounded."
- Numbers (40% reduction, $100M portfolio, 30% YoY) are copied verbatim.
- Skills only appear if they appear in the base resume. Adding "Kafka" or
  "Kubernetes" because the JD wants it, when the base resume doesn't list it,
  is forbidden.
- Pronouns and voice are kept consistent with the base resume's style.

**Output.** Markdown text (the tailored resume body) + a short rationale of
what was emphasized vs. de-emphasized.

### `claude.export_pdf`

**Purpose.** Render tailored markdown to PDF with neutral metadata.

**Inputs.** Tailored markdown. Owner contact details from `config.owner`.
Filename template from `config.archival.filenameTemplate`.

**Procedure.**
1. Render markdown to a clean .docx (using the docx skill) with:
   - Calibri or Arial 10–11pt body, 16pt header for name.
   - One-inch margins, US Letter.
   - Owner contact block at the top: name, location, email, phone, LinkedIn.
   - No watermarks, no borders, no AI-generator tag in the producer field.
2. Convert .docx → .pdf via `soffice.py` (LibreOffice).
3. Verify PDF metadata: producer field is LibreOffice (not "Claude" or any AI
   tag), creator is the owner's name.
4. Filename: `{config.archival.filenameTemplate}` with `{titleSlug}` =
   slugified job title and `{date}` = YYYYMMDD in Eastern time.

**Output.** Absolute path to the rendered PDF in the outputs folder. (The
next connector, `fs.archive_to_company`, moves it to its final home.)

### `fs.archive_to_company`

**Purpose.** Place the tailored PDF in a per-company folder under
`archives/`, preserving any prior applications.

**Inputs.** Source PDF path. Company name. Job title. Posting URL.

**Procedure.**
1. Sanitize the company name for filesystem use: spaces → underscores,
   strip characters not in `[A-Za-z0-9_-]`. Example: "Acme Corp." → "Acme_Corp".
2. Create `archives/<SanitizedCompany>/` if it doesn't exist.
3. Move the PDF into that folder. If a file with the same name already
   exists, append `_v2`, `_v3`, etc. — never overwrite.
4. Optionally write `application.json` next to the PDF with: posting URL,
   submission timestamp, applicant count, application path, and the JD text.

**Output.** Absolute path to the archived PDF inside `archives/`.

### `xlsx.append_tracker_row`

**Purpose.** Add a new application row to `tracker.xlsx → Applications`.

**Inputs.** All 15 column values per the schema in `architecture.md`.

**Procedure.**
1. Load `tracker.xlsx` with openpyxl (preserving formulas, formatting, and
   data validation).
2. Find the next empty row in the Applications sheet.
3. Write the values. Status defaults to "Applied". `lastStatusChange` =
   submission timestamp.
4. Save atomically (write to `.tmp`, rename).
5. Run `recalc.py` so the Reporting sheet picks up the new row.

**Output.** The row number written.

**Refusal.** If `tracker.xlsx` is open in Excel (lock file present), pause
and ask Spark to close it.

### `xlsx.update_status`

**Purpose.** Update the status (and optional fields) of an existing
application row.

**Inputs.** Row id (or posting id), new status, optional recruiter info,
optional notes.

**Procedure.**
1. Validate `new status` is one of the lifecycle states.
2. Open `tracker.xlsx`, find the row by row id or posting URL.
3. Update `Status`, `Last status change` (= now), and any other supplied
   fields.
4. If `new status` is terminal (Hired, Rejected, Withdrawn), also set the
   matching application in `state.json` to `watching: false`.
5. Save atomically; recalc.

**Output.** Old status, new status, row id.

### `claude.classify_email`

**Purpose.** Classify a single inbound email and route it to the right
application.

**Inputs.** Email subject + body + sender + applications list.

**Procedure.**
1. Try to match the email to a watched application by:
   a. Sender domain matches the company's ATS or career domain.
   b. Subject or body mentions the company name + a tracked job title.
2. If no match → label "unrelated"; do not update anything.
3. If matched, classify into:
   - **Acknowledgement** — auto-reply confirming submission.
   - **Rejection** — explicit "we won't be moving forward" / "decided to
     pursue other candidates".
   - **Recruiter Outreach** — recruiter wants to chat / asks for
     availability.
   - **Interview Request** — concrete interview scheduling.
   - **Offer** — offer letter or terms.
4. Compute confidence in [0, 1].
5. If confidence < `config.email.classificationConfidenceThreshold`, return
   classification "uncertain" and surface the email to Spark for manual
   call.

**Output.** `{ applicationRowId, classification, confidence,
extractedRecruiter? }`.

**Hard rule.** Email access is read-only. This connector never deletes,
archives, replies to, or forwards an email.

### `chrome.linkedin_search` / `chrome.read_posting` / `chrome.easy_apply` / `chrome.ats_apply`

**Purpose.** Browser-driven LinkedIn polling and ATS submission.

**Status.** Not implemented in this chunk. Requires:
- "Computer use" enabled in Cowork settings (currently disabled).
- A separate browser-skill chunk to write the procedures.

When implemented, all four are approval-gated. `easy_apply` and `ats_apply`
hard-fail if the approval flag is false in `workflow.json`.

### `human.handoff`

**Purpose.** Pause the workflow and surface a question to Spark.

**Procedure.**
1. Render the pause reason (from `params.reason`) plus the artifacts produced
   so far.
2. Wait for Spark's reply.
3. On "approve" / "go" / "yes" → resume from the next node.
4. On "skip" → mark the application as skipped in `state.json` and continue
   the cycle on the next posting.
5. On any other reply → ask Spark to be explicit.

**Output.** `{ decision: "approve" | "skip" | ... }`.

## State updates

After every successful per-posting flow, Claude updates `state.json`:

```jsonc
state.applications["<postingId>"] = {
  "company": "...",
  "title": "...",
  "url": "...",
  "submittedAt": "<iso>",
  "resumeUsed": "<filename>",
  "archivePath": "archives/<Company>/",
  "status": "Applied",
  "lastStatusChange": "<iso>",
  "applicantCountAtSubmit": <n|null>,
  "confirmationId": null,
  "recruiter": null,
  "watching": true
}
```

`state.lastPollAt` is updated only when a polling cycle completes.

## When Claude refuses

Claude refuses to run a cycle (and reports why) if any of the following:

1. `workflow.json`, `config.json`, or `state.json` is malformed.
2. No base resumes are registered in `config.resumes`.
3. `config.targeting.jobTitles` is still TBD and entry point 2 (polling) is
   used. Entry point 1 (paste-a-JD) is allowed without targeting filled in.
4. The action requested would create a third-party account.
5. The action requested would enter banking, SSN, or similar identity data.
6. The action requested would delete a file.
7. A submission connector (`chrome.easy_apply` / `chrome.ats_apply`) is
   reached without an explicit approval reply for that posting.
