# 15 — Implementation Roadmap: Distributed Job Scheduler

---

## Objective

Define a phased implementation plan from a single-node scheduler MVP to a production-grade distributed job scheduling platform handling 100K concurrent executions across a multi-region cluster. Each phase includes architecture evolution, infrastructure evolution, team scaling, and risk profile.

---

## Phase 0: MVP (Weeks 1–4) — 1–2 Engineers

### Goal

A working job scheduler that can define cron-based jobs and execute them reliably on a single node. Demonstrable in a local environment. No HA, no distributed locking, no multi-tenancy.

### Features

- Define jobs via REST API (name, cron expression, HTTP callback URL)
- One-time delayed jobs (fire-at timestamp)
- Basic cron evaluation: every-minute, every-hour, daily
- In-process job executor (runs in same JVM as scheduler)
- Simple execution history (in-memory, not persistent)
- Pause/resume/delete a job via API
- No retry logic (failed jobs stay failed)
- No distributed locking (single node only)

### Architecture

```
Client → Spring Boot (single instance)
              ↓ Spring @Scheduled (polling every 5s)
         PostgreSQL (single node)
              ↓
         HTTP Callback (target service)
```

### Infrastructure

- Single EC2 t3.medium or Docker Compose locally
- PostgreSQL (single node, RDS t3.micro or local)
- No Redis, no Kafka, no Kubernetes

### Database Schema (MVP)

```
jobs: id, name, cron_expression, callback_url, status, next_execution_time
job_executions: id, job_id, trigger_time, status, error_message
```

### Deliverables

- `POST /api/v1/jobs` — create job
- `GET /api/v1/jobs/{id}` — fetch job details
- `PUT /api/v1/jobs/{id}/pause` — pause job
- `DELETE /api/v1/jobs/{id}` — delete job
- `GET /api/v1/jobs/{id}/executions` — execution history
- PostgreSQL schema with Flyway migrations
- Docker Compose: `scheduler + postgres`
- Postman collection for manual testing

### Risks

- No fault tolerance: single node failure = missed triggers
- No distributed locking: running two instances causes duplicate execution
- No retry: failed jobs require manual intervention
- In-memory execution history lost on restart

### Complexity: Low

---

## Phase 1: V1 — Production-Ready (Weeks 5–12) — 2–3 Engineers

### Goal

A fault-tolerant scheduler with distributed locking, persistent execution history, HTTP callback retries, and basic observability. Can run 2+ scheduler instances without double execution. Handles 10K registered jobs with 1K active per hour.

### Features Added

- Distributed locking via Redis (`SETNX` / Redisson) — prevents duplicate execution across scheduler instances
- Retry policy: exponential backoff with jitter (configurable per job: max attempts, delay, backoff factor)
- Dead-letter job queue: jobs that exhaust retries → status `FAILED_PERMANENT`
- Execution timeout enforcement (HTTP callback must respond within configurable TTL)
- Parameterized jobs (runtime arguments stored as JSON, passed to callback)
- Job priority (P0–P4): higher priority jobs polled first
- Persistent execution history in PostgreSQL (partitioned by month)
- Basic observability: Prometheus metrics (`job_executions_total`, `job_latency_seconds`, `lock_acquire_failures`)
- Health and readiness endpoints for Kubernetes
- JWT authentication on the management API

### Architecture

```
Client → Load Balancer → Spring Boot API (2 instances)
                               ↓
                    PostgreSQL (Primary + Read Replica)
                         ↑           ↓
              Scheduler Engine   Execution History
              (polls every 1s,  (partitioned table)
               acquires Redis
               lock before fire)
                    ↓
              Redis (Distributed Lock + Job Params Cache)
                    ↓
              HTTP Callback → Target Service
                    ↓
              Retry Handler (Spring Retry)
```

### Infrastructure

- EKS or plain EC2 with 2 Scheduler pods + 2 API pods
- RDS PostgreSQL Multi-AZ (db.t3.medium)
- ElastiCache Redis Single-AZ (cache.t3.medium) with Sentinel for failover
- Basic Prometheus + Grafana (single instance, no HA)
- GitHub Actions CI/CD → Docker → ECR → ECS or EKS

### New Modules

- `scheduler` module: polling engine, cron evaluation, lock lifecycle
- `execution` module: job dispatch, callback invocation, result processing
- `retry` module: backoff calculation, dead-letter handling
- `identity` module: JWT auth, API key management

### Deliverables

- Full REST API per [04-api-design.md](04-api-design.md)
- Flyway migrations for all Phase 1 tables
- Prometheus metrics + Grafana dashboard: execution rate, retry rate, failure rate
- Alerting: lock acquisition failure spike, job lag > 10s
- CI/CD pipeline with staging and prod environments
- Load test results: 1,000 jobs/minute execution verified

### Architecture Evolution from MVP

- Added Redis distributed locking (multi-instance safe)
- PostgreSQL partitioned execution history table
- JWT auth layer on API
- Retry framework with dead-letter support
- Kubernetes-ready: readiness/liveness probes, graceful shutdown hook

### Risks

- Redis single-AZ: if Redis goes down, locking breaks → duplicate execution possible
- Polling frequency (1s) can spike PostgreSQL read load at scale
- HTTP callback failures: target service SLA impacts scheduler retry load

### Complexity: Medium

---

## Phase 2: V2 — Scale (Months 4–9) — 4–6 Engineers

### Goal

Decouple job dispatch from execution via Kafka. Support 100K concurrent executions. Add worker pool management, DAG-based job chaining, KEDA-driven autoscaling, and multi-tenancy. Target: 10M registered jobs, 1M active, 100K concurrent.

### Features Added

- Kafka-based dispatch: scheduler publishes to `job-dispatch` topic; workers consume
- Worker pool management: workers register in Redis with capability metadata, heartbeat TTL
- DAG job chaining: jobs can declare dependencies; execution waits on upstream completion
- KEDA autoscaling: worker pods scale on Kafka `job-dispatch` consumer lag metric
- Execution timeout enforcement via Kafka message TTL + dedicated timeout scanner
- Concurrent execution limit per job type (configurable `max_concurrency`)
- Multi-tenancy: namespace isolation, per-tenant quotas (`max_concurrent_jobs`, `max_jobs_per_hour`)
- Tenant-scoped RBAC: roles (viewer, operator, admin) enforced per namespace
- Job output log streaming: workers publish stdout/stderr chunks to S3, linked from execution record
- Execution history read path via Elasticsearch (offloaded from PostgreSQL read replica)
- Bulk job registration API (async CSV/JSON import)
- Webhook delivery on job completion / failure

### Architecture

```
Client → API Gateway (Spring Boot)
              ↓
         Job Store (PostgreSQL — Primary)
              ↓
         Scheduler Engine
         (polls next_execution_time index, acquires Redis lock)
              ↓
    Kafka Topic: job-dispatch (32 partitions)
         ↓              ↓              ↓
  Worker Pod 1   Worker Pod 2   ... Worker Pod N
  (KEDA HPA based on consumer lag)
         ↓
  Result: Kafka Topic: job-results
         ↓
  Result Processor → PostgreSQL (execution history)
                   → Elasticsearch (search/query)
                   → Webhook Dispatcher

Redis:
  - Distributed locks (per job_id, fencing token)
  - Worker registry (heartbeat TTL keys)
  - Per-tenant quota counters
  - Hot job params cache

S3:
  - Execution output logs (streamed by workers)
  - Execution history archive (>90 days, Parquet via Spark/Athena)
```

### Infrastructure Evolution

- EKS cluster: dedicated node groups for API, Scheduler, Workers
- Kafka: MSK (2–4 brokers, 32 partitions for `job-dispatch`)
- KEDA: Kafka consumer lag metric → worker pod HPA
- Elasticsearch: 4–6 data nodes for execution history search
- S3 + Lifecycle policies: archive execution rows > 90 days to Parquet
- Multi-AZ Redis Cluster (3 shards, 3 replicas)
- OpenTelemetry → Jaeger for distributed tracing across scheduler → Kafka → worker

### New Modules / Services Extracted

- `worker-registry` module: manages worker lifecycle, capability index, heartbeat
- `dag-engine` module: dependency graph evaluation, upstream completion tracking
- `tenant` module: namespace management, quota enforcement, RBAC
- `webhook` module: fan-out delivery with retry on job lifecycle events

### Architecture Tradeoffs at V2

| Decision | Why | Tradeoff |
|---|---|---|
| Kafka for dispatch | Decouples scheduler from workers; enables KEDA | Adds Kafka ops burden |
| Elasticsearch for history | Execution history queries at 10K RPS | Eventual consistency; requires CDC sync |
| KEDA autoscaling | Handles midnight cron spike without pre-warming | Requires Prometheus + KEDA operator in K8s |
| Multi-tenant quotas via Redis | Zero-latency quota check per execution | Redis counter can drift under partitions |
| DAG engine in-process | Avoids distributed coordination overhead | Complex to extract later; state in PostgreSQL |

### Team Scaling

- 2 Backend engineers: core scheduler + execution features
- 1 Platform/SRE engineer: Kafka, KEDA, EKS, monitoring
- 1 Backend engineer: multi-tenancy, RBAC, webhook delivery

### Risks

- Kafka consumer group rebalancing during worker scale events can delay job starts by 10–30s
- DAG execution can deadlock if dependency graph has cycles (must validate at registration time)
- Multi-tenant quota enforcement under Redis partition: counters may under-count → quota breach

### Complexity: High

---

## Phase 3: V3 — Enterprise Scale (Months 10–18) — 8–12 Engineers

### Goal

Multi-region active-passive with < 30s failover. Support 10M registered jobs, 1M active per hour, 100K concurrent executions. ML-based pre-warming for cron spikes. Self-service tenant provisioning. SLA reporting and audit compliance.

### Features Added

- Multi-region active-passive: primary region (US) + warm standby (EU)
- Failover: Patroni/PgBouncer auto-promotes PostgreSQL standby in EU within 30s
- Regional Kafka replication via MirrorMaker2 (EU consumers can pick up from regional replica)
- ML-based spike prediction: trains on historical cron patterns, pre-warms worker pool 5 min before predicted burst
- Execution marketplace: pluggable executor types (HTTP callback, gRPC, container, shell)
- Self-service tenant provisioning: API for tenant onboarding, quota management, billing hooks
- Immutable audit log: execution history archived to S3 with Object Lock (WORM) for compliance
- SLA reporting: per-tenant P99 scheduling latency, execution success rate, missed trigger count
- Priority-aware queue partitioning: Kafka topic per priority tier (`dispatch-p0`, `dispatch-p1`, etc.)
- Cross-datacenter job routing: route specific job types to nearest datacenter by worker capability tags

### Architecture

```
Global Traffic
    ↓
Route 53 (active-passive health check failover)
    ↓
Primary Region (US-East):
  API Gateway (EKS, 10+ pods)
  Scheduler Engine (EKS, 5+ pods, leader-elected via Redis)
  PostgreSQL Primary (RDS, Multi-AZ)
  Kafka Cluster (MSK, 8 brokers)
  Worker Pool (EKS, 1,000+ pods via KEDA)
  Elasticsearch Cluster (8 data nodes)
  Redis Cluster (6 shards)

Standby Region (EU-West):
  API Gateway (read-only, rejecting writes during standby)
  PostgreSQL Standby (streaming replication from US)
  Kafka MirrorMaker2 (replicates job-results, job-events)
  Worker Pool (scaled to 10% capacity, warms up on failover)
  Redis (replicates lock state from primary via active replication)

Shared:
  S3 (audit logs, execution output, archived history — cross-region replication)
  CloudFront (API documentation, static assets)
  AWS Route 53 Health Checks (automatic DNS failover)
```

### Infrastructure Evolution

- Patroni for PostgreSQL HA with automated failover
- Kafka MirrorMaker2 for cross-region topic replication
- KEDA + Karpenter for node-level autoscaling (not just pod-level)
- ML spike predictor: simple ARIMA model trained daily on execution history, published as prediction config
- Terraform modules per region (DRY infrastructure as code)
- Zero-trust: Istio service mesh, mTLS between all internal services
- Secret rotation: AWS Secrets Manager with automatic rotation for DB credentials

### Services Extracted from Monolith by V3

| Service | Reason for Extraction |
|---|---|
| `scheduler-service` | Independent scaling, isolated failure domain |
| `worker-manager-service` | Manages cross-datacenter worker pool, capability index |
| `execution-history-service` | Read-heavy, needs dedicated Elasticsearch + Athena backend |
| `tenant-service` | Self-service provisioning, billing integration, isolated deploy cadence |
| `audit-service` | Compliance-critical, separate data store, immutable write path |

### Team Scaling

- 2 Backend teams: Scheduling & Execution (4 engineers) + Platform & Multi-tenant (3 engineers)
- 1 SRE team: multi-region reliability, Kafka, Kubernetes (2–3 engineers)
- 1 ML engineer: spike prediction, auto-scaling optimization
- Total: 10–12 engineers

### Complexity: Very High

---

## Implementation Principles Across All Phases

| Principle | Application |
|---|---|
| Idempotent workers | Every job execution must be safe to retry; fencing tokens prevent stale result writes |
| Observability from day 1 | Prometheus metrics on every scheduler poll cycle from MVP |
| Database migrations first | Flyway migrations checked in before any code referencing new columns |
| Graceful shutdown | Scheduler stops polling; in-flight executions complete; Kafka consumer commits offset |
| Failure contract explicit | Every job definition declares: max_retries, timeout, on_failure behavior |
| Clock monotonicity | Use wall clock for scheduling; use monotonic clock for execution duration measurement |
| No silent failures | Every failed job dispatch writes to DLQ with reason; DLQ size is alerted on |

---

## Interview Discussion Points

- **What do you build first in MVP?** The cron evaluation loop + PostgreSQL lock (advisory lock). Everything else is additive. A scheduler that double-fires is worse than one with no UI.
- **When do you add Kafka?** At V2, when worker count exceeds what a single-threaded dispatch loop can feed (> 1,000 concurrent executions). Before that, direct HTTP dispatch is simpler.
- **What breaks first at 10x load?** PostgreSQL `next_execution_time` index scan. At 10M jobs, the scheduler polls an index over 10M rows every second — becomes I/O bound. Fix: partition jobs table by `next_execution_time` range + bloom filter for hot window.
- **How do you handle the midnight spike without Kafka?** You can't — synchronous dispatch has bounded throughput. Kafka + KEDA is the right answer at scale.
- **What's the hardest distributed systems problem here?** The fencing token problem: a slow worker holds an execution lock, times out, scheduler re-dispatches, now two workers are running the same job. Solve with monotonically increasing fencing tokens and worker self-checks before writing results.
