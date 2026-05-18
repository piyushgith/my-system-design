# 00 — Requirements Analysis: Distributed Job Scheduler

## Objective
Define the functional and non-functional requirements for a production-grade distributed job scheduling system capable of reliably scheduling, triggering, and executing millions of background jobs across a cluster — comparable in scope to Quartz Scheduler, Apache Airflow, Celery Beat, or GitHub Actions.

---

## 1. Functional Requirements

### Core Scheduling
- Define jobs with cron expressions, one-time triggers, or interval-based schedules
- Support timezone-aware scheduling
- Support delayed job execution (fire-at timestamp)
- Support job chaining and DAG-based dependency execution
- Allow priority assignment to jobs (P0–P4)
- Enable pausing, resuming, and cancelling scheduled jobs
- Allow parameterized jobs (runtime arguments injected at trigger time)

### Execution
- Dispatch jobs to worker nodes for execution
- Guarantee at-least-once execution (with idempotency for exactly-once semantics)
- Support retry policies: fixed, linear, exponential backoff with jitter
- Support max-retry limits and dead-letter queuing after exhaustion
- Support job timeouts (hard deadline for execution)
- Support exclusive execution (prevent concurrent runs of the same job)
- Support concurrent execution limits per job type

### Observability
- Track job execution history: start time, end time, status, worker, output/error logs
- Expose real-time job status via API
- Emit events for external consumers (webhooks, Kafka)
- Maintain audit log of all schedule changes

### Multi-tenancy (Optional, Phase 2)
- Namespace isolation per tenant
- Per-tenant resource quotas (max concurrent jobs, max jobs/hour)
- Tenant-scoped RBAC

---

## 2. Non-Functional Requirements

| Dimension | Target |
|---|---|
| Availability | 99.99% (≤52 min/year downtime) |
| Scheduling latency | ≤1 second from trigger time to enqueuing |
| Execution start latency | ≤5 seconds from enqueue to start |
| Throughput | 100k concurrent job executions |
| Job store scale | 10M registered jobs |
| Execution history retention | 90 days hot, 2 years cold |
| RPS (API) | 10,000 RPS across all endpoints |
| Clock skew tolerance | ≤500ms between scheduler nodes |
| Exactly-once guarantee | Per-job idempotency with fencing tokens |

---

## 3. Assumptions

- Jobs are lightweight to moderately heavy (seconds to hours); not compute-intensive HPC jobs
- Workers are stateless and horizontally scalable
- Network partitions are possible; the system must handle split-brain scenarios
- Cron expressions follow standard UNIX cron (5-field) with optional seconds field
- Job logic is implemented by the caller — scheduler only dispatches; it doesn't run business logic itself
- Multi-datacenter active-passive failover is sufficient for Phase 1; active-active in Phase 3
- Clock synchronization is handled by NTP/PTP at the infrastructure level; scheduler must tolerate ±500ms skew
- Tenants are known at onboarding time (no self-service provisioning in Phase 1)

---

## 4. Constraints

- Must support graceful shutdown: in-flight jobs complete; no new dispatches accepted
- Must not lose a scheduled job even if all scheduler nodes crash simultaneously (durability via PostgreSQL)
- Must comply with GDPR: job parameters may contain PII — audit log must be immutable and retentable
- Must interoperate with existing CI/CD pipelines via webhooks and REST callbacks
- No external SaaS dependencies in data path (on-prem deployable)

---

## 5. Back-of-the-Envelope Calculations

### Job Store Scale
```
10,000,000 registered jobs
Each job record: ~2KB (metadata, schedule, params)
Total job store: 10M × 2KB = 20GB (fits comfortably in PostgreSQL)
```

### Execution History Scale
```
Assume average job fires once per hour:
10M jobs × 1 execution/hour = 10M rows/hour = ~2,800 rows/second write rate
Each execution row: ~1KB (status, timestamps, logs pointer)
Daily write: 10M × 24 = 240M rows/day = 240GB/day (RAW — needs partitioning + log offloading)

Realistic: 1M active jobs (10% of 10M are hot), firing 1M/hr = 280 rows/sec — manageable
Hot executions (last 24hr): 1M × 24 = 24M rows = 24GB in PostgreSQL partition
90-day retention: 24M × 90 = 2.16B rows = 2.1TB — archive to S3 + query via Athena
```

### Concurrent Executions
```
100,000 concurrent executions
Average execution: 30 seconds
Throughput: 100,000 / 30 = ~3,333 new jobs started per second at steady state
Kafka ingestion: 3,333 messages/sec — trivially handled (Kafka handles millions/sec)
Worker count (assuming 10 concurrent jobs/worker): 100,000 / 10 = 10,000 workers (auto-scaled)
```

### Midnight Cron Spike
```
Common pattern: */1 * * * * (every minute) or 0 0 * * * (midnight)
Assuming 10% of 1M active jobs fire at midnight: 100,000 jobs triggered simultaneously
Dispatch window must spread over 60 seconds (next minute boundary)
Requires burst Kafka capacity: 100,000 / 60 = ~1,667 messages/sec (easily handled)
Worker scale-up event must pre-warm based on HPA metrics
```

### Lock Table
```
Concurrent lock acquisitions: 100,000 (one per in-flight execution)
Lock row size: ~200 bytes
Total: 100,000 × 200B = 20MB — fully in-memory (Redis)
```

### Scheduler Nodes
```
Scheduler duty: scan due jobs every 1–5 seconds
At 1M active jobs: partition-based scan (scan only jobs due ±window)
Index on (next_execution_time, status) — point read, not full scan
PostgreSQL can handle: 10,000 indexed reads/sec per replica — scheduler node overhead is negligible
```

---

## 6. Read/Write Patterns

| Operation | Pattern | Volume | Notes |
|---|---|---|---|
| Poll for due jobs | Read-heavy | 1–5 sec interval | Scheduler node only — indexed |
| Submit job execution | Write | ~3,000/sec | Kafka dispatch |
| Update execution status | Write | ~3,000/sec | Workers writing back |
| Query execution history | Read | ~500 RPS | Dashboard, monitoring |
| Register/update job | Write | Low (~10 RPS) | Config-time, not runtime |
| Acquire distributed lock | Read+Write | ~100k/sec | Redis SETNX |
| Fetch job params | Read | ~3,000/sec | Workers fetching job def |

---

## 7. Traffic Assumptions

- Peak API traffic: 10,000 RPS (mostly read: status checks, history queries)
- Scheduler polling: internal, low-overhead, PostgreSQL indexed reads
- Worker heartbeat: 100,000 workers × 1 heartbeat/10sec = 10,000 writes/sec to Redis
- Kafka throughput: 10,000 messages/sec sustained, 50,000 burst

---

## 8. Latency Expectations

| SLO | P50 | P95 | P99 |
|---|---|---|---|
| Job trigger → enqueue | <200ms | <500ms | <1s |
| Enqueue → execution start | <2s | <5s | <10s |
| API response (job status) | <50ms | <200ms | <500ms |
| Execution history query | <100ms | <500ms | <2s |

---

## 9. Availability Targets

- Scheduling subsystem: 99.99% (active-passive HA, <30s failover)
- Execution subsystem: 99.9% (stateless workers, rolling redeploy)
- API layer: 99.99% (multi-instance, load balanced)
- Storage (PostgreSQL): 99.99% with streaming replication + automated failover
- Redis: 99.99% with Redis Sentinel or Redis Cluster

---

## Interview Discussion Points

**Q: Why 99.99% for scheduler but only 99.9% for workers?**
A: The scheduler is the single point of coordination — a 1-minute outage causes missed triggers. Workers are ephemeral; a worker crash just means that job retries on another worker.

**Q: How do you handle the midnight spike without pre-warming?**
A: You don't — the system must pre-warm. HPA on workers reacts to Kafka consumer lag metric (Prometheus + KEDA). Scheduler can also "look ahead" 5 minutes and pre-enqueue burst jobs, smoothing the dispatch curve.

**Q: Why 90-day hot + 2-year cold, not just delete?**
A: Compliance, debugging, SLA reporting. Hot in PostgreSQL (partitioned), cold in S3 (Parquet) with Athena for ad-hoc queries. This is standard FAANG practice for audit-relevant data.
