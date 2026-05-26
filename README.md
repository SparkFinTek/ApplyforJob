# ApplyforJob — stop spraying resumes into the void

**A local, open job-application pipeline for senior engineering roles.** It watches LinkedIn, filters out the listings you should never apply to (reposted, already saturated, stale), tailors a resume to each surviving posting using your own AI provider, and tracks every application end-to-end in a spreadsheet you actually own.

Built because applying to jobs is broken:

- **Repost manipulation** — recruiters re-post 3-month-old listings as "1 hour ago" to game the feed.
- **Saturation traps** — the moment a posting hits 100+ applicants, your resume is statistically invisible.
- **Tailoring tedium** — every serious application needs a tweaked resume, and doing 20 of those by hand burns a Saturday.
- **No memory** — three weeks in, you can't remember which companies you applied to or what resume version they got.

ApplyforJob handles all four. Strict filters block reposts and over-applied listings. An AI tailor produces a per-JD resume you approve before submit. Every application — what you sent, when, the response — lives in an Excel tracker on your machine, not a SaaS you'll forget to cancel.

> **Status:** alpha. Works end-to-end for Director/VP of Engineering roles. Built for one user (the author) and being opened up. Bug reports welcome.

---

## Table of contents

- [What it actually does](#what-it-actually-does)
- [The filter rules — opinionated by design](#the-filter-rules--opinionated-by-design)
- [Architecture in 30 seconds](#architecture-in-30-seconds)
- [Quick install](#quick-install)
- [Configuration](#configuration)
- [How to run a cycle](#how-to-run-a-cycle)
- [Frequently asked questions](#frequently-asked-questions)
- [Roadmap](#roadmap)
- [License](#license)

---

## What it actually does

1. **You open a LinkedIn search** pre-filtered for your target title, location, work mode, and posting age.
2. **A browser-side script walks each visible card**, extracts metadata (company, title, applicant count, posted-time, reposted flag), and POSTs candidates to the local backend.
3. **The Java backend applies strict filters**, rejects fails (with reason logged), and for survivors:
   - Computes a JD-coverage match score against your base resume.
   - Sends the JD + your base resume text to your AI provider with a locked HTML/CSS resume template and a *truthfulness-only* prompt — no fabrication.
   - Renders the returned HTML to PDF via openhtmltopdf.
   - Archives the PDF under `archives/<Company>/`.
   - Appends a row to `tracker.xlsx`.
   - Writes the application to `state.json` as **Ready for Submit**.
4. **You review and submit**. For external ATS portals the system stops at "Ready for Submit" so you stay in control. For LinkedIn Easy Apply, an optional auto-submit mode (off by default) walks the form with safety caps.
5. **You hit "Mark submitted"** with a confirmation ID after sending; status moves to **Applied**, the lifecycle clock starts.

Everything stays on your machine. No external SaaS. No data leaves except the API call that tailors the resume.

---

## The filter rules — opinionated by design

A posting is processed only if **all** of these hold:

| Rule | Default | Why |
|---|---|---|
| Title matches a phrase whitelist | "Director of Engineering", "VP of Engineering", etc. | You configured the search; the filter just double-checks the listing isn't drifting. |
| Posted within window | 720 minutes (12h) | After ~12h, the top-10 applicant slot is gone. Configurable. |
| Applicant count < 10 | 10 | Beyond the first 10, response rate collapses. The number is a knob in `config.json`. |
| Not marked Reposted | `reposted: false` | LinkedIn re-floats stale listings; the reposted flag is the tell. |
| Work mode in allowlist | Remote, Hybrid | Set to your preference in `config.json`. |
| Location matches | "United States" | Whitelist of country/region keywords. |

Tailoring is gated on a minimum **match score** (default 0.10) so an off-domain Director-of-Marine-Engineering posting won't burn an API call.

Submission policy has its own gates:

- `submission.autoSubmit` — off by default
- `submission.dailyMaxAutoSubmissions` — default 3
- `submission.perCycleMaxSubmissions` — default 1
- `submission.autoSubmitEasyApplyOnly` — true (never auto-submit through external ATS)

---

## Architecture in 30 seconds

```
+-----------------+        +------------------+        +----------------+
|  Browser (your  | POST  |  Spring Boot     |  call  |  AI provider   |
|  LinkedIn tab + | ----> |  REST on :8090   | -----> |  (your key)    |
|  capture script)|        |  workflow walker |        +----------------+
+-----------------+        +------------------+
        ^                          |   |
        |                          v   v
        |               +------------------+   +------------------+
        |               |  config.json     |   |  state.json      |
        +-------------- |  workflow.json   |   |  tracker.xlsx    |
       React UI :5173   +------------------+   |  archives/<co>/  |
                                                +------------------+
```

The **backend** is a single Spring Boot app (Java 17). Workflow steps are dispatched by name from `workflow.json` — you can edit the graph from the UI without recompiling.

The **frontend** is a Vite + React + TypeScript SPA with five pages:

- `/search` — the capture queue and Run / Re-run buttons
- `/applications` — every row of `tracker.xlsx`
- `/reports` — daily / weekly / monthly + pipeline snapshot
- `/workflow` — visual editor for `workflow.json`
- `/config` — view current filters and submission policy

Everything is localhost-only. The backend binds `127.0.0.1` and is not exposed to your network.

---

## Quick install

### Prerequisites

| Tool | Version | Why |
|---|---|---|
| Java | 17+ | Backend runtime. Check: `java -version` |
| Maven | 3.8+ | Build tool. Bundled wrapper `./mvnw` works too. |
| Node | 20+ | Frontend tooling. Check: `node -v` |
| Git | any | To clone. |
| An Anthropic API key | — | The resume tailoring step calls the Anthropic Messages API. Get one at https://console.anthropic.com/ |
| Chrome | recent | For the capture script (browser script runs in your normal browser). |

### One-time setup

```bash
# 1. Clone
git clone git@github.com:SparkFinTek/ApplyforJob.git
cd ApplyforJob

# 2. Drop your base resume PDF into resumes/
cp ~/Documents/MyResume.pdf resumes/MyResume.pdf
# Then edit config.json -> resumes.baseResume to match the filename

# 3. Set your AI provider key (Anthropic in current build)
export ANTHROPIC_API_KEY="sk-ant-..."
# Recommended: put this in ~/.zshrc or ~/.bashrc so it persists.

# 4. Backend — first start (downloads deps, builds, then runs)
cd backend
mvn spring-boot:run
# Wait for: "Started JobflowApplication ... port(s): 8090"

# 5. Frontend — in a SECOND terminal
cd frontend
npm install
npm run dev
# Vite prints "Local: http://localhost:5173/"
```

### Open the app

http://localhost:5173/search

---

## Configuration

All settings live in `config.json` at the project root. The keys you will actually touch:

```jsonc
{
  "targeting": {
    "jobTitles": ["Director of Engineering", "VP of Engineering"],
    "locations": ["United States"],
    "workModes": ["Remote", "Hybrid"],
    "postingMaxAgeMinutes": 720,
    "minMatchScore": 0.10
  },
  "submission": {
    "autoSubmit": false,
    "autoSubmitEasyApplyOnly": true,
    "dailyMaxAutoSubmissions": 3,
    "perCycleMaxSubmissions": 1
  },
  "schedule": {
    "autoRunMinutes": 15
  },
  "resumes": {
    "baseResume": "Your_Name.pdf"
  }
}
```

The Search page has a UI for the most-edited fields (posting window, work modes, autoRun frequency) — selections persist back to `config.json`.

---

## How to run a cycle

### Manual mode (recommended while you learn the tool)

1. From `/search`, click **Open LinkedIn search ↗**. A new tab opens pre-filtered.
2. Click into each posting on LinkedIn. Copy the metadata into the capture form (Company, Title, URL, Applicant count, JD body, posted-time, reposted? checkbox).
3. Pick **Customized** (AI tailor) or **Base** (submit as-is) for resume strategy.
4. Click **Queue**. The posting goes into `pending/`.
5. Click **Run**. The backend walks each queued posting through filter → match → tailor → render → archive → tracker.
6. Open the row's archived PDF, apply on the company's site, click **Mark submitted** and paste the confirmation ID.

### Browser-script mode

There's a capture script (under `scripts/`) that walks visible LinkedIn cards, scrapes metadata, and POSTs to the backend automatically. See `runtime.md` for usage.

### Scheduled mode

`autoRunMinutes` in `config.json` polls the queue every N minutes and processes anything pending. Combined with the macOS/Cowork scheduled-task feature, you can have the cycle fire every hour during business hours.

---

## Frequently asked questions

**Does my resume / personal data ever leave my machine?**
Only the JD text + your base resume text go to the AI provider for tailoring. Nothing else is uploaded. No analytics. No telemetry. Your `state.json`, tracker, and archives are local files — they're gitignored too, so you can fork this without leaking your job hunt.

**What does it cost to run?**
One Anthropic API call per tailored resume, typically $0.02–$0.08 per posting depending on JD length. Filtered-out postings cost nothing.

**Why are reposts blocked?**
A "Reposted 2 hours ago" listing on LinkedIn is almost always 30+ days old and saturated. Your application lands on top of a stack the recruiter has already triaged.

**Can I use a different AI provider?**
Currently wired to the Anthropic Messages API. The `AnthropicClient` is the only file that knows the wire format — swapping providers is one class.

**Does it auto-submit?**
Only for LinkedIn Easy Apply, only when `submission.autoSubmit=true`, only up to daily/per-cycle caps you set. External ATS (Workday, Greenhouse, Lever, Jobvite…) always stop at "Ready for Submit" — you submit manually because their forms vary too much to safely automate and creating accounts on your behalf is out of scope.

**Will this get my LinkedIn account banned?**
The browser script runs in your real Chrome with your normal session. It reads visible cards; it does not click apply or submit anything on LinkedIn (except via the optional Easy Apply mode, which behaves like a human filling a short form). It does not scrape past pagination or background-tab harvest. Still — use your judgment.

**Why "ApplyforJob" / "Project Gamma"?**
Internal codename. The repo is `ApplyforJob`; the on-disk folder is `Project Gamma`.

---

## Roadmap

- [ ] First-class capture browser extension (currently a paste-in script)
- [ ] Multi-resume support (different base resumes per role family)
- [ ] Email parser — auto-detect "received your application", interview invites, rejections, and bump status in the tracker
- [ ] Provider abstraction (OpenAI, local Ollama)
- [ ] Configurable cover letter generation
- [ ] Salary range extraction + filter

---

## Contributing

Pull requests welcome, especially:

- New ATS handlers (Workday, Greenhouse, Lever, Ashby, etc.)
- Capture scripts for other job boards (Indeed, Otta, Wellfound)
- Tracker analytics — funnel conversion rates by source, by title

Open an issue first if you're planning a large change.

---

## License

Copyright © 2026 Spark Invesco LLC. All rights reserved.

The source is published for reference, education, and personal use. You may
clone, build, and run it for your own job search. You may not redistribute,
sell, sublicense, or offer it as a hosted service without prior written
permission. A formal OSS license may be added later.
