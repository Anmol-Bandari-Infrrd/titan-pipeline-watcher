# Pipeline Watcher: diagrams

Flow diagrams for the service (kept here so `HOW-IT-WORKS.md` stays simple). They render on GitHub and in IntelliJ.

## The big picture

```mermaid
flowchart LR
    subgraph ENVS["9 production environments"]
        direction TB
        M1[("usprod MongoDB<br/>titanDB")]
        G1["usprod Grafana<br/>→ Prometheus, Loki"]
        M2[("swbcprod MongoDB")]
        G2["swbcprod Grafana"]
        MX[("… auprod, euprod, caprod,<br/>rkt1prod, rkt2prod, snprod, arkprod")]
        GX["… their Grafanas"]
    end

    subgraph SVC["titan-pipeline-watcher (one process)"]
        direction TB
        REG["EnvRegistry<br/>1 Mongo client + 1 Grafana client per env"]
        RUN["CheckRunner<br/>timer every 15s, 16 threads"]
        CHK["8 checks"]
        BRD["StatusBoard<br/>latest results, issues, trends"]
        ACK["AckStore"]
        API["REST API /api/*"]
        PAGE["index.html (dashboard)"]
    end

    M1 & M2 & MX -- "read-only queries,<br/>secondary preferred" --> REG
    G1 & G2 & GX -- "HTTPS, one login session<br/>per env" --> REG
    REG --> CHK
    RUN -- "runs when due" --> CHK
    CHK -- "results" --> BRD
    ACK <--> BRD
    BRD --> API
    PAGE -- "GET every 30s,<br/>POST/DELETE acks" --> API
    TV["Office TV / browser"] --> PAGE
```

## Data flow inside the service

```mermaid
flowchart TD
    A["Spring Boot starts"] --> B["Load application.yml<br/>+ application-local.yml (creds)"]
    B --> C["EnvRegistry: for each enabled env<br/>build Mongo client + Grafana client"]
    C --> D["AckStore loads pipeline-watcher-acks.json"]
    D --> E["StatusBoard loads pipeline-watcher-state.json<br/>(first-seen times, last 24h only)"]
    E --> F["CheckRunner registers every env on the board<br/>and gives every env×check a random first-run offset"]
    F --> G{{"Every 15s: tick()"}}
    G --> H{"For each env × check:<br/>applies? due? not already running?"}
    H -- no --> G
    H -- yes --> I["Submit to thread pool (16 threads)"]
    I --> J["check.run(env, now)<br/>queries Mongo and/or Grafana"]
    J -- "returns CheckResult<br/>(findings + trend values)" --> K["board.success()<br/>store result, update first/last seen,<br/>resolve vanished issues, add trend points"]
    J -- "throws" --> L["board.failure()<br/>store short clean error,<br/>keep last good result"]
    K & L --> G
    M["Browser: GET /api/status every 30s"] --> N["board.snapshot()<br/>build env views: findings + acks + states"]
    N --> O["JSON to page → render tiles, Firing now,<br/>trends, recurring errors, footer"]
    P["User clicks Acknowledge"] --> Q["POST /api/acks → AckStore → file"]
    Q --> N
    R["Every 60s"] --> S["board.saveSeen() → pipeline-watcher-state.json"]
```

## Grafana login (session) states

```mermaid
stateDiagram-v2
    [*] --> TokenMode: GRAFANA_ENV_TOKEN set
    [*] --> NoSession: no token
    TokenMode --> TokenMode: every request sends "Authorization: Bearer token"
    TokenMode --> Paused: HTTP 401

    NoSession --> Session: POST /login {user, password} → 200<br/>(cookies stored)
    NoSession --> Paused: login → 401
    Session --> Session: requests carry only the session cookie
    Session --> Renewing: less than 60s before grafana_session_expiry<br/>or a request got 401
    Renewing --> Session: POST /api/user/auth-tokens/rotate → 200
    Renewing --> NoSession: rotate failed → log in again
    Session --> Paused: still 401 after renew/login
    Paused --> NoSession: 5 minutes later
```

## The scheduler (one tick)

```mermaid
sequenceDiagram
    participant T as tick() every 15s
    participant P as Thread pool (16)
    participant C as Check
    participant B as StatusBoard
    loop every env × every check
        T->>T: appliesTo(env)? due (lastStart + interval ≤ now)? not running?
        alt all yes
            T->>T: lastStart = now, mark running
            T->>P: submit runOne(env, check)
            P->>C: run(env, now)
            alt returns
                C-->>P: CheckResult
                P->>B: success(env, check, result, now)
            else throws
                C-->>P: exception
                P->>B: failure(env, check, shortError(e), now)
            end
            P->>P: unmark running
        end
    end
```

## Stuck docs check

```mermaid
flowchart TD
    A["Query document (index status+lastModifiedDate):<br/>status = IN_PROGRESS, isActive = true, isParent ≠ true,<br/>subStatus ∉ review list, userModelId ∉ page_classification models,<br/>lastModifiedDate between now−7d and now−20min<br/>sorted oldest first, max 2,000"] --> B{"For each candidate<br/>(max 500 audit lookups)"}
    B --> C["Latest audit row for the doc:<br/>documentId = id, moduleName exists,<br/>newest createdDate"]
    C --> D["stage = that moduleName (or 'no_audit')<br/>lastActivity = later of audit createdDate and lastModifiedDate"]
    D --> E{"now − lastActivity ≥ limit for stage?<br/>(default 30, OCR 20, GENIE* 45, TABL_GENIE 60 min)"}
    E -- no --> B
    E -- yes --> F["stuck; remember its parentDocumentId"]
    F --> B
    B -- done --> G["Look up parents of stuck docs:<br/>any parent whose subStatus is in the review list?"]
    G --> H["Drop children of parents in review"]
    H --> I["Group by stage. Stage with > 5 docs →<br/>CRITICAL 'Docs stuck at #lt;stage#gt;'<br/>detail: oldest idle N min (+ '(partial scan)')<br/>doc IDs: oldest first, max 20"]
    I --> J["value 'stuck' = total stuck docs (trend line)"]
```

## Logs check

```mermaid
flowchart TD
    S{"First run for this env?"} -- yes --> SEED["Load the last 6h of ERROR lines<br/>in 30-min chunks (12 queries; a failed chunk is skipped)<br/>→ remember the fingerprint of every line<br/>not matched by the catalog"]
    S -- no --> Q
    SEED --> Q["Loki query, last 15 min, max 5,000 lines:<br/>{namespace=~log-namespaces} |= #quot;ERROR#quot;<br/>!= #lt;each IGNORE literal#gt;"]
    Q --> W["Keep only lines with the whole word ERROR"]
    W --> L{"For each line: first catalog entry<br/>whose service matches (or any)<br/>and whose regex is found"}
    L -- "match" --> K["count per catalog entry"]
    L -- "no match" --> U["fingerprint = service | message<br/>with ids, numbers, hex ids, JSON replaced"]
    K --> KF["CRITICAL/WARNING entry with count ≥ its minCount → that severity<br/>below minCount, or INFO entry → INFO<br/>title = entry title; detail = services · meaning<br/>(+ '(sampled)' if 5,000 lines were hit)"]
    U --> UF{"fingerprint not seen in last 7 days<br/>and ≥ 10 lines?"}
    UF -- yes --> NF["WARNING 'New error · #lt;service#gt;'<br/>detail = first line's message (140 chars)"]
    UF -- no --> REM
    NF --> REM["mark fingerprint seen now;<br/>forget fingerprints older than 7 days"]
    KF & REM --> V["value errorLines15m = lines read"]
```
