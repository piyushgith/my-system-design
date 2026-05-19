# 06 — Event Flow: Metrics & Monitoring Platform

## Objective

Document all critical data flows through the observability pipeline — from telemetry emission to storage, alerting, and query response. Each flow highlights ordering guarantees, failure handling, and performance characteristics.

---

## Flow 1: Metrics Scrape (Pull Model)

```mermaid
sequenceDiagram
    participant S as Scrape Scheduler
    participant T as Target Service (/metrics)
    participant P as Parser/Normalizer
    participant K as Kafka (metrics-raw)
    participant W as TSDB Writer
    participant DB as TSDB (Head Block)

    S->>S: Tick at scrape_interval (e.g., 15s)
    S->>T: HTTP GET /metrics
    T-->>S: Prometheus exposition format (text/plain)
    S->>P: Parse text → []TimeSeries{labels, samples}
    P->>P: Apply relabel_configs (drop, rename, add labels)
    P->>P: Inject tenant_id label
    P->>K: Produce WriteRequest (proto-encoded)
    K-->>W: Consume (at-least-once)
    W->>W: Validate: check for out-of-order samples
    W->>DB: Append samples to head block WAL
    DB-->>W: ACK
    W-->>K: Commit offset
```

**Failure Handling:**
- Target unreachable → scraper marks series as stale (special NaN value), continues next interval
- Kafka producer full → scraper applies backpressure via remote_write queue; drops oldest if queue full
- TSDB writer crash → WAL replay on restart recovers in-flight samples
- Out-of-order sample (clock skew) → rejected with error, logged, NOT retried

---

## Flow 2: Metrics Push (Remote Write)

```mermaid
sequenceDiagram
    participant App as Application (Prometheus SDK)
    participant RW as Remote Write Endpoint
    participant IQ as Ingestion Queue (Kafka)
    participant W as TSDB Writer
    participant DB as TSDB

    App->>App: Batch samples (configurable batch_size, queue_capacity)
    App->>RW: POST /api/v1/write (snappy-compressed proto)
    RW->>RW: Authenticate (API key → resolve tenant)
    RW->>RW: Enforce cardinality quota
    RW->>RW: Inject __tenant_id__ label
    RW->>IQ: Produce to metrics-raw topic
    RW-->>App: 204 No Content (accepted, not yet stored)
    IQ-->>W: Consume
    W->>DB: Append to WAL + head block
```

**Key Design Decision:** Return 204 on Kafka produce success, NOT on TSDB write success. This means "accepted" ≠ "stored". Clients must tolerate potential data loss on downstream failure. For financial-grade observability, use synchronous write path with 2xx only on TSDB commit — but this sacrifices throughput 10x.

---

## Flow 3: Log Shipping

```mermaid
sequenceDiagram
    participant App as Application (stdout/file)
    participant Agent as Log Agent (Fluent Bit/Promtail)
    participant K as Kafka (logs-raw)
    participant E as Enrichment Processor
    participant K2 as Kafka (logs-enriched)
    participant ES as Elasticsearch

    App->>Agent: Write log line (structured JSON or plain text)
    Agent->>Agent: Parse (JSON/regex), extract fields
    Agent->>Agent: Add host, pod, namespace labels
    Agent->>Agent: Buffer in local WAL (position file)
    Agent->>K: Produce log batch (tenant-partitioned)
    K-->>E: Consume logs-raw
    E->>E: Enrich: geo-IP, PII detection/masking, severity normalization
    E->>E: Inject trace_id/span_id if present in log line
    E->>K2: Produce to logs-enriched
    K2-->>ES: Consume (Logstash/direct ES bulk API)
    ES->>ES: Index into tenant-scoped index
```

**Position File:** Agent tracks byte offset in log file. On restart, resumes from last committed position — prevents duplicate ingestion. Key reliability mechanism.

**PII Masking in Enrichment:** Credit card numbers, SSNs, email patterns detected via regex → replaced with `[REDACTED]` before ES indexing. Masking is one-way — original log NOT stored anywhere.

---

## Flow 4: Trace Ingestion (OTLP)

```mermaid
sequenceDiagram
    participant App as Application (OTel SDK)
    participant C as OTLP Collector
    participant K as Kafka (traces-raw)
    participant TP as Tail Sampling Processor
    participant TS as Trace Store (Tempo/Jaeger)

    App->>C: OTLP gRPC (spans with trace_id, span_id, parent_span_id)
    C->>C: Batch spans by trace_id
    C->>K: Produce to traces-raw (partitioned by trace_id)
    K-->>TP: Consume
    TP->>TP: Buffer spans for complete trace (30s window)
    TP->>TP: Apply sampling decision (keep errors, slow traces, 1% rest)
    TP->>TS: Store sampled spans
    TP->>TP: Drop unsampled (no storage write)
```

**Tail Sampling:** Decision made AFTER seeing all spans for a trace, not at head. Enables "always sample error traces" without knowing upfront if trace will error. Requires buffering all spans for a trace window — memory intensive.

**Partition by trace_id:** Ensures all spans for one trace go to same Kafka partition → same consumer → single sampling decision point.

---

## Flow 5: Alert Evaluation

```mermaid
sequenceDiagram
    participant AE as Alert Evaluator (cron)
    participant QC as Query Context (PromQL Engine)
    participant TSDB as TSDB
    participant AS as Alert State Machine
    participant AM as AlertManager
    participant NC as Notification Context

    loop Every evaluation_interval (default 1m)
        AE->>AE: Load active alert rules for all tenants
        AE->>QC: Evaluate PromQL expression (instant query)
        QC->>TSDB: Fetch relevant series
        TSDB-->>QC: Return samples
        QC-->>AE: Result set (matching series = condition true)
        AE->>AS: Transition state (inactive→pending→firing or firing→resolved)
        alt Pending duration exceeded
            AS->>AM: AlertFired event
            AM->>AM: Group alerts, apply inhibitions, check silences
            AM->>NC: Route to configured receivers
            NC->>NC: Deduplicate (HA replica check)
            NC->>NC: Apply group_wait delay
            NC-->>External: PagerDuty/Slack/Email
        end
    end
```

**Pending State:** Rule must be true for `for: 5m` before firing. Prevents flapping on transient spikes. State stored in AlertManager in-memory; HA cluster replicates via gossip.

**Inhibition Example:** Alert `HostDown{host=web-01}` inhibits all other alerts with `{host=web-01}` → reduces noise during host failure.

---

## Flow 6: Dashboard Query

```mermaid
sequenceDiagram
    participant U as User (Browser)
    participant GW as API Gateway
    participant QF as Query Frontend
    participant Cache as Query Cache (Redis)
    participant QS as Query Shard 1..N
    participant TSDB as TSDB Shards

    U->>GW: GET /api/dashboards/{id}/query?from=now-1h&to=now
    GW->>GW: Authenticate, resolve tenant
    GW->>QF: Forward query request
    QF->>QF: Parse PromQL, align step to cache boundary
    QF->>Cache: Lookup cached result (key = query_hash + step + time_range)
    alt Cache HIT
        Cache-->>QF: Return cached result
    else Cache MISS
        QF->>QF: Split range query into sub-queries (sharding)
        par Parallel shard execution
            QF->>QS: Sub-query [t0..t1]
            QF->>QS: Sub-query [t1..t2]
        end
        QS->>TSDB: Fetch chunks for matching series
        TSDB-->>QS: Return chunks
        QS-->>QF: Partial results
        QF->>QF: Merge + deduplicate partial results
        QF->>Cache: Store result (TTL = step size)
        QF-->>U: JSON response
    end
```

**Step Alignment:** If user queries `step=60s`, results are snapped to minute boundaries. This makes cache keys deterministic — two users querying same metric at different second offsets get same cached result.

**Thundering Herd:** 50 users open same dashboard simultaneously → all miss cache at same time → 50 identical queries to TSDB. Query Frontend deduplicates in-flight identical queries: first query executes, rest wait for same result.

---

## Flow 7: TSDB Compaction (Background)

```mermaid
sequenceDiagram
    participant H as Head Block (in-memory, 2h window)
    participant WAL as Write-Ahead Log
    participant C as Compactor
    participant L1 as Block Level 1 (2h, local SSD)
    participant L2 as Block Level 2 (48h, local SSD)
    participant S3 as Object Storage (cold)

    Note over H,WAL: Continuous write path
    H->>WAL: Every sample appended to WAL first
    H->>H: Accumulate in memory for 2h
    H->>C: Trigger: head block full
    C->>L1: Cut immutable block, persist to SSD
    C->>C: Run periodically: merge 2h blocks → 48h block
    C->>L2: Write L2 block
    C->>C: Run: merge 48h blocks → 2-week block
    C->>S3: Upload aged blocks to object storage
    C->>L1: Delete local copy after S3 upload verified
```

**Why Compaction Matters:** Many small blocks = many open file descriptors + slow range queries (must scan N files). Compaction reduces query scan cost. Tombstones (deleted series) only physically removed at compaction time.

---

## Cross-Flow: Trace-Metric-Log Correlation

```mermaid
sequenceDiagram
    participant User
    participant GW as API Gateway / Grafana
    participant QC as Query Context
    participant TS as Trace Store
    participant ES as Elasticsearch

    User->>GW: Click on metric spike at t=14:32
    GW->>QC: Fetch exemplars for metric at t=14:32
    QC-->>GW: Exemplar: {trace_id: "abc123", value: 4200ms}
    GW->>TS: GET /traces/abc123
    TS-->>GW: Full trace (service graph, span waterfall)
    GW->>ES: Search logs where trace_id="abc123" AND timestamp IN [14:31, 14:33]
    ES-->>GW: Related log lines (errors, warnings)
    GW-->>User: Unified view: metric spike → trace → logs
```

**Exemplar:** Single sample in a time-series annotated with a trace_id. Prometheus stores them in memory (not persisted by default). Critical for "jump from metric to trace" UX. Low overhead — one exemplar per scrape interval per series.

---

## Failure Handling Summary

| Flow | Failure Point | Behavior | Recovery |
|------|--------------|----------|----------|
| Scrape | Target down | Stale marker written | Auto-recovers when target returns |
| Scrape | Kafka full | Remote write queue backs up → drops oldest | Queue monitoring + alert on drop rate |
| Log shipping | Agent crash | Position file tracks offset | Resume from last committed position |
| Log shipping | ES node down | Kafka consumer pauses, lag builds | ES recovers, consumer catches up |
| Trace ingestion | Collector OOM | Spans dropped before Kafka | Head-based sampling fallback |
| Alert eval | Evaluator restarts | In-memory state lost → alerts re-enter pending | `for` duration restarts; brief delay in firing |
| Alert eval | Query timeout | Alert stays in last known state | Configurable: fire on error vs keep last state |
| Dashboard query | TSDB shard down | Partial results returned | Frontend returns partial data with error annotation |

---

## Interview Discussion Points

**Q: What happens if Prometheus misses a scrape?**
Series shows a gap. If gap > 5m (default lookback delta), range queries return no data for that window. `absent()` function can alert on this. For critical metrics, use two Prometheus instances scraping same targets — deduplication on query side.

**Q: Why use Kafka between collector and storage instead of direct write?**
Decouples ingestion rate from storage write rate. Storage compaction, ES indexing, and TSDB writes have variable latency. Kafka absorbs bursts. Without Kafka, a slow ES node directly back-pressures log agents → agents buffer on disk → eventual data loss.

**Q: How do you guarantee ordering in the log flow?**
Within a single log source (one file/pod), ordering is guaranteed by Kafka partition assignment (same source always → same partition). Across sources, logs are only approximately ordered by ingestion time, not guaranteed by event time. ES stores event timestamp; queries filter by event timestamp, not Kafka offset.

**Q: Alert flapping — how do you prevent noisy alerts?**
Three mechanisms: (1) `for: duration` in rule prevents transient triggers, (2) `repeat_interval` in routing prevents re-notification while already firing, (3) silence for known maintenance windows. Additionally, use `avg_over_time` or rate functions instead of instant values to smooth spikes.
