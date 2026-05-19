# 16 — Advanced Improvements & Architecture Critique

## Objective

Honest critique of the architecture decisions made throughout this design. Identify scaling limits, tech debt risks, operational burdens, and what a Staff+ interviewer will challenge. Propose concrete improvements for each weakness.

---

## Architecture Critique

### Critique 1: Prometheus Pull Model Doesn't Scale for Ephemeral Workloads

**Problem:** Prometheus pull model requires the scraper to reach targets. With serverless functions (AWS Lambda, GCP Cloud Run), the target exists for milliseconds — Prometheus will never scrape it.

**Current design's gap:** FaaS metrics are simply unobservable in a pure pull model.

**Improvement:**
- Push model via OpenTelemetry SDK → OTLP Collector → Mimir remote_write
- Prometheus Push Gateway for batch jobs (anti-pattern for long-running services but valid for jobs)
- Accept that FaaS observability is fundamentally different: use cloud-native metrics (CloudWatch, Cloud Monitoring) for FaaS; reserve Prometheus for long-running services

**Tradeoff:** Two metrics systems in parallel (Prometheus + cloud-native) adds complexity. Unified view in Grafana via multi-datasource panels mitigates UX fragmentation.

---

### Critique 2: Cardinality Is the Achilles Heel — No Systemic Prevention

**Problem:** The design describes cardinality mitigation (relabeling, limits) but not systemic prevention. Engineers keep accidentally adding high-cardinality labels because there's no feedback loop at development time.

**Current design's gap:** Cardinality explosion is discovered in production, not at instrumentation time.

**Improvement:**
- **Pre-commit cardinality analysis:** CI pipeline check that estimates cardinality delta when new instrumentation is added
- **Cardinality explorer:** Grafana dashboard showing top-N series by cardinality, trending cardinality over last 7 days
- **Ingestion-time enforcement with helpful errors:** reject high-cardinality series with specific error message ("label 'user_id' has 50000 unique values in the last hour; use exemplars instead")
- **Instrumentation review process:** observability platform team reviews new metric additions for cardinality impact before merging

---

### Critique 3: No Native Multi-Tenancy in Vanilla Prometheus

**Problem:** Prometheus has no concept of tenants. All data is in one flat namespace. The design defers multi-tenancy to Mimir (Phase 2), but many teams try to make Prometheus multi-tenant with label-based isolation.

**Label-based isolation is broken:** A tenant can query `{__tenant_id__="other_tenant"}` in PromQL and see another tenant's data unless enforced by a proxy layer (Prom Label Proxy).

**Improvement:**
- Hard requirement: use Mimir/Cortex for any multi-tenant use case — not Prometheus + label tricks
- If stuck on Prometheus: deploy Prom Label Proxy (open source) in front of Prometheus API; enforces label injection per authenticated user
- For compliance: Mimir's per-tenant storage (separate S3 prefixes, separate ingester ring slots) is the only design that gives cryptographic tenant isolation

---

### Critique 4: Elasticsearch is Over-Used

**Problem:** The design uses Elasticsearch for log storage. At scale (> 1TB/day), ES is expensive (JVM heap, replica overhead), operationally complex (JVM tuning, shard rebalancing), and 3–10x less compressed than columnar stores.

**Scaling limit:** ES JVM heap capped at 31GB (compressed oops limit). Beyond that, performance degrades sharply. Large ES clusters need dedicated JVM expertise.

**Improvement:**
- For log analytics: replace ES with **ClickHouse** — 10:1 compression, SQL, 10x faster aggregations on large datasets
- For log search (free-text): ES still wins for ad-hoc keyword search; ClickHouse's text search is weaker
- Hybrid approach: ClickHouse for analytics/compliance retention, ES for hot interactive search (last 7 days)
- Or: **Grafana Loki** — log aggregation optimized for label-indexed logs (not full-text), extremely cheap storage (S3-native), integrated into Grafana stack; trades search flexibility for cost

| Store | Best For | Avoid For |
|---|---|---|
| Elasticsearch | Full-text search, ad-hoc exploration | Long-term analytics, high-volume storage |
| ClickHouse | Aggregation analytics, compliance archival | Interactive full-text search |
| Loki | Label-indexed log search, cost-sensitive | Complex regex/full-text across unstructured logs |

---

### Critique 5: Alert Evaluation Interval is 1 Minute — Too Slow for SLO Burn Rate

**Problem:** Prometheus evaluates alert rules every 1 minute by default. For a 99.99% SLO, budget is exhausted in 52 minutes. By the time a 14x burn rate alert fires (after `for: 1m`), 14 minutes of budget is already consumed.

**Scaling limit:** Reducing eval interval to 15s increases TSDB query load proportionally. At 10K rules × 15s eval = 667 rule evaluations/sec — feasible but costly.

**Improvement:**
- **Multi-window burn rate alerts:** Two windows (1h + 6h) reduces false positives without needing sub-minute eval
- **Streaming evaluation with Flink:** Kafka → Flink → stateful stream processing → alert state — enables sub-second evaluation, not limited by scrape interval
- **Pre-aggregate via recording rules:** Record burn rate as a new time-series updated every 15s; alert on the pre-aggregated series rather than raw counters

---

### Critique 6: WAL-Based Durability Has a 2-Hour Loss Window

**Problem:** Prometheus head block lives in memory for 2 hours before being persisted. On catastrophic hardware failure (not just process crash), up to 2 hours of recent metrics can be lost.

**Current design's gap:** WAL protects against process crash. It does NOT protect against disk failure (WAL and head block are on the same disk).

**Improvement:**
- Use Mimir with `replication_factor=3` — samples written to 3 ingesters simultaneously; disk failure on one ingester loses no data
- Or: Prometheus agent mode → remote_write to Mimir with WAL-backed retry queue; even if agent dies, WAL on separate volume preserves samples for replay

---

## Scaling Limits

| Component | Limit | Symptom | Fix |
|---|---|---|---|
| Single Prometheus | 10M series / 16GB RAM | OOM kill | Migrate to Mimir |
| Mimir ingester (single) | ~5M series per ingester | CPU saturation | Add ingesters; rebalance ring |
| Elasticsearch per node | 31GB JVM heap | GC pauses > 5s | Add data nodes; reduce heap per node |
| Kafka partition | 1 partition = 1 consumer thread | Consumer lag | Increase partitions; add consumers |
| AlertManager gossip | ~100 members max practical | Gossip storm | Use hierarchical AlertManager (federated) |
| PromQL range query | > 1M series in memory | OOM on query frontend | Query limits + sharding |
| Thanos Compactor | Single instance (singleton) | Compaction queue backlog | Cannot scale; must be fast enough single-node |

---

## Tech Debt Risks

| Debt | Accrual Rate | Cost to Fix Later | Priority |
|---|---|---|---|
| Static scrape configs (no service discovery) | High — new service = manual config change | Medium — migrate to ServiceMonitor CRDs | High |
| Alert rules defined in flat files, no ownership | Medium — alerts become orphaned | Medium — introduce AlertRule GitOps + ownership metadata | Medium |
| No dashboard version control | High — dashboards edited in UI, changes lost | Low — enable Grafana → Git sync | High |
| Elasticsearch dynamic mapping enabled | Medium — new log fields auto-create mappings, mapping explosion | High — requires index migration | Medium |
| Direct Prometheus API access (no query proxy) | Low — until multi-tenancy needed | High — retrofit tenant enforcement | High when multi-tenant |
| No exemplars in existing instrumentation | Permanent — must update all service SDKs | High | Medium |

---

## Operational Complexity Assessment

| Component | Ops Burden | Key Runbooks Needed |
|---|---|---|
| Prometheus + Thanos | Medium | WAL corruption recovery, Thanos Compactor failure, S3 permission errors |
| Mimir | High | Ingester ring management, zone-aware replication failures, ruler sync |
| Elasticsearch | High | Shard rebalancing, JVM heap tuning, split-brain recovery, ILM stuck |
| Kafka | Medium | Partition leader election, consumer group rebalancing, retention management |
| AlertManager HA | Low | Gossip peer connectivity, silence persistence |
| Grafana | Low | Dashboard provisioning, datasource credential rotation |

**Mimir is the highest operational investment.** Do not adopt Mimir until you have dedicated observability SRE who understands consistent hashing, zone-aware replication, and object storage interaction patterns.

---

## What a Staff+ Interviewer Will Challenge

**Challenge 1: "Your design uses Kafka everywhere — isn't that over-engineering?"**

Response: Kafka is justified for log pipeline (decouples agents from ES write latency; ES slowdowns don't drop logs). Kafka is optional for metrics pipeline if using Mimir remote_write directly. At Phase 0-1, skip Kafka for metrics. Introduce for logs when ES write latency becomes variable (typically > 10GB/day).

**Challenge 2: "How do you prevent a monitoring platform outage during a production incident?"**

Response: (1) Observability platform runs in a separate infrastructure namespace with dedicated resource quotas — production incident cannot starve monitoring. (2) Meta-monitoring (Prometheus-to-monitor-Prometheus) is deployed on separate lightweight infra. (3) Monitoring platform uses different cloud region from production (network partition doesn't take both down). (4) On-call tooling (PagerDuty integration) survives monitoring platform downtime via dead man's switch.

**Challenge 3: "At 50M series, how much does this cost to run?"**

Response: Rough estimate for 50M series:
- Mimir storage (S3): 50M series × 1.3 bytes/sample × 2 samples/min × 525,600 min/yr ≈ 70TB/yr → ~$1,600/yr S3
- Mimir compute: 30 ingesters × $500/mo = $15K/mo
- Elasticsearch (5TB/day, 30-day hot): 150TB SSD = ~$30K/mo
- Kafka: $2K/mo
- Query layer: $5K/mo
- Total: ~$55K/mo at 50M series scale

Cost optimization: ClickHouse for logs (10x compression) → 150TB → 15TB → saves ~$27K/mo. Biggest lever is log storage cost.

**Challenge 4: "Why not build this on top of a managed service like Datadog?"**

Response: Build vs buy analysis:
- Datadog: $0.10/host/hr + $0.01/GB logs + custom metric pricing → at 500 hosts + 5TB/day logs → ~$100K/mo
- Self-built: ~$55K/mo at same scale + ~6 engineer salaries ($1.5M/yr fully loaded)
- Break-even: if 6 engineers can maintain this AND build product features, self-built wins at scale
- Below ~100 hosts: Datadog wins on total cost
- Above ~500 hosts with engineering capacity: self-built wins

Real answer: use managed services (Grafana Cloud, Datadog) until scale demands cost optimization. Building an observability platform from scratch is a product in itself.

**Challenge 5: "What's the hardest operational problem you haven't addressed?"**

Honest answer: **Cardinality governance at organizational scale.** When 100 teams are instrumenting 200 services, cardinality grows unbounded. The platform team can set limits, but enforcing them requires cultural change + developer education + tooling + approval processes. This is a people problem, not a technology problem. The best technical systems (per-tenant limits, cardinality explorer, CI checks) only help if engineers actually use them. Failure mode: cardinality limit hit mid-incident, alert evaluation breaks, platform team gets paged to raise limits under pressure, limits become meaningless.

---

## Advanced Topics Not Covered in Main Design

| Topic | What It Is | When to Adopt |
|---|---|---|
| Continuous Profiling (Pyroscope) | Always-on CPU/memory flame graphs | When latency issues are not explainable by traces alone |
| eBPF Collection (Cilium/Pixie) | Kernel-level metrics without instrumentation | Legacy apps that can't adopt OTel SDK |
| Streaming Evaluation (Flink) | Sub-second alert evaluation on Kafka streams | When 1-minute eval interval misses SLO burn rate |
| Vector Embeddings for Log Search | Semantic log search (similar errors cluster together) | Large log volumes where keyword search is insufficient |
| OpenTelemetry Semantic Conventions | Standardized attribute names across all signals | From day 1 — prevents naming fragmentation |
| Grafana Beyla | eBPF auto-instrumentation for Go/Java/Python without code changes | Zero-instrumentation observability for legacy services |
| SLO-as-Code | Define SLOs in YAML, auto-generate alerts + dashboards | Phase 2+ when teams own their SLO definitions |

---

## Startup vs FAANG Differences

| Dimension | Startup | FAANG |
|---|---|---|
| Stack choice | Grafana Cloud managed (SaaS) → no ops burden | Self-hosted Mimir/Thanos/ES at planetary scale |
| Cardinality | 100K series — not a problem | 500M+ series — primary design constraint |
| Multi-tenancy | Single tenant often OK | Hundreds of internal teams, each a tenant |
| Alert governance | 10 hand-crafted alerts, one person owns them | Thousands of alerts, need GitOps + review process |
| Log retention | 7–30 days | 7 years (compliance), cold tier in S3 Glacier |
| Custom tooling | None — use off-the-shelf | Custom TSDB extensions, custom query engines |
| Cost | Irrelevant at startup scale | $1M+/mo → dedicated cost optimization team |
| Observability of observability | Not needed | Critical — meta-monitoring is a product itself |
