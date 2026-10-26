# Pipeline Watcher: how it works

A guide for someone new to the project. It explains everything the service does, step by step, in plain words.
(Flow diagrams are kept separately in [DIAGRAMS.md](DIAGRAMS.md).)

---

## 1. What is this service?

Our platform processes documents in many **production environments** ("prods"): usprod, swbcprod, auprod, euprod,
caprod, rkt1prod, rkt2prod, snprod and arkprod. Each prod has its own database and its own monitoring tools.

When something breaks (documents stop moving, a service keeps crashing, an AI model starts failing) we usually find
out only when a **client complains**.

**Pipeline Watcher** fixes that. It is one small Java (Spring Boot) application that:

1. **Connects** to every prod's database (MongoDB) and monitoring tool (Grafana).
2. **Checks** each prod every few minutes for known kinds of problems (8 checks).
3. **Shows** the results on one web page, made to be shown on a TV in the office.

Red on the TV = something is wrong right now. Green = all good.

Things it does **not** do:
- It never **changes** anything in any prod. It only reads.
- It doesn't use **AI**. It's plain Java code with fixed rules.
- It doesn't send **Slack or email** messages (yet).

---

## 2. Words you need to know

| Word | Meaning |
|---|---|
| **Prod / env** | One production environment, e.g. `usprod`. "env" is short for environment. |
| **MongoDB (Mongo)** | The database where every document's status and processing history is stored. Database name: `titanDB`. |
| **Collection** | A "table" in Mongo. We read 4: `document`, `audit`, `llm_audit`, `user_model`. |
| **`document` collection** | One row per document, with its `status` (e.g. `IN_PROGRESS`, `PROCESSING_FAILED`). |
| **`audit` collection** | One row each time a pipeline step (called a **module**, e.g. `GENIE_VISION`) runs on a document. It's the document's history. |
| **`llm_audit` collection** | One row per call to an AI model (LLM), with `isFailed` true/false. |
| **Secondary** | Mongo keeps copies of the data on several servers. The main one is the *primary*; copies are *secondaries*. We read only from secondaries, so we never slow down the main one. |
| **Grafana** | The monitoring website each prod has. Behind it are **Prometheus** and **Loki**. |
| **Prometheus** | Stores numbers over time ("metrics"), e.g. how many messages are waiting in a queue, or how often a pod restarted. You ask it questions in a language called **PromQL**. |
| **Loki** | Stores the services' log lines. You search it with **LogQL**. |
| **Pod** | One running copy of a service in Kubernetes. A service can have many pods. |
| **Queue (RabbitMQ)** | Services pass documents to each other through queues. If nobody reads a queue, documents pile up there. |
| **Check** | One kind of health test, e.g. "are documents stuck?". There are 8. |
| **Finding / issue** | A problem a check found, e.g. "9 docs stuck at GENIE_VISION in auprod". |
| **Severity** | How bad an issue is: **CRITICAL** (red), **WARNING** (amber), **INFO** (just information, never coloured). |

---

## 3. The big picture in 6 steps

1. The service starts and **reads its settings** (which prods, logins, limits).
2. It **opens a connection** to each prod's Mongo and Grafana, and keeps them open.
3. Every **15 seconds** it looks at its 8 checks for each prod and runs the ones that are **due**
   (each check has its own timer: every 1, 2 or 5 minutes).
4. Each check **asks Mongo or Grafana a question** and returns what it found.
5. The results are stored **in memory** in one place (the "status board").
6. The web page **asks the service for the latest results every 30 seconds** and draws them.

---

## 4. Where the code is

Project folder: `TITAN REPOS/titan-pipeline-watcher/`. Java code: `pipeline-watcher/src/main/java/ai/infrrd/idc/watcher/`.

| Folder / file | What's in it |
|---|---|
| `PipelineWatcherApplication.java` | The `main` method that starts everything. |
| `config/WatcherProperties.java` | Reads all `watcher.*` settings from `application.yml` into a Java object. |
| `env/EnvRegistry.java` | Opens the connections to each prod (one Mongo + one Grafana per prod) at startup. |
| `env/EnvContext.java` | A small holder: one prod's name, settings, Mongo and Grafana connection. Given to every check. |
| `env/GrafanaClient.java` | Talks to one prod's Grafana: logs in, asks Prometheus and Loki questions. |
| `check/` | The 8 checks (one class each), plus shared helpers (`Check`, `CheckResult`, `DocQueries`, `StuckRule`). |
| `constants/ErrorCatalog.java` | A fixed list of 39 known error messages and what they mean (used by the logs check). |
| `board/CheckRunner.java` | The timer that decides which checks to run and runs them. |
| `board/StatusBoard.java` | Stores all results, works out each prod's colour, tracks when issues started and ended. |
| `board/AckStore.java` | Stores "acknowledgements" (someone saying "I know, I'm on it"). |
| `api/StatusController.java` | The web addresses (URLs) the page calls. |
| `src/main/resources/application.yml` | All settings and limits. No passwords. |
| `src/main/resources/static/index.html` | The dashboard page (HTML + JavaScript in one file). |
| `pipeline-watcher/application-local.yml` | **Your passwords**, on your machine only (not in git, not in the built jar). |

---

## 5. What happens when you start it

1. Spring Boot starts. Spring's automatic Mongo setup is **switched off**, because we make our own connections per prod.
2. Settings are loaded: `application.yml`, then `application-local.yml` (your logins).
3. **`EnvRegistry`** goes through the list of prods. For each prod that is switched on, it creates:
   - a **Mongo client**, if a Mongo connection string is set,
   - a **Grafana client**, if a Grafana URL is set,
   - and prints a line like `Env usprod: mongo=true, grafana=true`.
4. Saved data from the last run is loaded: acknowledgements (`pipeline-watcher-acks.json`) and the times issues were first
   seen (`pipeline-watcher-state.json`).
5. **`CheckRunner`** gives every check a **random start delay**, so all 72 checks (8 checks × 9 prods) don't hit the prods
   in the same second.
6. The website starts at **`http://localhost:8530/pipeline-watcher/`**.

For the first few minutes the prod tiles show **STARTING**, until each check has run once.

---

## 6. Settings

All settings are in `application.yml` under `watcher:`. The main ones:

| Setting | Value | What it means |
|---|---|---|
| `lookback-hours` | 168 (7 days) | Only documents changed in the last 7 days are checked. |
| `default-stuck-minutes` | 30 | A document with no progress for 30 min counts as stuck… |
| `stage-stuck-minutes` | OCR 20, GENIE / GENIE_VISION / BIBOT / BIBOT_VISION 45, TABL_GENIE 60 | …unless its current step has its own limit (AI steps are slower, so they get more time). |
| `review-sub-statuses` | Page to be reviewed, To be reviewed, To be validated, Page to be validated, Risk to be reviewed | Documents waiting for a **person** to review them. These are not stuck, so they're skipped. |
| `classifier-model-types` | page_classification | Documents under these models are in-between records, not real work, so they're skipped. |
| `sla-risk-fraction` | 0.7 | A document is "at SLA risk" once 70% of its time until the due date is used up. |
| `sample-size` | 20 | At most 20 document IDs are kept per issue. |
| `pod-namespaces` | orch, core, prep, cidc, portal, base, correction, feedback | Kubernetes areas whose pods we watch. |
| `log-namespaces` | same, without `base` | Areas whose error logs we read (`base` holds Mongo, whose logs are noise). |
| `thresholds.*` | see each check below | When something turns red/amber. |
| `envs` | one entry per prod | Name, on/off, and where to connect (see below). |

One prod's entry looks like this:

```yaml
- name: usprod
  enabled: true
  mongo-uri: ${MONGO_USPROD_CONN:}          # Mongo connection string
  grafana-url: ${GRAFANA_USPROD_URL:…}      # Grafana web address
  grafana-token: ${GRAFANA_USPROD_TOKEN:}   # optional Grafana token (better than a password)
  grafana-user: ${GRAFANA_USERNAME:}
  grafana-password: ${GRAFANA_PASSWORD:}
```

`${MONGO_USPROD_CONN:}` means "take the value called `MONGO_USPROD_CONN`, or empty if there isn't one". The value comes from
your environment variables, or from `application-local.yml`. These are the same names the Claude MCP connections use.
If a value is empty, that part of the prod is simply not checked.

The UATs (uat, swbcuat) are in the list but switched off, so red on the TV always means a **client-facing** problem.

---

## 7. How it talks to Mongo

- **One connection per prod**, opened at startup and reused. It never reconnects for each query.
- **Read-only:** it only runs searches (`find`) and summaries (`aggregate`). It never writes.
- **Reads from secondaries** (copies), never from the main server.
- **Max 5 connections** per prod.
- **Every query is stopped by Mongo after 30 seconds**, so a slow query can never hang around on a prod.
- It tells Mongo its name is `pipeline-watcher`, so database admins can see who is querying.
- If the login fails, the board shows **"Mongo login failed"** (never the username or password).

---

## 8. How it talks to Grafana

### Going through Grafana (the "datasource proxy")

Prometheus and Loki sit inside each prod's private network, so we can't reach them directly. Grafana can.
So we **ask Grafana to pass our question on** and give us the answer:

```
watcher ──► Grafana ──► Prometheus   (numbers: queues, pods)
                   └──► Loki         (log lines)
```

That way we only need **Grafana's address and a Grafana login**, which we already have.

Grafana knows Prometheus and Loki by an ID ("uid"). The watcher looks these IDs up once per prod (by asking Grafana for
its list) and then remembers them.

### Logging in once, not on every request

At first the service sent the username and password **with every request**: hundreds per minute.
Grafana treats each one as a login, and when a few failed, it **locked the user out** for a few minutes. That also
locked *you* out of Grafana.

Now it works like your browser:
1. **Log in once** per prod. Grafana gives back a **session cookie** (a temporary pass).
2. Every request after that shows only the **cookie**, not the password.
3. Grafana's sessions last about **10 minutes**. Just before that, the service **renews** the session.
   That's not a new login.
4. Only if renewing fails does it **log in again**.
5. If a login is **rejected**, it **stops calling that Grafana for 5 minutes**, so any lockout can expire.

Tested: 9 prods → 9 logins in total, and every renewal worked.

Even better: if DevOps gives us a **read-only Grafana token** per prod (`GRAFANA_<ENV>_TOKEN`), the service uses that instead.
A token is not a login, so a lockout can never happen.

### Not overloading Grafana

- At most **3 requests at a time** per prod's Grafana.
- A request is given up after **30 seconds** (60 seconds for log searches).

---

## 9. How checks are run (the scheduler)

`CheckRunner` wakes up **every 15 seconds** and goes through every prod × every check:

- Does this check apply to this prod? (E.g. Mongo checks need a Mongo connection.)
- Is it **due**? (Has its interval passed since it last started?)
- Is it **not already running**? (The same check never runs twice at the same time for the same prod.)

If all three are yes, the check is handed to a pool of **16 worker threads** and runs in the background.
When it finishes:
- **Success** → its result goes to the status board.
- **Error** → the board stores a short, clean error message, and the prod shows "Check failing".

One slow or broken prod never blocks the others.

---

## 10. The 8 checks

| # | Check | Runs every | Looks at |
|---|---|---|---|
| 1 | Stuck docs | 5 min | Mongo |
| 2 | SLA risk | 5 min | Mongo |
| 3 | Failure spike | 2 min | Mongo |
| 4 | LLM failures | 2 min | Mongo |
| 5 | Module failures | 2 min | Mongo |
| 6 | Queues | 1 min | Grafana → Prometheus |
| 7 | Pods | 2 min | Grafana → Prometheus |
| 8 | Logs | 5 min | Grafana → Loki |

All checks look at **past data** (e.g. "the last 30 minutes"), not just what happened since the service started.

### 10.1 Stuck docs: "Are documents sitting still?"

**Step 1: find candidates.** Search `document` for documents that are:
- `IN_PROGRESS` and active,
- not a "parent" document (parents wait for their children, so they're always in progress),
- not waiting for a person (see `review-sub-statuses`),
- not under a page-classification model,
- last changed between 7 days ago and 20 minutes ago (anything newer can't be stuck yet).

Oldest first, at most 2,000.

**Step 2: find each one's last step.** For each candidate (at most 500 per run), read its **newest `audit` row**.
That row's module name (e.g. `GENIE_VISION`) is the document's current **stage**.

**Step 3: decide.** "Last activity" = the later of that audit row and the document's `lastModifiedDate`. If the time since then is
longer than the stage's limit (30 min by default, 45 for AI steps…), the document is **stuck**.

**Step 4: double-check the parent.** If a stuck document's parent is waiting for a person to review, skip it.

**Step 5: report.** Group the stuck documents by stage. More than **5** at the same stage → 🔴
**"Docs stuck at GENIE_VISION"**, with the age of the oldest and up to 20 document IDs.

> **Careful:** "stuck at GENIE_VISION" means GENIE_VISION was the **last thing that happened**, not that GENIE_VISION is broken.
> Usually the step that should come *after* it never started.

If it ran out of its limits (2,000 / 500), it says "(partial scan)" and counts only what it checked.

### 10.2 SLA risk: "Will we miss a client's due date?"

- Only for prods that store a due date on documents (now: swbcprod, field `optionalParams.DocumentDueDate`).
- Same document filter as stuck docs, but only documents that have a due date.
- A document is **at risk** when 70% of the time between "created" and "due" has passed and it's still in the pipeline.
- Any at-risk document → 🟠 **"Docs at SLA risk"**, with their IDs.

### 10.3 Failure spike: "Are documents suddenly failing for a client?"

- Count documents that became `PROCESSING_FAILED` in the **last 30 minutes**, per client (`customerId`).
- Count the same for **the same 30 minutes yesterday**.
- 🔴 **"Failure spike · customer xxxxxxxx"** when a client has **more than 5** failures **and** more than **3 times** yesterday's
  number (plus 1, so a client with 0 yesterday needs more than 3).

### 10.4 LLM failures: "Is an AI model failing?"

- From `llm_audit`, the last **10 minutes**, per model: total calls and failed calls.
- 🔴 **"LLM failing · <model>"** when **more than 20%** of a model's calls failed, and it had at least **20 calls**
  (so 1 failure out of 2 calls doesn't count).

### 10.5 Module failures: "Is one pipeline step failing a lot?"

- From `audit`, the last **15 minutes**: failed rows per module, only rows that belong to a document.
  (Training jobs also write failed rows but have no document. They don't affect clients, so they're skipped.)
- 🟠 **"Module failing · <module>"** when one module failed **more than 10** times.

### 10.6 Queues: "Is any queue not being read?"

Three questions to Prometheus about RabbitMQ:

| Question | Result |
|---|---|
| Does a queue have messages waiting, **zero readers for 10 minutes**, and did it have a reader at some point in the last 7 days? | 🔴 **"No consumers · <queue>"**. The "last 7 days" part skips old, unused queues that hold one forgotten message. |
| Has a queue had **more than 50 messages for 30 minutes** and is it **still growing**? | 🟠 **"Queue not draining · <queue>"**. Catches readers that are connected but stuck. |
| How many messages are waiting in total? | Just a number for the trend line. |

### 10.7 Pods: "Are services crashing?"

Pods are grouped by **service** (the pod name without its random ending), so 50 crashing copies of one service show as one line.
Only the app namespaces are watched.

| Rule | Result |
|---|---|
| A service's pods have been in **CrashLoopBackOff** (keeps crashing on start) for 5 minutes | 🔴 "Crash loop · <service>" |
| Pods were **killed for using too much memory (OOMKilled)** and restarted in the last 30 min | 🔴 "OOMKilled · <service>" |
| **3 or more restarts** in 30 minutes, any reason | 🟠 "Restarting · <service>" |
| **Java memory (heap) above 90%** for 15 minutes: a warning before it gets killed | 🟠 "Heap high · <service>" |
| A deployment is **missing pods** for 10 minutes | 🟠 "Pods unavailable · <deployment>" |

### 10.8 Logs: "Are services logging errors?"

Every 5 minutes it reads the **last 15 minutes of ERROR log lines** from Loki (up to 5,000 lines per prod).

1. **Noise is removed in Loki itself.** Known harmless lines (e.g. `Session expired`, ~66,000 per hour on auprod) are never even downloaded.
2. Each remaining line is compared with the **error list** (`ErrorCatalog`, see section 11):
   - **Known error** → counted under its name. If it's a CRITICAL/WARNING error and it happened at least its limit times,
     it goes red/amber. Otherwise it's shown as INFO in "Recurring errors".
   - **Document IDs** are taken only from places that clearly hold a document ID (the log line's doc field, `docId:…`,
     IDs ending in `_document-N`, or an ID right after the word "document"). Any other ID, e.g. a model training ID, is ignored.
   - **Unknown error** → the line is simplified into a "fingerprint" (IDs, numbers and JSON removed), so the same error with
     different document IDs counts as one. If that fingerprint was **not seen in the last 7 days** and appears **10+ times**
     → 🟠 **"New error · <service>"**. New errors like this usually come from a fresh deploy.
3. When the service starts, it first reads the **last 6 hours** of errors (in 30-minute pieces) to learn what's "normal".
   Otherwise every old error would look new after a restart.

---

## 11. The error list (`constants/ErrorCatalog.java`)

A **constants file**: a fixed Java list of **39 known error messages**. It was built from ~20,000 real error lines from the prods,
our KB (`failure-patterns.md`, `learned-facts.md`) and the services' code.

Each entry has:
- **a pattern** to search for in the log line,
- **a service** (optional),
- **a level**,
- **a limit** (how many times per 15 min before it shows),
- **a title**,
- **a short explanation**.

The **first** entry that matches wins.

| Level | What happens on the board |
|---|---|
| **CRITICAL** (9 entries) | Red once it reaches its limit. E.g. "Classification silently defaulting (Gemini failing)", "Pagination Lambda missing". |
| **WARNING** (16) | Amber once it reaches its limit. E.g. "Azure OCR failing" (limit 15), "LLM rate limited (429) heavily" (limit 150). |
| **INFO** (8) | Never coloured. Listed under "Recurring errors" with a count. E.g. "NER code bug (token_type)". |
| **IGNORE** (6) | Filtered out before download. E.g. `Session expired`, `failed call to cuInit`. |

**Why an error moves from left to right on the board:** a WARNING with limit 15 that happened 20 times shows in "Firing now" (left).
If the next run counts only 8, it's below the limit, so it moves to "Recurring errors" (right). If it goes back above 15, it returns to the left.

The explanations sometimes mention a ticket (e.g. IDCST-18908). These were typed into the file from our KB.
The service doesn't look anything up in Jira.

---

## 12. Issues, colours and history (`StatusBoard`)

**Each issue has a name** made of `prod|check|title`, e.g. `auprod|stuck-docs|Docs stuck at TABL_GENIE_VISION`.
The board uses it to remember the issue between runs.

**For each issue it remembers:**
- **First seen**: when a check first reported it. This is saved to a file every minute, so a restart doesn't reset it.
- **Last seen**: the latest time it was reported.
- **Resolved**: when the check stops reporting it, the issue moves to the "Resolved" list (kept 24 hours).
  Recurring errors never go to that list.

**If a check fails** (e.g. can't connect), its old findings are hidden and one row says "Check failing" (or "N checks failing").

**A prod's colour:**

| Tile | When |
|---|---|
| ⚪ NOT CONFIGURED | No Mongo and no Grafana set up for this prod |
| ⚪ STARTING | No check has finished yet |
| ⚪ NO DATA | Every check for this prod is failing: we're blind there |
| 🔴 CRITICAL | At least one red issue that nobody has acknowledged |
| 🟠 WARNING | At least one amber issue (incl. "checks failing") that nobody has acknowledged |
| 🟢 HEALTHY | Nothing else. INFO items don't count. |

**Trend lines:** each check also reports a few numbers (stuck count, failures in 30 min, etc.). The board keeps 24 hours of these
**in memory**, so they start empty after a restart.

---

## 13. Acknowledging an issue

"Acknowledge" means **"I know about this, someone is working on it"**. It takes the issue out of "Firing now" (it's then listed
in the footer instead) and stops it from colouring the prod's tile.

- You must give your **name**, a **reason**, and **how long**: 1 hour, 4 hours, 24 hours or 3 days. The maximum is 7 days.
- It **comes back on its own**:
  - when the time is up,
  - if the issue gets **worse**: it turns red after being amber, or its count **doubles**. It then shows a **REOPENED** tag.
- Acknowledgements are saved in `pipeline-watcher-acks.json`, so a restart doesn't lose them.
- The page only offers Acknowledge for red and amber items.

---

## 14. Files it saves

| File | What's in it | When it's written |
|---|---|---|
| `pipeline-watcher-acks.json` | Acknowledgements (who, why, until when) | Each time someone acknowledges / un-acknowledges |
| `pipeline-watcher-state.json` | When each issue was first and last seen | Every minute |
| `pipeline-watcher/application-local.yml` | Your logins (you create it once) | Never written by the service |

They're created in the folder you start the service from. All three are ignored by git.

---

## 15. Web addresses (API)

All start with `http://localhost:8530/pipeline-watcher`.

| Address | What it gives |
|---|---|
| `/` | The dashboard page. (`/tv.html` and `/console.html` are old addresses that now redirect here.) |
| `GET /api/status` | Everything about every prod, as JSON. The page calls this every 30 seconds. Includes document IDs. |
| `GET /api/envs/usprod` | The same, for one prod. |
| `GET /api/resolved` | Issues that were fixed in the last 24 hours. |
| `POST /api/acks` | Acknowledge an issue. Body: `{"key": …, "hours": …, "reason": …, "by": …}`. |
| `DELETE /api/acks?key=…` | Remove an acknowledgement. |
| `/actuator/health` | Is the service itself alive? |

There's **no login** on these yet. Anyone who can open the page can acknowledge issues. That's fine on a laptop, not on a shared server.

---

## 16. The dashboard page

### What you see

- **Top:** one **tile per prod** with its colour and the number of stuck and failed documents.
- **Left, "Firing now":** problems to fix now. Red first. Each row shows the prod, what's wrong, **since when**, and how many.
- **Right:** context. **Trend lines** for stuck and failed documents, and **"Recurring errors"** (known, chronic errors with counts).
- **Bottom:** acknowledged issues and who acknowledged them.

### Grafana links

Most issues come with **"Open in Grafana"** links: in the side panel, and as a small "Grafana ↗" button on the issue's row (not in TV mode).
A link opens Grafana's **Explore** screen with the right query already filled in:

| Issue | The link shows |
|---|---|
| Log error (known or new) | those error lines in Loki, last 1 hour |
| Queue | the queue's waiting messages and consumers, last 6 hours |
| Pods | the service's restarts / heap / missing replicas (6h), plus its logs (1h) |
| Stuck docs | all log lines of the oldest stuck document, around the time it last moved |
| SLA risk | log lines of the first at-risk document, last 24 hours |
| Module / LLM failures | error lines mentioning that module / from llm-predictor, last 1 hour |
| Failure spike | orchestrator errors, last 1 hour |

> **Important:** Grafana only lets **Editors and Admins** use Explore. A **Viewer** who clicks a link is sent to the Grafana home page.
> Our shared account is a Viewer in every prod (checked 2026-10-26), so you need a Grafana login with Editor rights (or DevOps can
> allow Explore for Viewers) for the links to open.

### What you can click

| Click | What happens |
|---|---|
| An issue (or a recurring error, or a footer item) | A side panel opens: count, first seen, last seen, full explanation, **document IDs** (click one to copy it, or "copy all"), and the Acknowledge / Un-acknowledge form. |
| A prod's tile | The page shows only that prod. Click again to show all. |
| "Resolved / Checks" | A panel with issues fixed in the last 24 hours, and any checks that are failing. |
| "Only since service start" | Hides issues and recurring errors that already existed before the service started, and tells you how many are hidden. Tiles and trends don't change. |

After **2 minutes without any mouse or keyboard input**, the panel and the prod filter close by themselves, so the TV always goes back to the normal view.

### Special addresses

| Address ends with | Effect |
|---|---|
| `?kiosk=1` | For the TV: no buttons, no mouse cursor, fits the screen (max 12 problems and 6 recurring errors). |
| `?issue=<name>` | Opens that issue's panel straight away. |
| `?env=usprod` | Starts showing only usprod. |
| `?view=history` | Opens the Resolved / Checks panel. |
| `?since=start` | Starts with "Only since service start" on. |

Without `?kiosk=1`, the page scrolls normally and shows everything.

If the page can't reach the service for **3 minutes**, a red banner says **"Data is stale"**, so a frozen screen never looks healthy.

---

## 17. When things go wrong

| Problem | What you see on the board |
|---|---|
| Mongo password wrong | "Mongo login failed" |
| Mongo can't be reached | "Mongo unreachable (timeout)" or "Mongo connection failed" |
| Mongo query too slow | Mongo stops it after 30s; the check shows an error |
| Grafana returns an error page | e.g. "Grafana … returned HTTP 502" (usually a short hiccup on their side) |
| Grafana rejects the login | "Grafana login rejected (401); pausing calls …" (waits 5 min before trying again) |
| Grafana too slow | "request timed out" |
| Everything for a prod fails | Tile says **NO DATA**, and a red "No data from env" row appears |

Error messages are cleaned before they're shown: usernames and passwords are never displayed.

**Limits that protect the prods:** stuck docs checks at most 2,000 documents and 500 histories per run; logs reads at most
5,000 lines per run; at most 20 document IDs per issue; history and "resolved" are kept for 24 hours.

**Run only one copy** against the prods at a time. Two copies double the load (this caused timeouts once).

---

## 18. Running it and testing it

```bash
cd "TITAN REPOS/titan-pipeline-watcher/pipeline-watcher"
mvn clean package                         # build + run the tests
java -jar target/pipeline-watcher-1.0.jar # start it
```

Or just run `PipelineWatcherApplication` in IntelliJ. Your logins are read from `application-local.yml` automatically.

- Open **http://localhost:8530/pipeline-watcher/**
- On the office TV: `google-chrome --kiosk 'http://localhost:8530/pipeline-watcher/?kiosk=1'`
- Different port: `PORT=8531 java -jar …`

**Useful log lines:**
- `Env usprod: mongo=true, grafana=true`: connections set up
- `Grafana session opened` / `Grafana session renew ok`: Grafana login working
- `usprod/queues failed after … ms: …`: a check failed (with the reason)

**Tests (19, all passing):**
- `StuckRuleTest`: the stuck and SLA rules.
- `LogErrorCheckTest`: reading log lines, fingerprints, the error list, which IDs count as document IDs.
- `AckStoreTest`: acknowledgements expire, reopen when worse, and survive a restart.

The rest (queries, page, logins) was tested by running it against the real prods.

---

## 19. Why the rules are like this

All of these come from looking at real prod data in October 2026:

- **Most `IN_PROGRESS` documents are fine** (usprod had 1.37 million). Many are waiting for review, are parents, or are
  page-classification records. That's why those are skipped.
- **7 days, not less:** with 3 days, documents stuck for more than 3 days disappeared from the board.
- **Training errors are skipped:** they failed ~180 times per 15 minutes on auprod and euprod, without affecting clients.
- **Old unused queues are skipped:** otherwise caprod, euprod and rkt2prod would be red forever.
- **No "memory over 90%" rule:** about 20 pods sit above 90% normally, and almost none of them ever got killed.
- **Raw error counts don't work:** 95% of auprod's ~69,000 error lines per hour were one harmless message. Hence the error list and noise filter.
- **Grafana login once:** sending the password with every request locked the user out.

---

## 20. Limits and what's next

**Known limits:**
- No login on the page yet.
- Trend lines and the "seen" error fingerprints are lost on restart.
- "First seen" means first seen *by this service*, not when the problem really started.
- Failure-spike and LLM issues don't have document IDs. Others have at most 20.
- Failure spikes show client IDs, not client names.
- All limits are first guesses and need tuning.
- It uses personal logins. It should get its own read-only Mongo users and Grafana tokens.
- Running on a laptop: if the laptop sleeps, the board goes stale.
- No Slack messages yet.

**Status (2026-10-22):** runs against all 9 prods. rkt1prod's Mongo login is rejected, so its Mongo checks fail; its Grafana checks work.

**Problems it has already found:**
- auprod: documents stuck at `TABL_GENIE_VISION` for ~5 days
- snprod: queue `ml-crop-classification` with no reader for 2+ days
- swbcprod: pagination Lambda missing + AWS AccessDenied
- euprod / auprod: scheduler can't reach `page-classification`
- rkt2prod: `groupId is null` errors
- auprod: Gemini classification silently using a default
- usprod: NER `token_type` code bug (chronic)
- swbcprod (past): a PDF splitter crashing ~100 times/day for 5 days

**Next steps:**
1. Put it on the office TV and compare it with real tickets every day; tune the limits.
2. Get read-only Mongo users and Grafana tokens; fix rkt1prod's login.
3. Move it to Kubernetes and add a login to the page.
4. Later: Slack messages, more checks, client names, more document IDs, and Claude writing a first analysis for red issues.

**Still to decide:** the success target, the full list of "waiting for review" statuses, whether UATs go on the TV, and who owns this after Q4.
