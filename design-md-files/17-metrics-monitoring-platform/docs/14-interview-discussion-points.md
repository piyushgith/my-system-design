# 14 — Interview Discussion Points: Metrics & Monitoring Platform

## Objective

Comprehensive interview preparation from junior to principal engineer level. Covers fundamentals, production tradeoffs, scaling evolution, and adversarial "what breaks first?" analysis.

---

## Junior / Mid-Level Questions

### Q: What's the difference between metrics, logs, and traces?

| Signal | What It Captures | Structure | Retention | Best For |
|---|---|---|---|---|
| Metrics | Numeric measurements over time | Structured (labels + float64) | Long (months/years) | Dashboards, alerts, capacity planning |
| Logs | Discrete events with context | Semi-structured text/JSON | Medium (weeks) | Debugging, audit, root cause |
| Traces | Request flow across services | Structured (spans, parent-child) | Short (days) | Latency profiling, dependency analysis |

All three are complementary. The "observability three pillars" model. Real insight requires correlation (exemplars link metrics to traces; trace_id in logs links logs to traces).

---

### Q: Push vs Pull for metrics collection — explain the tradeoff.

**Pull (Prometheus model):**
- Scraper decides when to collect — collector is in control
- Easy to detect target is down (`up == 0`)
- Service discovery: Prometheus discovers targets dynamically (Kubernetes SD)
- Problem: ephemeral targets (FaaS, batch jobs) may disappear before being scraped
- Problem: pull requires network access from Prometheus to target — doesn't work across firewalls

**Push (remote_write / StatsD / OpenTelemetry):**
- Application decides when to push — application controls timing and batching
- Works across firewalls (client initiates connection)
- Works for ephemeral workloads (push before exiting)
- Problem: need to detect stale/silent sources (if app stops pushing, no "down" signal)
- Problem: application bears responsibility for buffering, retry, backpressure

**Answer for interviews:** Prometheus chose pull because it makes the collector authoritative — you can always see what Prometheus is actually seeing. But production systems often use both: pull for always-on services, push for batch jobs and cross-network scenarios.

---

### Q: What is a time-series? What is cardinality?

A time-series is a unique `{metric_name + label_set}` identifier paired with a sequence of `(timestamp, float64)` samples. Example:
```
http_requests_total{method="GET", status="200", service="api"} → [(t1, 100), (t2, 105), ...]
```

Cardinality is the total count of unique time-series. Adding a label with 100 possible values multiplies cardinality by 100.

High cardinality → high RAM usage in Prometheus → OOM → incident.

---

### Q: What is a log index in Elasticsearch?

An ES index is like a database table but denormalized. An index contains documents (log records). Each index has primary shards (data partitioned for parallelism) and replica shards (copies for fault tolerance).

For logs: create one index per day per tenant. Query time-ranges by targeting specific date indices rather than scanning everything.

---

## Senior Engineer Questions

### Q: Explain the Prometheus TSDB block architecture.

Prometheus stores data in blocks:
- **Head Block**: In-memory, mutable, holds last 2h of data. Backed by WAL for durability.
- **Persisted Blocks**: Immutable, on-disk. Each block covers a non-overlapping time range.
- **WAL**: Write-ahead log. Every sample appended here before head block. On crash, WAL is replayed to reconstruct head block.
- **Compaction**: Merges small blocks into larger blocks. Reduces file count, improves query performance, applies tombstones.
- **Index**: Inverted index mapping `(label name → label value → series IDs)`. Enables fast label-based filtering without scanning all chunks.

Query process:
1. Evaluate label matchers against index → get series ID list
2. For each series, find chunks covering query time range
3. Decode chunk samples, apply PromQL function

---

### Q: What causes Prometheus to run out of memory? How do you fix it?

**Root cause:** Number of unique time-series (cardinality) × memory per series.

Each series requires ~3KB RAM for the index entry, labels, and active chunk. 5M series = ~15GB RAM.

**Detection:** `prometheus_tsdb_head_series` metric. Alert when > 80% of expected max.

**Fixes:**
1. Audit high-cardinality labels: find labels with unbounded values (user_id, trace_id, request_id)
2. Drop labels via `metric_relabel_configs` before storage
3. Use recording rules to pre-aggregate → query aggregated series, not raw
4. Replace high-cardinality labels with exemplars (link to trace, not store in label)
5. Migrate to Cortex/Mimir which handles multi-instance horizontal scaling

---

### Q: Why is Elasticsearch NOT good for metrics storage?

| Criteria | Prometheus TSDB | Elasticsearch |
|---|---|---|
| Compression | 1.3 bytes/sample (Gorilla encoding) | ~100 bytes/sample (JSON/binary doc) |
| Query language | PromQL (time-aware, rate/delta built-in) | Lucene (text-search oriented, not time-math) |
| Aggregation speed | Native vectorized chunk scan | General-purpose aggregation pipeline |
| Retention | Configurable, compaction-based | ILM (index lifecycle, coarser granularity) |
| Storage cost | 100M samples = ~130MB | 100M samples = ~10GB |

ES is optimized for text search and document retrieval. TSDB is optimized for float64 samples with time-series compression (delta-of-delta encoding, XOR encoding from Facebook's Gorilla paper). Using ES for metrics wastes 75-100x storage and is slower for time-range queries.

---

### Q: Explain Prometheus remote_write backpressure.

Prometheus maintains an in-memory queue per remote_write target (default capacity: 10,000 sample batches). When the remote endpoint is slow:
1. Queue fills
2. Prometheus applies backpressure: stops accepting new remote_write batches until queue drains
3. If queue still fills: oldest batches are dropped (recency-first semantics — prefer losing old data over new)

Key config: `queue_config.max_samples_per_send` (batch size), `queue_config.capacity` (queue depth), `queue_config.max_backoff` (retry ceiling).

Alert on `prometheus_remote_storage_samples_dropped_total > 0` — any dropping means data loss.

---

### Q: AlertManager HA — how does deduplication work across replicas?

Two AlertManager instances both receive the same alert from two Prometheus replicas. Without dedup, two notifications are sent.

AlertManager cluster uses **gossip protocol** (memberlist library):
- Each instance shares its alert state with peers via gossip
- Deduplication key = hash of alert labels (e.g., `alertname="HighErrorRate", service="api"`)
- When instance A receives `AlertFired` for key `X`, it gossips to peers
- Instance B receives same alert, checks gossip state: key `X` already handled → skips notification
- `group_wait` timer (default 30s) ensures dedup window is wide enough

This is **eventually consistent dedup** — brief window where both might notify if gossip propagation lags. Acceptable tradeoff: slightly noisy vs strong consistency requiring distributed lock.

---

## Staff Engineer Questions

### Q: Thanos vs Cortex vs Mimir — when to use each?

| System | Architecture | Best For | Operational Cost |
|---|---|---|---|
| Thanos | Sidecar pattern on top of Prometheus | Adding long-term storage to existing Prometheus fleet | Low adoption cost, moderate ops |
| Cortex | Microservices (distributor, ingester, querier, compactor) | Multi-tenant SaaS, per-tenant isolation | High ops complexity |
| Mimir | Cortex v2, simplified, object-storage native | New multi-tenant builds; Cortex replacement | Medium-high ops |

**Decision tree:**
- Single tenant, existing Prometheus: Thanos
- Multi-tenant SaaS product: Mimir
- Already on Cortex: migrate to Mimir (Grafana's recommendation)
- Very small scale: vanilla Prometheus + Grafana, no federation

---

### Q: How do you achieve tenant isolation in a multi-tenant metrics platform?

**At ingestion:**
- Validate API key → resolve tenant_id
- Inject `__tenant_id__` label into every series at ingestion gateway
- Enforce per-tenant cardinality quota (reject if exceeded)

**At storage:**
- Cortex/Mimir: separate ingester ring slots per tenant; block files prefixed with tenant_id in S3
- Per-tenant retention policies (enterprise customer gets 2 years; free tier gets 15 days)

**At query:**
- Query Frontend enforces tenant context: all PromQL queries implicitly scoped to `{__tenant_id__="abc"}`
- Label matchers cannot escape tenant scope — enforced at query parsing layer
- Prevent cross-tenant label leakage: Series IDs from different tenants never merged

**At alerting:**
- Alert rules scoped per tenant; evaluator runs rules in tenant context
- AlertManager routes notifications to tenant-specific receivers

---

### Q: How do you handle 10 million active time-series in a single Prometheus?

**Short answer: you don't.** Single Prometheus can't handle 10M series reliably.

**Architecture evolution:**
1. 0-2M series: single Prometheus + SSD + 16GB RAM
2. 2-5M series: vertical scale (32GB RAM, fast NVMe)
3. 5-10M series: shard by scrape job across 3-5 Prometheus instances; Thanos Querier federates
4. 10M+ series: Cortex/Mimir with hash-ring-based sharding; series distributed across N ingesters

For sharding: use `hashmod` relabeling to divide targets across Prometheus instances, or use Prometheus agent mode (no local storage) feeding into Cortex/Mimir.

---

### Q: Explain PromQL federation and when it breaks.

Federation query: Prometheus A scrapes select metrics from Prometheus B via `/federate` endpoint. Used for aggregating metrics from many instances into a global view.

**When federation breaks:**
- High-cardinality metrics: `/federate` transfers all matching series → saturates network
- Slow federate source: global Prometheus' scrape times out → gap
- Label conflicts: overlapping label names between federated sources
- Stale data: federated data is always 1 scrape interval behind

**Better alternative:** Thanos/Mimir with StoreAPI — lazy fetching from object storage; only fetches data needed for query, not all series upfront.

---

### Q: What is a "dead man's switch" alert and why is it critical?

A dead man's switch is an alert that fires when the alerting pipeline is healthy. Inverted logic: always-firing alert that gets SUPPRESSED if pipeline is alive. If the alert stops being suppressed (pipeline breaks), it fires to external system.

Implementation:
1. Create `Watchdog` alert rule that always fires: `expr: 1`
2. Route to a special receiver that calls an external "heartbeat" endpoint (e.g., PagerDuty dead man's switch API)
3. External service alerts on-call if heartbeat stops within 5 minutes

Without dead man's switch: alert pipeline failure = silent incident. No alerts fire, but no one knows the alerting system itself is broken.

---

## Common Mistakes

| Mistake | Consequence | Correct Approach |
|---|---|---|
| Using `user_id` as a metric label | Cardinality explosion → Prometheus OOM | Store user-level data in logs; use exemplars for trace linkage |
| Single AlertManager instance | State loss on restart, SPOF for notifications | Always 3-node HA cluster with gossip |
| Querying ES for metrics | 100x storage waste, 10x slower queries | Use Prometheus TSDB for metrics |
| Not setting `minimum_master_nodes` in ES | Split-brain on network partition | Set `minimum_master_nodes = N/2 + 1` |
| Short Kafka retention for logs | Consumer lag beyond retention → permanent log loss | Set Kafka retention > consumer's maximum expected lag |
| Missing dead man's switch | Silent alert pipeline failure | Always configure Watchdog alert |
| No per-tenant query limits | One runaway query kills all tenants | Enforce `max_fetched_series_per_query` |
| Alert on raw values instead of rates | Noisy: counter resets trigger alerts | Always use `rate()` or `increase()` on counters |

---

## "What Would Break First?" Analysis

**Scenario: Traffic 10x spike on monitored services**

1. Metrics cardinality doubles (new pods, new endpoints)
2. Prometheus head block RAM grows → 70% threshold → alert
3. If not addressed: OOM → restart → 15min gap → alert evaluation blind
4. During blind window: actual incident in monitored service goes undetected
5. After Prometheus recovers: WAL replay → 15min gap in dashboards

**Scenario: Elasticsearch upgrade goes wrong**

1. Rolling upgrade: one ES node in unknown state
2. Kafka consumer lag builds (log ingestion slows)
3. Kafka retention reached → oldest unconsumed log batches deleted
4. Log gaps for that time window — permanent loss if not caught within Kafka retention period

**Scenario: On-call engineer runs expensive dashboard**

1. PromQL selects 50M series without label matchers
2. Query Frontend allocates 8GB for merge
3. OOM kill on Query Frontend pod
4. Pod restart (stateless) — 30s downtime
5. Other tenants' dashboards fail during restart window
6. Alert evaluation unaffected (separate query lane)

---

## Tradeoff Tables

### Push vs Pull

| Dimension | Pull | Push |
|---|---|---|
| Failure detection | Easy (`up == 0`) | Harder (need heartbeat) |
| Ephemeral workloads | Poor (job may finish before scrape) | Good (push before exit) |
| Firewall friendliness | Poor (requires inbound access) | Good (client initiates) |
| Control | Collector-authoritative | Application-authoritative |
| FaaS / serverless | Unusable | Required |

### Log Storage: Elasticsearch vs ClickHouse

| Dimension | Elasticsearch | ClickHouse |
|---|---|---|
| Text search | Excellent (inverted index) | Good (tokenbf index) |
| Compression | ~3:1 | ~10:1 |
| Write throughput | Good | Excellent |
| Query language | KQL/Lucene | SQL |
| Operational complexity | High (JVM, heap tuning) | Medium |
| Ecosystem | Huge (ELK stack) | Growing |

### Metrics TSDB: Prometheus vs InfluxDB vs TimescaleDB

| Dimension | Prometheus | InfluxDB | TimescaleDB |
|---|---|---|---|
| Query language | PromQL | Flux/InfluxQL | SQL |
| Multi-tenancy | External (Mimir) | Built-in (enterprise) | PostgreSQL RBAC |
| Write throughput | Very high | High | Medium |
| Long-term storage | External (Thanos/S3) | Built-in | PostgreSQL storage |
| Ecosystem | Huge (Kubernetes native) | Medium | PostgreSQL ecosystem |

---

## Scaling Evolution Questions

**Q: You're at 100K series today. What's your scaling plan for 100M?**

Phase 1 (100K→2M): Single Prometheus, add SSD, tune retention (local 15d + Thanos for long-term)
Phase 2 (2M→10M): Shard Prometheus by scrape job; Thanos Querier for global view
Phase 3 (10M→50M): Migrate to Mimir; hash ring sharding; per-tenant cardinality limits
Phase 4 (50M→100M): Multi-region Mimir; global federation; eBPF collection; streaming evaluation

**Q: How does your alerting change at each phase?**

Phase 1: AlertManager HA pair, local rules
Phase 2: Thanos Ruler for global rules (runs against Thanos Querier)
Phase 3: Mimir Ruler, per-tenant rule evaluation
Phase 4: Streaming alert evaluation (Flink over Kafka) for sub-second SLO burn rate alerts

---

## Staff+ / Principal Questions

**Q: Design SLO burn rate alerting.**

SLO: 99.9% success rate over 30 days = allows 43.2 minutes of downtime/month.

Multi-window burn rate alert:
- Short window (1h): if error rate consuming budget at 14x normal → alert immediately (true positive, fast)
- Long window (6h): if consuming at 6x over 6h → alert (catches slow burns)
- Both must be true → page on-call

This prevents both false positives (transient spike) and missed alerts (slow degradation).

```
SLO budget remaining = (current_error_rate × time_remaining) / allowed_downtime
```

**Q: How would you implement multi-tenant metric aggregation without transferring all raw data?**

Recording rules at tenant level → pre-aggregate per-tenant → global aggregation queries pre-aggregated series only. Never federate raw series to global layer.

**Q: What are exemplars and how do they solve the cardinality problem for trace linkage?**

Exemplars are optional annotations on a metric sample: `{traceId="abc123", spanId="xyz456"}`. Stored separately from the main time-series data (not as labels). Do not inflate cardinality.

Usage: metric spike at t=14:32 → fetch exemplar at that point → get trace_id → jump to trace → see which request caused spike.

Without exemplars: would need `trace_id` as a label → infinite cardinality → OOM.

**Q: eBPF-based observability — what problem does it solve?**

eBPF runs programs in the Linux kernel without modifying application code. Enables:
- Collecting metrics without SDK instrumentation (zero-code observability)
- Network-level tracing (track request as it moves through kernel TCP stack)
- Syscall-level profiling

Solves the instrumentation gap: legacy apps, third-party binaries, and polyglot services that can't adopt OTel SDK. Tools: Pixie, Cilium, Parca.

Limitation: Linux-only, kernel version constraints, complex security model (privileged container required).
