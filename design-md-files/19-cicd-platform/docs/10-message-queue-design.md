# 10 — Message Queue Design: CI/CD Platform

---

## Objective

Define the Kafka-based messaging architecture for pipeline trigger ingestion, job dispatch, status propagation, and notification delivery. Cover topic design, consumer groups, retry patterns, and dead-letter queues.

---

## Queue Architecture Overview

```
WebhookService ──→ [pipeline-triggers] ──→ Scheduler
                                              │
                         ┌────────────────────┤
                         ▼                    ▼
                  [job-queue-ubuntu]   [job-queue-large]  (per runner label)
                         │
                         ▼
                  Runner Pool ──→ [job-status] ──→ Scheduler
                                                │
                                                ▼
                                         [run-completed] ──→ Notification Service
                                                          ──→ PR Status Service
                                                          ──→ Webhook Delivery Service
                                         
                  [cache-invalidations] ──→ Cache Invalidation Service
                  [audit-events]        ──→ Audit Log Writer
```

---

## Topic Design

### `pipeline-triggers`

**Purpose:** Inbound pipeline trigger events from Webhook Service to Scheduler.

```
Partitions: 24
Replication factor: 3
Retention: 7 days
Key: {repoId}  → all triggers for a repo go to same partition (ordering per repo)
```

**Why order per repo?** If a repo triggers two pipelines rapidly, Scheduler processes them in order. Prevents race condition where concurrent run creations for the same branch conflict.

**Message:**
```json
{
  "eventId": "uuid",
  "repoId": "uuid",
  "orgId": "uuid",
  "triggerType": "PUSH",
  "branch": "main",
  "commitSha": "abc123",
  "deliveryId": "github-delivery-uuid"
}
```

---

### `job-queue-{label}` (multiple topics)

**Purpose:** Deliver job dispatch events to runner pool. Separate topic per runner label for independent scaling.

```
Topics:
  job-queue-ubuntu-22-04 (partitions: 96, retention: 1h)
  job-queue-ubuntu-22-04-large (partitions: 48, retention: 1h)
  job-queue-self-hosted (partitions: 24, retention: 1h)

Replication factor: 3
Key: {orgId}  → all jobs for an org go to same partition (fair share via round-robin across orgs)
```

**Short retention (1h):** Job events older than 1 hour are expired (the runner starting a 1-hour-old job is problematic — the run may have been cancelled). Scheduler re-queues jobs that weren't picked up.

**Message:**
```json
{
  "jobId": "uuid",
  "runId": "uuid",
  "orgId": "uuid",
  "workflowJobId": "build",
  "runsOn": "ubuntu-22.04",
  "commitSha": "abc123",
  "workflowYamlRef": "s3://cicd-pipelines/{orgId}/{repoId}/{sha}/ci.yml",
  "secretNames": ["AWS_KEY"],
  "jobToken": "jwt...",
  "timeoutMinutes": 30,
  "retryCount": 0,
  "dispatchedAt": "ISO8601"
}
```

---

### `job-status`

**Purpose:** Runner reports job and step status updates to Scheduler.

```
Partitions: 48
Replication factor: 3
Retention: 24 hours
Key: {jobId}  → all status events for a job in order
```

**Message types:**

```json
// Job started
{ "eventType": "JOB_STARTED", "jobId": "uuid", "runnerId": "uuid", "startedAt": "ISO8601" }

// Step completed
{ "eventType": "STEP_COMPLETED", "jobId": "uuid", "stepIndex": 2,
  "status": "SUCCEEDED", "exitCode": 0, "completedAt": "ISO8601" }

// Job completed
{ "eventType": "JOB_COMPLETED", "jobId": "uuid", "status": "SUCCEEDED",
  "steps": [...], "exitCode": 0, "completedAt": "ISO8601" }

// Heartbeat
{ "eventType": "HEARTBEAT", "jobId": "uuid", "runnerId": "uuid", "at": "ISO8601" }
```

---

### `run-completed`

**Purpose:** Fan-out notification when a pipeline run reaches terminal state.

```
Partitions: 12
Replication factor: 3
Retention: 7 days
Key: {orgId}
```

**Message:**
```json
{
  "runId": "uuid",
  "orgId": "uuid",
  "repoId": "uuid",
  "workflowName": "ci",
  "status": "FAILED",
  "branch": "main",
  "commitSha": "abc123",
  "durationSeconds": 245,
  "triggeredBy": "PUSH",
  "actor": "user123",
  "failedJobs": ["test"]
}
```

**Consumers:**
- Notification Service (Slack, email)
- PR Status Check Service (GitHub, GitLab)
- External Webhook Delivery Service (user-defined webhooks)
- Analytics/BI pipeline (job duration tracking)

Multiple consumer groups read independently — each gets the full event.

---

### `cache-invalidations`

**Purpose:** Propagate cache invalidation signals to Cache Service.

```
Partitions: 6
Retention: 5 minutes (short — invalidations are time-sensitive)
Key: {cacheKey}
```

```json
{ "pattern": "runs:list:{repoId}:*", "reason": "new_run_created", "at": "ISO8601" }
```

---

### `audit-events`

**Purpose:** Durable audit trail of security-relevant actions.

```
Partitions: 12
Replication factor: 3
Retention: 30 days (then archive to S3)
Key: {orgId}
Cleanup: log compaction disabled — retain full history
```

---

## Consumer Groups

| Topic | Consumer Group | Service | Consumers |
|---|---|---|---|
| `pipeline-triggers` | `scheduler-trigger-consumer` | Scheduler | 3 (matches Scheduler replicas) |
| `job-queue-ubuntu-22-04` | `runner-ubuntu-consumer` | Runner Pool | Scales with pool (up to 96) |
| `job-status` | `scheduler-status-consumer` | Scheduler | 3 |
| `run-completed` | `notification-consumer` | Notification Service | 5 |
| `run-completed` | `pr-status-consumer` | PR Status Service | 3 |
| `run-completed` | `webhook-delivery-consumer` | Webhook Delivery | 3 |
| `audit-events` | `audit-writer-consumer` | Audit Log Writer | 3 |

---

## Dead Letter Queue Pattern

### Job Dispatch DLQ

```
Primary topic: job-queue-ubuntu-22-04
DLQ topic: job-queue-ubuntu-22-04-dlq

When to DLQ a job:
  1. Job not picked up within 1 hour (topic retention expires) → Scheduler re-queues
  2. Runner fails to start job 3 times → DLQ
  3. Job token expired before runner start → DLQ (cannot fetch secrets)

DLQ message includes:
  Original message + failure metadata:
  {
    "originalMessage": {...},
    "failureReason": "TOKEN_EXPIRED",
    "failureCount": 3,
    "lastFailureAt": "ISO8601"
  }

DLQ processing:
  Separate consumer: reads DLQ, marks job as FAILED in DB with reason
  Sends user notification: "Job 'build' failed to start: token expired"
  Operator alert if DLQ depth > 100
```

### Webhook Delivery DLQ

External webhook deliveries (user-defined callbacks) can fail (endpoint down):

```
Primary: run-completed
Webhook Delivery Service:
  Try POST to user endpoint (timeout: 10s)
  Retry: 3 attempts with exponential backoff (1s, 4s, 16s)
  On all failures: write to webhook-deliveries-dlq
  
User can view failed deliveries in UI and manually redeliver
Auto-disable endpoint after 100 consecutive failures
```

---

## Retry Strategy

### Kafka Consumer Retry

**Do NOT retry in the consumer loop** — this blocks the partition, affecting all other messages.

**Correct pattern: Retry Topics**

```
job-queue-ubuntu-22-04         (immediate dispatch)
job-queue-ubuntu-22-04-retry-1 (retry after 30s delay)
job-queue-ubuntu-22-04-retry-2 (retry after 5min delay)
job-queue-ubuntu-22-04-dlq     (after all retries exhausted)

Consumer on primary topic:
  On failure → produce to retry-1 with retryAt = NOW() + 30s
  Consumer on retry-1 checks retryAt:
    if retryAt > NOW(): re-produce to retry-1 (not yet ready)
    if retryAt <= NOW(): process (or produce to retry-2 if fails again)
```

**Alternative: delay via Scheduler timer**
For job dispatch retries, Scheduler (not Kafka) manages the retry schedule:
```
Job fails → Scheduler records retry_count + 1
Scheduler creates DelayedRetry(jobId, retryAt = NOW() + exponential_backoff)
At retryAt: Scheduler re-dispatches to job-queue
```

This is simpler than Kafka retry topics for this use case.

---

## Backpressure Handling

### Runner Pool Saturation

```
Scenario: 100,000 jobs queued, only 10,000 runners
Kafka job-queue depth: 90,000 messages

Backpressure chain:
  1. HPA scales runner pool (up to 10,000 max)
  2. At max capacity: job-queue consumer lag grows
  3. Scheduler sees queue depth (via Kafka metrics) → stops creating new jobs from pending runs
     (pauses dispatch, runs remain PENDING in DB)
  4. As runners free up → Scheduler resumes dispatch

Webhook/trigger acceptance: continues regardless of backpressure
  (pipeline-triggers is separate from job-queue)
  New runs are created (status=PENDING) but jobs are not dispatched until runners available
```

### Org-Level Backpressure

```
Org with concurrency_limit=50 has 1000 queued jobs:
  Only 50 jobs ever in job-queue at once
  Scheduler holds 950 jobs in QUEUED status in DB
  As each job completes: Scheduler picks next from DB queue, dispatches to Kafka
  
Kafka job-queue size: proportional to concurrency_limit, not total queued jobs
→ Prevents one org from flooding job-queue and affecting others
```

---

## Ordering Guarantees

| Topic | Key | Ordering Need |
|---|---|---|
| `pipeline-triggers` | repoId | Order triggers per repo (last commit wins) |
| `job-queue-*` | orgId | Fair-share (no strict ordering needed) |
| `job-status` | jobId | Order status events per job (step 1 before step 2) |
| `run-completed` | orgId | No ordering needed (run IDs in message) |

**Why repoId key for pipeline-triggers?**
Rapid pushes to the same branch: commit A, then commit B. Scheduler must process A before B to correctly determine "latest run for branch". With same partition → guaranteed order.

---

## Message Size Limits

| Topic | Typical Size | Max Size | Handling |
|---|---|---|---|
| `pipeline-triggers` | 500 bytes | 10 KB | Inline payload |
| `job-queue` | 2 KB | 64 KB | YAML stored in S3; only S3 ref in message |
| `job-status` | 1 KB | 10 KB | Inline |
| `run-completed` | 500 bytes | 5 KB | Inline |
| `audit-events` | 1 KB | 50 KB | Inline |

**Why store workflow YAML in S3?** JobDispatchEvent would exceed Kafka's max message size (1 MB default) for large pipeline YAML files. Store YAML in S3 at trigger time, reference by URL in event. Runner fetches YAML from S3 directly.

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Separate topic per runner label | Independent scaling per pool; no cross-pool interference | More topics to manage; metadata overhead |
| orgId as job-queue key | Fair-share distribution across orgs | Within an org, jobs run in order (not fastest-first) |
| Scheduler-managed retry (not Kafka retry topics) | Simpler; Scheduler already owns job lifecycle | More Scheduler complexity |
| Short job-queue retention (1h) | Stale job dispatch is worse than no dispatch | Jobs not picked up in 1h must be re-queued by Scheduler |

---

## Interview Discussion Points

- **Why use Kafka instead of a simple database-backed queue?** At 600 jobs/sec dispatch rate, a DB-backed queue with polling would create 600 writes + 600 reads/sec for just queue management — high DB load. Kafka's purpose-built sequential log handles this efficiently with multiple consumer groups
- **How do you prevent a job from being dispatched to two runners simultaneously?** Kafka consumer group protocol: each partition message consumed by exactly one consumer. Within runner: compare-and-swap on DB (`UPDATE jobs SET status=RUNNING WHERE status=QUEUED`). Double protection
- **What happens if Kafka is down?** Webhook events buffered in Webhook Service (in-memory or local queue for short duration). Scheduler cannot dispatch. Running jobs continue (runners are autonomous). On Kafka recovery: buffered events replayed. Acceptable downtime window: < 5 minutes (RPO for trigger events)
- **How does fair-share scheduling work with Kafka?** org_id as partition key groups org's jobs to same partition. With 96 partitions and 1000 active orgs: ~10 orgs per partition. Within a partition: messages served in order = org's jobs interleaved. Not perfectly fair but close enough at scale
- **Should we use SQS instead of Kafka?** For MVP: yes. SQS is simpler, fully managed, at-least-once delivery, visibility timeout. Disadvantages: no consumer groups (can't have multiple notification consumers), no replay, no ordered delivery. At 600 jobs/sec: SQS is fine. At 10K jobs/sec: Kafka's throughput advantage matters
