# 02 — Domain Modeling: Distributed Job Scheduler

## Objective
Define the rich domain model — aggregates, entities, value objects, domain events, and invariants — that captures the core scheduling domain accurately and makes business rules enforceable at the model level.

---

## 1. Domain Overview

The scheduling domain involves four primary concerns:
1. **What to run** — the Job definition with its execution logic and parameters
2. **When to run it** — the Schedule with triggers and timing rules
3. **How it ran** — the Execution with lifecycle, result, and retry state
4. **Who ran it** — the Worker and its capacity, status, and routing capabilities

These concerns map to four bounded contexts with distinct aggregates.

---

## 2. Aggregates

### 2.1 Job Aggregate (Root: Job)

The `Job` aggregate owns the definition and scheduling configuration. It is the central aggregate of the system.

```
Job (Aggregate Root)
├── JobId (Value Object — UUID)
├── JobName (Value Object — non-empty string, unique within namespace)
├── Namespace (Value Object — tenant scoping)
├── JobType (Value Object — HTTP | SHELL | GRPC | CUSTOM)
├── ExecutionConfig (Value Object)
│   ├── timeoutSeconds: int
│   ├── maxConcurrent: int
│   ├── retryPolicy: RetryPolicy (Value Object)
│   └── exclusiveLock: boolean
├── JobParameters (Value Object — immutable key-value map)
├── Schedule (Entity — owned by Job)
├── JobStatus (Enum — ACTIVE | PAUSED | DELETED | ARCHIVED)
├── Priority (Value Object — P0 to P4)
├── JobGroup (Value Object — logical grouping for routing)
├── CreatedBy (Value Object — UserId)
├── CreatedAt: Instant
├── UpdatedAt: Instant
└── Version: long (optimistic lock)
```

**Invariants on Job:**
- A `DELETED` job cannot be re-activated
- `maxConcurrent` must be ≥ 1
- `timeoutSeconds` must be ≤ max system timeout (configurable, default 24h)
- `JobName` must be unique within `Namespace`
- A `PAUSED` job does not trigger executions but retains its Schedule

---

### 2.2 Schedule Entity (owned by Job Aggregate)

```
Schedule (Entity)
├── ScheduleId (Value Object — UUID)
├── ScheduleType (Enum — CRON | INTERVAL | ONE_TIME | MANUAL)
├── CronExpression (Value Object — validated 5 or 6 field cron string)
├── Interval (Value Object — duration in seconds, nullable)
├── FireAt (Instant — nullable, for ONE_TIME)
├── Timezone (Value Object — IANA timezone string)
├── StartAt (Instant — nullable, effective date)
├── EndAt (Instant — nullable, expiry date)
├── NextExecutionTime (Instant — computed, stored for polling)
├── LastExecutionTime (Instant — updated on dispatch)
└── MisfirePolicy (Enum — FIRE_ONCE | FIRE_ALL | IGNORE)
```

**Invariants on Schedule:**
- `CronExpression` must pass syntax validation at creation time
- `NextExecutionTime` is always pre-computed and stored; it is never calculated on read
- `MisfirePolicy.FIRE_ALL` is only allowed for CRON schedules
- For ONE_TIME schedules: `FireAt` must be in the future at creation time

**Key Design Decision — Pre-computed NextExecutionTime:**
Rather than computing the next run on every scheduler poll, `NextExecutionTime` is pre-computed whenever the schedule changes or a job fires. The scheduler's polling query becomes a simple indexed range scan: `WHERE next_execution_time <= now() AND status = 'ACTIVE'`.

---

### 2.3 JobExecution Aggregate (Root: JobExecution)

Each execution of a Job is its own aggregate. This allows independent lifecycle management and avoids bloating the Job aggregate.

```
JobExecution (Aggregate Root)
├── ExecutionId (Value Object — UUID)
├── JobId (Value Object — foreign reference, NOT entity)
├── TriggerType (Enum — SCHEDULED | MANUAL | DEPENDENCY | RETRY)
├── TriggeredAt (Instant)
├── ScheduledFor (Instant — original trigger time, for latency tracking)
├── StartedAt (Instant — nullable)
├── CompletedAt (Instant — nullable)
├── ExecutionStatus (Enum — QUEUED | EXECUTING | COMPLETED | FAILED | TIMED_OUT | CANCELLED | RETRYING)
├── WorkerId (Value Object — nullable until assigned)
├── AttemptNumber (Value Object — 1-indexed)
├── RetryOfExecutionId (Value Object — nullable, links to parent execution)
├── ExecutionContext (Value Object — runtime params, fencing token)
├── Result (ExecutionResult Value Object — nullable)
│   ├── exitCode: int
│   ├── outputSummary: String (≤1KB)
│   └── logReference: URI (S3/GCS path to full log)
├── FailureReason (Value Object — nullable)
└── FencingToken (Value Object — monotonically increasing, per-job)
```

**Invariants on JobExecution:**
- `ExecutionStatus` transitions are strictly ordered: QUEUED → EXECUTING → (COMPLETED | FAILED | TIMED_OUT | CANCELLED | RETRYING)
- A transition from COMPLETED or CANCELLED is terminal — no further transitions allowed
- `AttemptNumber` is always ≥ 1
- `FencingToken` must exceed all previously issued tokens for the same Job (prevents stale worker writes)

**Why separate aggregate?**
Execution history can reach billions of rows. As a separate aggregate, it can be partitioned, archived, and queried independently of the Job definition. It also allows the Job aggregate to remain small and frequently cached.

---

### 2.4 Worker Aggregate (Root: Worker)

```
Worker (Aggregate Root)
├── WorkerId (Value Object — UUID, stable across restarts within a K8s pod lifecycle)
├── WorkerHostname (Value Object — pod name in K8s)
├── WorkerType (Value Object — matches JobType — HTTP | SHELL | GRPC | CUSTOM)
├── Capabilities (Value Object — Set of tags for routing, e.g., "gpu", "high-memory")
├── WorkerStatus (Enum — STARTING | ACTIVE | DRAINING | OFFLINE)
├── CurrentLoad (Value Object — count of in-flight executions)
├── MaxConcurrency (Value Object — configured max)
├── LastHeartbeatAt (Instant)
├── RegisteredAt (Instant)
└── Zone (Value Object — availability zone for locality-aware routing)
```

**Invariants on Worker:**
- `CurrentLoad` must be ≤ `MaxConcurrency`
- A `DRAINING` worker accepts no new jobs but completes in-flight ones
- `LastHeartbeatAt` older than 30s → system marks worker `OFFLINE`

---

### 2.5 JobDependency (Value Object within Job Aggregate)

For DAG execution:

```
JobDependency (Value Object)
├── DependsOnJobId (Value Object — UUID)
├── DependencyType (Enum — SUCCESS | COMPLETION | CUSTOM_CONDITION)
└── TimeWindowSeconds (int — max wait time for dependency to resolve)
```

**Design Note:** Full DAG management (building the execution graph, checking readiness) lives in the Execution bounded context's domain service, not on the Job aggregate directly. The Job aggregate only holds its declared dependencies as a list of value objects.

---

## 3. Value Objects

| Value Object | Description | Validation Rule |
|---|---|---|
| `JobId` | UUID, stable identifier | Must be valid UUID v4 |
| `Namespace` | Tenant/org scoping key | Alphanumeric, max 64 chars |
| `CronExpression` | Validated cron string | Passes CronUtils parse |
| `RetryPolicy` | maxAttempts, backoffType, initialDelay, multiplier, maxDelay, jitter | maxAttempts ≥ 1, delays > 0 |
| `Priority` | P0 (highest) to P4 (lowest) | Enum |
| `FencingToken` | Monotonic counter per Job | Stored in job.fence_token_seq, incremented on each dispatch |
| `ExecutionContext` | Merged params at trigger time | Immutable snapshot at dispatch |

---

## 4. Domain Events

Domain events are emitted by aggregate methods and consumed internally or published to Kafka.

| Event | Emitted By | Payload |
|---|---|---|
| `JobRegistered` | Job.register() | jobId, namespace, schedule, createdBy |
| `JobUpdated` | Job.update() | jobId, changedFields, updatedBy |
| `JobPaused` | Job.pause() | jobId, pausedBy, reason |
| `JobResumed` | Job.resume() | jobId, resumedBy |
| `JobDeleted` | Job.delete() | jobId, deletedBy |
| `JobTriggered` | SchedulerEngine (domain service) | jobId, executionId, scheduledFor, fencingToken |
| `ExecutionQueued` | JobExecution.queue() | executionId, jobId, jobType, priority |
| `ExecutionStarted` | JobExecution.start() | executionId, workerId, startedAt |
| `ExecutionCompleted` | JobExecution.complete() | executionId, result, duration |
| `ExecutionFailed` | JobExecution.fail() | executionId, reason, attemptNumber |
| `ExecutionRetrying` | JobExecution.scheduleRetry() | executionId, newExecutionId, nextAttemptAt, attemptNumber |
| `ExecutionTimedOut` | TimeoutMonitor (domain service) | executionId, timeoutAt |
| `WorkerRegistered` | Worker.register() | workerId, type, capabilities, zone |
| `WorkerOffline` | Worker.markOffline() | workerId, lastHeartbeatAt |
| `DLQEnqueued` | RetryExhausted handler | executionId, jobId, failureReason, attemptCount |

---

## 5. Domain Services

### SchedulerEngineService
- Polls for due jobs (infrastructure concern, calls Job repository)
- Acquires distributed lock via LockManager
- Computes next execution time and persists
- Creates `JobExecution` and publishes `ExecutionQueued` event
- Handles misfire policies

### RetryOrchestrationService
- Receives `ExecutionFailed` events
- Evaluates retry policy (attempts exhausted? backoff expired?)
- Creates new `JobExecution` with incremented `AttemptNumber`
- Or publishes to DLQ if max retries exceeded

### DAGResolutionService
- Listens for `ExecutionCompleted` / `ExecutionFailed` events
- Checks if downstream jobs' dependency conditions are satisfied
- Triggers downstream `JobExecution` when conditions met

### WorkerRoutingService
- Assigns incoming `ExecutionQueued` to appropriate Kafka partition based on job type, priority, and worker capabilities
- Not a runtime call — routing is encoded in Kafka partition key strategy

---

## 6. Aggregate Invariant Table

| Aggregate | Invariant | Enforcement |
|---|---|---|
| Job | Status cannot move from DELETED | Status transition method guards |
| Job | NextExecutionTime always pre-computed | Schedule.computeNext() called in every update |
| JobExecution | Terminal states are final | State machine in JobExecution.transition() |
| JobExecution | FencingToken monotonically increases | Sequence in DB, checked in worker result handler |
| Worker | CurrentLoad ≤ MaxConcurrency | Checked before job assignment |
| Worker | OFFLINE workers not routed | WorkerRegistry filters OFFLINE entries |

---

## Interview Discussion Points

**Q: Why does JobExecution have a FencingToken?**
A: A worker may go into a GC pause mid-execution and the lock expires. Another worker picks up the same job. When the first worker wakes up and tries to write its result, the system detects its fencing token is stale (lower than the current token) and rejects the write. This prevents split-brain execution results.

**Q: Why is Schedule an entity inside Job rather than its own aggregate?**
A: Schedule has no independent lifecycle — it is always created, updated, and deleted with the Job. Making it a separate aggregate would require cross-aggregate transactions for every job configuration change, adding unnecessary complexity. At scale, if schedules need to be shared across jobs, this can be refactored.

**Q: How does the DAG work — does Job aggregate know about its dependents?**
A: No. A Job only knows its dependencies (what it waits for), not its dependents (who waits for it). The DAG resolution service maintains the inverse graph. This keeps the Job aggregate focused and avoids circular references.
