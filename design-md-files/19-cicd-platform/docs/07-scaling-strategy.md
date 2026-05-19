# 07 — Scaling Strategy: CI/CD Platform

---

## Objective

Define horizontal and vertical scaling strategies for runner pools, control plane services, database tiers, and log/artifact infrastructure. Identify bottlenecks and the mechanisms to handle traffic growth.

---

## Scaling Dimensions

| Component | Scaling Unit | Mechanism |
|---|---|---|
| Runner pool | Pod (Kubernetes HPA) | Scale by job queue depth |
| Webhook Service | Pod | Scale by webhook RPS |
| Scheduler | Pod (active-passive) | Leader election, not horizontal |
| Log Service | Pod | Scale by concurrent SSE connections |
| Secret Service | Pod | Scale by RPS (read-heavy) |
| Artifact Service | Pod | Scale by concurrent uploads |
| PostgreSQL | Vertical + read replicas | Read replicas for status queries |
| Kafka | Brokers + partitions | Standard Kafka scaling |
| Redis (log buffer) | Redis Cluster | Horizontal sharding by jobId |
| S3 / object storage | Infinite | Managed by cloud provider |

---

## Runner Pool Autoscaling

The runner pool is the most dynamic scaling component — it must scale from 0 to 10,000 pods based on demand.

### Kubernetes HPA Configuration

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: runner-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: runner-pool
  minReplicas: 10          # always-warm base pool
  maxReplicas: 10000
  metrics:
  - type: External
    external:
      metric:
        name: kafka_consumer_group_lag  # job queue depth
        selector:
          matchLabels:
            group: runner-consumer
            topic: job-queue
      target:
        type: AverageValue
        averageValue: "5"  # target 5 queued jobs per runner (scale out faster)
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 60
      policies:
      - type: Pods
        value: 100
        periodSeconds: 60  # add up to 100 pods per minute
    scaleDown:
      stabilizationWindowSeconds: 300  # wait 5 min before scaling down
      policies:
      - type: Pods
        value: 20
        periodSeconds: 60  # remove up to 20 pods per minute
```

**Pre-warm pool:** Keep minimum 10 runners idle. Cold starts (container pull + pod scheduling) take 30–60 seconds. Pre-warm avoids first-job latency spike.

**Scale-down lag:** 5-minute stabilization window before scale-down. Jobs typically < 10 minutes — don't scale down immediately after a burst ends.

### Runner Lifecycle

```
Pod scheduled → runner agent starts → pulls job from queue → executes
→ job complete → agent idles → if no job in 5 min → pod terminates
```

**Idle pod management:** Runner agent polls queue for 5 minutes. If no job, self-terminates. HPA creates new pods when queue depth rises. This is more efficient than keeping 10,000 idle pods.

### Runner Pool Segmentation

```
Pool 1: ubuntu-22.04 (general purpose) — 8,000 runners max
Pool 2: ubuntu-22.04-large (4 CPU, 8 GB) — 1,000 runners max
Pool 3: self-hosted (org-owned) — user-managed
Pool 4: gpu-runner — 100 runners max
```

Each pool has its own Kafka consumer group on a dedicated `job-queue-{label}` partition set. HPA per pool.

---

## Control Plane Scaling

### Webhook Service
- Stateless — scale horizontally with deployment replicas
- Each instance handles ~1000 webhook validations/sec (CPU-bound for HMAC)
- At 120 webhooks/sec peak: 3 pods comfortably handle load
- Scale trigger: CPU > 70%

### Scheduler — Single Leader with Standby

**Why not horizontal Scheduler?** Scheduler owns global state (job dispatch decisions, cron timing, concurrency enforcement). Concurrent schedulers would create race conditions for job dispatch and org concurrency limits.

**Leader election (PostgreSQL advisory locks):**
```sql
SELECT pg_try_advisory_lock(12345678);  -- constant for "scheduler"
-- Returns TRUE if this instance got the lock
-- Lock released automatically on connection close
```

Primary scheduler holds lock. Standby instances poll every 5 seconds. On primary failure: lock released, standby acquires lock within 5 seconds.

**Scalability concern:** Single scheduler becomes bottleneck at very high job throughput. Mitigation: shard by `orgId` (each org's jobs handled by a dedicated scheduler shard). At 10M jobs/day, single scheduler handles ~120 jobs/sec — well within a single process's capacity.

### Log Service Scaling

Log Service scales by concurrent SSE connections (not CPU/memory):

```
Typical: 10K concurrent log streams (users watching running jobs)
Per pod: 500 concurrent SSE connections
Required pods: 20 min; 50 at peak
```

Scale trigger: concurrent connection count > 400 per pod.

Redis Pub/Sub: each pod subscribes to relevant `log:pubsub:{jobId}` channels. At 10K concurrent jobs, 10K Redis subscriptions — within Redis single-node limits (1M+ subscriptions supported).

---

## Database Scaling

### PostgreSQL Vertical + Read Replicas

**Phase 1 (MVP):** Single PostgreSQL instance (primary)
- Handles write throughput (job state transitions): ~600 writes/sec at 10M jobs/day
- Single-node limit: ~5K writes/sec — safe headroom

**Phase 2:** Primary + 2 read replicas
- Status API, run list queries → read replicas
- Scheduler, job state transitions → primary only
- Replication lag: < 50ms — acceptable for non-critical reads

**Phase 3 (> 50M jobs/day):** Citus sharding by org_id
- Hash-distributed: `org_id % num_shards`
- Cross-shard queries (admin reports): coordinator node aggregates
- Migration: gradual — add Citus extension, start routing new orgs to distributed setup

### Connection Pooling (PgBouncer)

```
Services → PgBouncer (transaction-mode pooling) → PostgreSQL
Pool size: 100 connections per PgBouncer instance
Services: 10 replicas × 20 connections = 200 connections pooled to 100
```

Transaction-mode pooling: connection returned to pool after each transaction. Supports 10x more concurrent clients than PostgreSQL max_connections.

---

## Kafka Scaling

### Job Queue Throughput

```
Peak: 600 jobs dispatched/sec
Message size: ~2 KB (JobDispatchEvent with YAML reference)
Throughput: 600 × 2 KB = 1.2 MB/sec — trivial for Kafka
```

**Partition count for job queue topics:**
- `job-queue-ubuntu-22.04`: 96 partitions (supports 96 concurrent consumers polling simultaneously)
- `pipeline-triggers`: 24 partitions
- `job-status`: 48 partitions

**Why 96 partitions for job queue?** Runner pool can scale to 10,000 pods but they're not all polling simultaneously. 96 concurrent pollers at any instant is realistic (long-poll with 30s timeout).

---

## Log Storage Scaling

### Redis Cluster for Log Buffer

At 10,000 concurrent jobs, Redis handles:
- 10K concurrent RPUSH operations (log append)
- 10K concurrent SUBSCRIBE channels (SSE consumers)
- Memory: 10K jobs × 5000 lines × 200 bytes = ~10 GB — within single Redis capacity

**Redis Cluster sharding:** Key `log:stream:{jobId}` sharded by `{jobId}` hash slot. 6-node Redis Cluster (3 primary, 3 replica).

### S3 Log Storage

- Write: 1M jobs/day × 2 MB/job = 2 TB/day — S3 handles unlimited PUT rate
- Read: log replay requests — S3 presigned URL per request, served by S3 CDN
- S3 Select: filter log lines server-side for search queries (avoid full download)

---

## Multi-Region Scaling

### Why Multi-Region?
- Webhook latency: GitHub → single-region platform adds roundtrip vs regional webhook handler
- Runner proximity: runners can be closer to artifact registries (docker.io, npm)
- Compliance: EU orgs may require data residency (logs, secrets) in EU

### Architecture

```
Region US-EAST (primary)
├── Control plane (full)
├── PostgreSQL primary
├── Kafka cluster
├── Runner pool (US orgs)
└── S3: us-east-1

Region EU-WEST (active)
├── Webhook receiver → forward to US-EAST control plane
├── Runner pool (EU orgs)
├── S3: eu-west-1 (separate artifact/log storage)
└── Read replica of PostgreSQL primary

For full EU data residency:
├── Separate control plane per region
├── Cross-region org routing at API Gateway
```

---

## Hot Job Problem

### Cause
Some organizations run extremely high volumes — monorepo CI triggers 1000 jobs per commit. With concurrency limits, these orgs create deep queues that affect tail latency for all orgs.

### Detection
```
kafka_consumer_group_lag per org_id partition
→ org X has 5000 queued jobs while org Y has 0
```

### Mitigation

**Fair-share scheduling:**
- Job queue partitioned by org_id (not just by runner label)
- Round-robin across org partitions: serve 1 job from org A, 1 from org B, 1 from org C
- Ensures high-volume orgs don't starve others

**Concurrency caps:**
- FREE orgs: max 5 concurrent jobs
- PRO orgs: max 50 concurrent jobs
- ENTERPRISE: negotiated (up to 10,000)
- Scheduler enforces via atomic counter in Redis: `INCR cicd:concurrency:{orgId}` — reject if > limit

---

## Rate Limiting

### Webhook Ingestion

```
Per-repository: 1000 webhooks/minute (token bucket)
Per-organization: 5000 webhooks/minute (token bucket)
Global: 100,000 webhooks/minute (platform-level)
```

Implementation: Redis token bucket per `{repoId}`. Key expires after inactivity.

### API Rate Limiting

```
GET endpoints: 1000 req/min per token
POST (trigger): 100 req/min per org
Log streaming connections: 50 concurrent per user
Secret writes: 100 req/min per org
```

API Gateway (Nginx/Kong) enforces rate limits before requests reach services.

---

## Performance Bottlenecks

| Bottleneck | Trigger | Solution |
|---|---|---|
| Scheduler single-thread | > 1000 jobs/sec dispatch rate | Shard by org_id across scheduler instances |
| PostgreSQL connection exhaustion | 500+ service pods × 20 conns | PgBouncer connection pooling |
| Kafka consumer lag (job queue) | Runner scale-up lags queue growth | Pre-warm base runner pool; aggressive HPA scale-up |
| Redis memory (log buffer) | > 50K concurrent jobs | Redis Cluster; reduce buffer from 5K to 1K lines |
| S3 PUT throughput (logs) | > 100K concurrent log uploads | S3 supports unlimited PUT — not a bottleneck |
| Job start latency (container pull) | Large runner image (5 GB+) | Pre-pull images; layer caching on runner nodes |

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Single leader Scheduler | Avoid dispatch races | Failover latency ~5s; single point of throughput |
| Runner pool pre-warm | < 30s job start latency | Idle pods cost money (minimum 10 always running) |
| Fair-share scheduling | Multi-tenant fairness | Implementation complexity; round-robin adds dispatch overhead |
| Redis for concurrency counters | Atomic INCR sub-ms | Redis dependency for critical concurrency enforcement |

---

## Interview Discussion Points

- **How do you scale to 10x traffic overnight?** Pre-provisioned runner pool scales automatically via HPA (minutes). Control plane is stateless — add pods in seconds. PostgreSQL read replicas handle read spike. Main bottleneck: Scheduler dispatch rate — pre-shard by org for high-scale events
- **What is the maximum number of concurrent jobs?** Constrained by: runner pool size (K8s node capacity), scheduler dispatch throughput, PostgreSQL write rate. Technical ceiling: ~100K concurrent jobs (10K pods × 10 jobs each with job-level containers). Practical: 10K with current node pool sizing
- **How do you handle a viral open-source project that gets 1000 PRs simultaneously?** Concurrency limits per org prevent queue explosion. PRO org: 50 concurrent jobs → 950 in queue, served as runners free up. With runner autoscaling: within 5-10 minutes, queue drains. Separate runner pool per org tier (ENTERPRISE gets priority)
- **Can the log service become a bottleneck?** At 10K concurrent jobs × 1000 log lines/sec = 10M lines/sec total. Each pod handles 500 SSE connections × 100 lines/sec = 50K lines/sec. Need 200 pods. Redis handles pub/sub at 1M ops/sec — not the bottleneck. Main risk: Redis memory for log buffers
