# 00 — Requirements Analysis: Metrics & Monitoring Platform

---

## Objective

Define the full requirements landscape for a production-grade observability platform that unifies metrics collection (Prometheus-style), log aggregation (ELK-style), distributed tracing (Jaeger/Tempo-style), alerting, and dashboards into a single cohesive system. This document establishes the engineering contract before any architectural decisions are made.

---

## 1. Functional Requirements

### 1.1 Metrics Collection & Storage

| Requirement | Detail |
|---|---|
| Time-series ingestion | Collect numeric metrics with labels/tags and timestamps from agents, scrapers, and push clients |
| Pull model (scraping) | Platform scrapes registered targets at configurable intervals (like Prometheus) |
| Push model | Agents and SDKs can push metrics directly (StatsD, OTLP, custom) |
| Label-based dimensionality | Each metric has a name and an arbitrary set of key-value label pairs |
| Retention policies | Per-tenant or per-metric configurable retention windows (e.g., 15 days raw, 1 year downsampled) |
| Downsampling | Automatic coarsening of high-resolution data to 5m, 1h, 1d resolutions for long-term storage |
| Query language | PromQL-compatible query interface for filtering, aggregation, and mathematical operations |
| Multi-tenancy | Logical tenant isolation within a shared storage infrastructure |
| Recording rules | Pre-computed derived metrics written back into storage on a schedule |

### 1.2 Log Aggregation

| Requirement | Detail |
|---|---|
| Structured log ingestion | Accept JSON, logfmt, and unstructured text logs |
| High-volume ingest | Billions of log lines per day from thousands of microservices |
| Log levels | DEBUG, INFO, WARN, ERROR, FATAL with filter/routing support |
| Field extraction | Parse structured fields at ingest or query time |
| Full-text search | Sub-second full-text search across petabytes of logs |
| Trace correlation | Every log line optionally carries trace_id and span_id for cross-signal correlation |
| Log retention | Tiered storage: hot (7 days), warm (30 days), cold (1 year) |
| Live tail | Real-time streaming of log lines matching a query |
| Log-based metrics | Derive counter/gauge metrics from log patterns (e.g., error rate from ERROR log count) |

### 1.3 Distributed Tracing

| Requirement | Detail |
|---|---|
| Trace ingestion | Accept spans via OTLP, Zipkin, and Jaeger protocols |
| Span model | TraceID, SpanID, ParentSpanID, service name, operation, duration, status, attributes |
| Trace assembly | Reconstruct complete trace trees from individual spans |
| Sampling | Head-based and tail-based sampling strategies |
| Dependency graph | Auto-discover service-to-service call graphs from trace data |
| Cross-signal linking | Navigate from trace → logs → metrics for the same time window and service |
| Trace search | Search by service, operation, duration, status, and custom attributes |
| Trace retention | Sampled traces retained for 7–30 days; full traces for error spans longer |

### 1.4 Alerting

| Requirement | Detail |
|---|---|
| Alert rules | Define threshold, anomaly, and absence-based alert rules over metrics and logs |
| Multi-condition alerts | Combine multiple signal types in a single alert rule |
| Alert routing | Route alerts to PagerDuty, Slack, email, webhooks, OpsGenie based on severity and team |
| Deduplication | Suppress duplicate alerts during the same firing period |
| Silencing | Temporarily mute alerts for known maintenance windows |
| Inhibition | Suppress child alerts when a parent alert fires |
| Alert history | Full history of alert state transitions for post-incident review |
| Notification templates | Jinja2-style templatable notification bodies |

### 1.5 Dashboards & Visualization

| Requirement | Detail |
|---|---|
| Dashboard builder | Drag-and-drop panel layout similar to Grafana |
| Panel types | Time series, stat, heatmap, bar chart, table, log panel, trace waterfall |
| Variable support | Template variables in queries (e.g., `$env`, `$service`) |
| Dashboard sharing | Public and authenticated sharing links |
| Dashboard-as-code | Export/import dashboards as JSON or YAML |
| Annotations | Overlay deployment events, incidents, and releases on graphs |
| Alerts in dashboards | Show alert state directly on relevant panels |

### 1.6 Configuration & Discovery

| Requirement | Detail |
|---|---|
| Scrape target discovery | Kubernetes SD, Consul SD, static configs, EC2 SD |
| Agent configuration | Central push of agent configs; agents pull updates |
| Data source management | Register and manage multiple backend data sources per tenant |
| User and team management | RBAC: viewer, editor, admin roles per organization/team |

---

## 2. Non-Functional Requirements

### 2.1 Performance

| Dimension | Target |
|---|---|
| Metric ingestion latency | < 1 second end-to-end from agent to queryable storage |
| Log ingestion latency | < 5 seconds from emission to searchable |
| Trace ingestion latency | < 2 seconds from span arrival to trace queryable |
| Dashboard query p99 | < 3 seconds for 24-hour range, 5 signals |
| Alert evaluation latency | < 30 seconds from condition breach to notification sent |
| Live tail latency | < 500ms from log write to stream delivery |

### 2.2 Availability

| Component | Target SLA |
|---|---|
| Ingestion pipeline | 99.99% (4 nines) — data loss is unacceptable during incidents |
| Query & dashboard layer | 99.9% (3 nines) — brief unavailability tolerable |
| Alerting engine | 99.95% — alert delivery must be highly reliable |
| Configuration API | 99.9% |

### 2.3 Scalability

| Dimension | Target |
|---|---|
| Active time-series | 100 million+ (growing 20% YoY) |
| Log ingestion throughput | 5 million log lines/second peak |
| Trace spans ingested | 500,000 spans/second peak |
| Metric scrape targets | 500,000+ registered targets |
| Concurrent dashboard users | 50,000 simultaneous |
| Alert rules evaluated | 1 million rules evaluated every 30 seconds |

### 2.4 Durability

- Zero data loss for metrics, logs, and traces once acknowledged at ingestion
- Cross-AZ replication for all storage components
- Backup and point-in-time recovery for configuration and alert rule state

### 2.5 Operability

- Full observability of the observability platform itself ("eat your own dog food")
- Zero-downtime upgrades via rolling deployments
- Tenant-level rate limiting and quota enforcement
- Graceful degradation: ingestion must survive query-layer failures

---

## 3. Assumptions

- The platform is multi-tenant but not multi-cloud in v1; tenants are logical, not physical, isolation boundaries
- Clients are expected to use OpenTelemetry SDKs as the primary instrumentation path; native protocol support (Prometheus remote write, Fluent Bit, Zipkin) is provided for legacy compatibility
- The platform does NOT store business-domain data; it stores observability signals only
- SaaS deployment model is the primary target; self-hosted is a v2 concern
- Alert routing integrates with external notification systems (PagerDuty, Slack) rather than owning notification delivery
- Kubernetes is the primary deployment substrate for the platform itself
- Time-series data is append-only; updates to historical data are not supported

---

## 4. Constraints

- Total cost of storage must remain bounded through aggressive compression and downsampling; raw high-resolution data retained for at most 15 days
- Regulatory compliance (SOC 2 Type II) requires audit logging of all data access and configuration changes
- No hard dependency on a single cloud provider; storage backends must be provider-agnostic (S3-compatible object storage)
- Query results must be consistent within a single tenant's view; cross-tenant isolation is strictly enforced
- GDPR: PII must not appear in metric labels, log fields must be maskable on ingest

---

## 5. Scale Estimation & Back-of-the-Envelope Calculations

### 5.1 Metric Time-Series

**Assumptions:**
- 10,000 microservice instances monitored
- Each instance exposes ~10,000 unique time-series (metric name × label combinations)
- Scrape interval: 15 seconds

```
Active time-series = 10,000 instances × 10,000 series = 100M active series

Scrape rate:
  100M series / 15s = 6.67M data points/second ingested

Storage per data point (Gorilla/XOR compressed):
  ~1.5 bytes/sample (compressed from 16 bytes raw)

Storage per day:
  6.67M samples/sec × 86,400 sec/day × 1.5 bytes = ~866 GB/day raw
  With 10x compression: ~87 GB/day

Storage for 15-day raw retention:
  87 GB/day × 15 days = ~1.3 TB compressed

Downsampled (5m resolution, 1 year):
  6.67M series × (86,400/300) samples/day × 1.5 bytes × 365 days / 10 compression ≈ 7 TB
```

**Total metric storage estimate: ~10 TB for 1 year across resolutions.**

### 5.2 Log Aggregation

**Assumptions:**
- 10,000 service instances
- Average log rate: 500 lines/instance/second at peak
- Average log line size (structured JSON): 500 bytes
- Peak multiplier: 3x during incidents

```
Peak ingestion rate:
  10,000 instances × 500 lines/sec = 5M lines/sec peak

Daily log volume (average, 2x peak compression):
  10,000 × 200 lines/sec (average) × 86,400 × 500 bytes = 86.4 TB/day raw

With LZ4 compression (typical 5–10x for structured logs):
  ~10–17 TB/day compressed

7-day hot storage:
  14 TB/day × 7 days = ~98 TB hot tier

30-day warm storage:
  14 TB/day × 30 days = ~420 TB warm tier (Elasticsearch with warmer allocation)

1-year cold storage (S3):
  14 TB/day × 365 days = ~5 PB (with additional compression: ~1–2 PB)
```

**Elasticsearch cluster sizing for hot tier:**
- 98 TB data ÷ 70% disk utilization ÷ 2 replicas = ~280 TB raw disk needed
- Assuming 20 data nodes × 16 TB NVMe: adequate for hot tier

### 5.3 Distributed Traces

**Assumptions:**
- 1M RPS across all monitored services
- Average trace depth: 10 spans
- Tail-based sampling: retain 1% of successful traces + 100% of error traces
- Error rate: 0.1%
- Average span size (OTLP compressed): 1 KB

```
Span ingest rate:
  1M RPS × 10 spans = 10M spans/sec (gross)
  Spans stored after sampling:
    Success traces: 1M × 0.999 × 1% × 10 = 99,900 spans/sec
    Error traces: 1M × 0.001 × 100% × 10 = 10,000 spans/sec
    Total stored: ~110,000 spans/sec

Daily stored traces:
  110,000 spans/sec × 86,400 × 1 KB = ~9.5 TB/day

30-day retention: ~285 TB
```

### 5.4 Request Rate Summary

| Signal | Ingestion RPS | Query RPS (p50) | Peak Query RPS |
|---|---|---|---|
| Metrics | 6.67M data points/sec | 5,000 queries/sec | 20,000 queries/sec |
| Logs | 5M lines/sec | 500 queries/sec | 2,000 queries/sec |
| Traces | 10M spans/sec (gross) | 200 queries/sec | 1,000 queries/sec |
| Alert eval | N/A (internal) | 33,000 rule evals/sec | 33,000 rule evals/sec |

### 5.5 Network Bandwidth

```
Metric scrape: 6.67M data points/sec × 16 bytes = ~107 MB/s inbound
Log ingest: 5M lines/sec × 500 bytes = ~2.5 GB/s inbound peak
Trace ingest: 10M spans/sec × 1KB = ~10 GB/s gross (pre-sampling)
Post-sampling trace: ~110K spans/sec × 1KB = ~110 MB/s stored

Total inbound peak: ~13 GB/s → requires 100 Gbps network fabric with headroom
```

---

## 6. Read/Write Patterns

### 6.1 Write Patterns

| Signal | Write Pattern | Characteristics |
|---|---|---|
| Metrics | Time-ordered, append-only, bursty during scrape cycles | Write amplification during scrape convergence; 15s cadence creates coordinated thundering herds |
| Logs | Continuous high-volume stream | Nearly uniform; spikes during deployments, incidents, batch jobs |
| Traces | Span-by-span, out-of-order assembly | Spans arrive within seconds; late spans (network delays) must be handled |
| Alert state | Low-volume, periodic | 30s evaluation intervals; state transitions are infrequent |

### 6.2 Read Patterns

| Signal | Read Pattern | Characteristics |
|---|---|---|
| Metrics queries | Recent data (last 1h) → 80% of queries; range queries up to 1 year are <5% | Heavy read on hot cache; cold data rare but expensive |
| Log search | Ad-hoc exploration, incident investigation | Unpredictable access patterns; full-text search is CPU-intensive |
| Trace lookup | TraceID lookup (O(1)) + search by service/duration | Most queries are single trace retrieval; fanout searches are expensive |
| Dashboards | Repeated, cacheable queries on fixed intervals | High temporal locality; cache hit ratio > 90% achievable |
| Alert eval | Internally scheduled, range queries over last N intervals | Predictable; resource-plannable; never cached |

**Critical insight:** The system is overwhelmingly write-heavy. The ratio of writes to reads is approximately 1000:1 for metrics and 10,000:1 for logs. Architecture must be optimized for write path first.

---

## 7. Latency Expectations

| Operation | p50 | p95 | p99 | Hard Limit |
|---|---|---|---|---|
| Metric scrape → queryable | 500ms | 2s | 5s | 30s |
| Log write → searchable | 2s | 5s | 15s | 60s |
| Trace span → assembled trace | 1s | 5s | 10s | 30s |
| Dashboard render (1h range) | 200ms | 800ms | 2s | 5s |
| Dashboard render (24h range) | 500ms | 2s | 5s | 10s |
| Alert evaluation cycle | 30s | 30s | 30s | 60s |
| Alert notification delivery | 5s | 15s | 30s | 120s |

---

## 8. Availability Targets & SLI/SLO

### 8.1 Ingestion Pipeline SLO

- **SLI:** Percentage of metric scrapes completed without error
- **SLO:** 99.99% of scrapes succeed (< 52 minutes downtime/year)
- **Error budget:** 52 minutes/year
- **Burn rate alert:** Page at 5× burn rate (consuming error budget 5× faster than expected)

### 8.2 Query Layer SLO

- **SLI:** Percentage of dashboard queries returning within 5 seconds
- **SLO:** 99.9% of queries < 5s
- **Error budget:** 8.7 hours/year

### 8.3 Alert Delivery SLO

- **SLI:** Percentage of fired alerts delivering notification within 2 minutes of breach
- **SLO:** 99.95% of alerts delivered within 2 minutes
- **Error budget:** 4.4 hours/year

### 8.4 Data Durability SLO

- **SLI:** Zero data loss once ingestion is acknowledged
- **SLO:** 99.999999% (8 nines) durability for acknowledged data
- **Mechanism:** Multi-AZ replication before acknowledgment; WAL-based recovery

---

## 9. Interview Discussion Points

**"What is the most critical reliability concern in a monitoring platform?"**
Ingestion reliability. A monitoring platform that loses data during an incident is not a monitoring platform — it is a liability. The ingestion path must be decoupled from storage failures. Using Kafka as a durable buffer means storage components can fail and recover without data loss.

**"How would you handle a cardinality explosion in metrics?"**
High cardinality (unbounded label values like user IDs, request IDs in metric labels) is the number one operational risk in Prometheus-style systems. Mitigations: per-tenant cardinality limits enforced at ingestion with backpressure; cardinality analysis tooling to alert operators; rejecting series that would exceed limits rather than silently degrading.

**"How do you estimate storage without running the system?"**
Back-of-the-envelope: active_series × samples_per_day × bytes_per_sample × compression_ratio × retention_days. Key insight: Gorilla compression (used in Prometheus) achieves 1.37 bytes/sample on real workloads, which is the baseline for time-series storage estimation.

**"What breaks first at 10× scale?"**
For metrics: the Prometheus single-node storage engine (solved by Thanos/Cortex sharding). For logs: Elasticsearch coordinating nodes become bottlenecks during burst indexing. For traces: the in-memory span buffer for tail-based sampling becomes the limiting factor.

**"How would you enforce GDPR in a logging system?"**
Field-level masking pipeline at ingestion: define sensitive field patterns (e.g., email, SSN regex); hash or redact before writing to storage. Cannot retroactively delete from append-only log stores without full re-ingestion; design for it upfront with a masking proxy layer.

**"Why is 'observability of the observability platform' non-trivial?"**
Bootstrap problem: you cannot use the platform itself to monitor its own ingestion failures (you won't see the signal if ingestion is down). Solution: maintain a lightweight out-of-band health check system (separate Prometheus scraping the platform's own endpoints) that is operationally independent from the main ingestion path.
