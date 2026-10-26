# titan-pipeline-watcher

One central service that polls every production environment (Mongo + Grafana) and serves a
status board for the office TV. Read-only, secondary reads, bounded queries.

- **Dashboard:** `http://<host>:8530/pipeline-watcher/`: TV view; click anything for details, doc IDs, acknowledge.
  Add `?kiosk=1` on the office TV.
- **JSON:** `/pipeline-watcher/api/status`, `/pipeline-watcher/api/envs/<env>` (includes sample doc IDs)
- **How it works, in full detail (with flow diagrams):** [HOW-IT-WORKS.md](HOW-IT-WORKS.md)

## Run (pilot)

Needs `MONGO_<ENV>_CONN`, `GRAFANA_<ENV>_URL`, `GRAFANA_USERNAME`, `GRAFANA_PASSWORD` (same names as the
MCP servers), either as env vars or in `pipeline-watcher/application-local.yml`. That file is git-ignored, is
loaded automatically and never goes into the jar, so running from IntelliJ needs no setup. A missing value just
disables that side of the env.

```bash
cd pipeline-watcher
mvn clean package
java -jar target/pipeline-watcher-1.0.jar
# open http://localhost:8530/pipeline-watcher/  (office TV: google-chrome --kiosk 'http://localhost:8530/pipeline-watcher/?kiosk=1')
```

## Checks

| Check | Every | What turns the board red/amber |
|---|---|---|
| stuck-docs | 5 min | > 5 leaf docs at one stage idle past the stage limit |
| sla-risk | 5 min | in-pipeline docs past 70% of their due-date window (envs with a due-date key) |
| failure-spike | 2 min | > 5 failures / 30 min for a customer and > 3× yesterday |
| llm-failures | 2 min | > 20% failed calls for a model (≥ 20 calls / 10 min) |
| module-failures | 2 min | > 10 failed audit rows for one module / 15 min |
| queues | 1 min | messages + 0 consumers for 10 min; backlog > 50 for 30 min and growing |
| pods | 2 min | crash loop; OOMKilled + recent restart; 3+ restarts / 30 min; heap > 90%; deployment missing pods |
| logs | 5 min | known errors from `constants/ErrorCatalog.java` above their count; a new error (not seen in 7 days) 10+ times in 15 min |

All thresholds are in `application.yml` (`watcher.*`).
