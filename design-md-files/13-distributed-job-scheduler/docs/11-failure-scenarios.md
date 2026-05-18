# 11 — Failure Scenarios: Distributed Job Scheduler

## Objective
Enumerate realistic production failure scenarios, analyze their blast radius, define detection mechanisms, and provide concrete recovery runbooks for the distributed job scheduler.

---

## 1. Failure Scenario Index

| # | Scenario | Severity | Blast Radius |
|---|---|---|---|
| F-01 | Scheduler leader node crashes mid-cycle | HIGH | Jobs not dispatched until new leader elected |
| F-02 | Worker crashes mid-execution (orphaned job) | MEDIUM | Single job execution lost |
| F-03 | Database primary fails | CRITICAL | All writes blocked; read-only mode |
| F-04 | Redis cluster failure | HIGH | Lock acquisition fails; potential double dispatch |
| F-05 | Kafka broker failure | HIGH | Job dispatch queued; workers drain existing jobs |
| F-06 | Clock skew between scheduler nodes | MEDIUM | Jobs triggered early/late |
| F-07 | Job storm (thundering herd at midnight) | HIGH | Queue overflow; worker saturation |
| F-08 | DLQ overflow | MEDIUM | DLQ monitor falls behind; alert delay |
| F-09 | Outbox table accumulation | HIGH | Events delayed; jobs not dispatched |
| F-10 | DAG dependency deadlock | MEDIUM | Downstream jobs stuck indefinitely |
| F-11 | Long-running job never terminates | HIGH | Lock held indefinitely; job never re-triggers |
| F-12 | Split-brain (two scheduler leaders) | CRITICAL | Double dispatch of all jobs |

---

## 2. F-01: Scheduler Leader Node Crashes Mid-Cycle

### Scenario
The active scheduler leader crashes after acquiring row locks on due jobs (PostgreSQL `FOR UPDATE SKIP LOCKED`) but before writing to the outbox table.

### Impact
- Row locks are automatically released when the PostgreSQL connection closes (connection death releases locks)
- No outbox entries were written → no Kafka dispatch
- Jobs appear in the "due but not dispatched" window

### Detection
- Health check on leader node fails → Redis lock TTL expires (30s default)
- New leader is elected within 30s (Redis lock expiry + election time)
- New leader's first poll finds the same due jobs (they were never dispatched)

### Recovery
**Automatic:** New leader polls due jobs, dispatches them. Jobs missed their scheduled time by at most 30s (election time). Misfire policy `FIRE_ONCE` triggers them once; `IGNORE` skips them.

**No manual action required in most cases.**

**Edge case:** If the leader acquired the lock, updated `next_execution_time` in PostgreSQL (partial transaction), then crashed before committing → transaction rollback reverts `next_execution_time` → new leader sees the job as due.

### Runbook
```
1. Verify: Check scheduler leader metric (scheduler_is_leader)
2. Wait: Redis lock TTL expires (max 30s)
3. Confirm: New leader elected (check scheduler logs)
4. Verify: Jobs due during outage re-dispatched within 60s of new leader election
5. If NOT re-dispatched: query SELECT * FROM schedules WHERE next_execution_time < now() - INTERVAL '5 min' AND status = 'ACTIVE'
6. Manually trigger affected jobs if misfire policy = IGNORE and business impact is high
```

---

## 3. F-02: Worker Crashes Mid-Execution (Orphaned Job)

### Scenario
A worker pod crashes (OOMKill, node failure, crash) while executing a job. The Kafka message was already committed (offset advanced), so the job will not be re-consumed from Kafka.

### Impact
- `job_executions` row stays in `status=EXECUTING` with `worker_id` of the dead pod
- Redis lock holds for `timeoutSeconds + 60s` before expiring
- Job appears stuck until lock expires

### Detection
**Worker heartbeat check:** Every 30 seconds, an `OrphanDetectionJob` (scheduled task on the scheduler engine) queries:
```sql
SELECT * FROM execution.job_executions
WHERE status = 'EXECUTING'
  AND updated_at < now() - INTERVAL '60 seconds'
  AND worker_id NOT IN (SELECT worker_id FROM worker_registry WHERE status = 'ACTIVE')
```

**Lock TTL expiry:** After `timeoutSeconds + 60s`, the Redis lock expires. The scheduler can re-dispatch.

### Recovery

**Automatic Orphan Recovery:**
1. `OrphanDetectionJob` detects execution with dead worker
2. Evaluates: is this job within its timeout window?
   - **Yes:** Update execution to `FAILED`, apply retry policy
   - **No (timeout exceeded):** Update to `TIMED_OUT`, apply retry policy
3. Release Redis lock: `DEL job-lock::{job_id}`
4. Create new `JobExecution` (if retry allowed) or publish to DLQ

### Runbook
```
1. Detect: Monitor alert fires for "execution stuck > 5 min"
2. Identify: GET /api/v1/executions?status=EXECUTING&olderThan=5m
3. Check worker: GET /api/v1/workers/{worker_id}
4. If worker OFFLINE:
   a. POST /api/v1/executions/{id}/cancel (cancels if still EXECUTING)
   b. System will auto-retry per retry policy
5. If worker ACTIVE but execution stuck: may be a legitimate long-running job
   a. Check job timeout configuration
   b. If job timeout is too low, update via PATCH /api/v1/jobs/{id}
6. Monitor: confirm new execution created and starts running
```

---

## 4. F-03: Database Primary Fails

### Scenario
PostgreSQL primary fails unexpectedly. Patroni detects failure and promotes a replica to primary (automated failover). Failover time: 10-30 seconds.

### Impact During Failover
- All writes fail: scheduler cannot write outbox entries
- Workers cannot update execution status
- API writes (job registration, manual trigger) fail
- Reads continue on surviving replicas

### Detection
Patroni health check (`/patroni` endpoint) responds with `{role: replica, lag: ...}` instead of `{role: master}`. Application connection pool detects connection failures.

### Recovery

**Automated:**
1. Patroni promotes replica to primary (10-30s)
2. Application connection pool (PgBouncer) detects primary change via DNS or VIP switch
3. Application reconnects to new primary
4. Outbox relay resumes; jobs dispatched
5. Workers resume writing results

**Potential data issues:**
- Replica may be 0-10 seconds behind at failover time
- Outbox entries written in that window: if the primary crashed before replicating, those outbox entries are lost
- Impact: up to 10 seconds of due jobs not dispatched → scheduler re-dispatches them on next poll (they're still in `next_execution_time` if the schedule update wasn't replicated)

### Runbook
```
1. Alert: PagerDuty fires for "PostgreSQL primary unavailable"
2. Check Patroni: verify automatic failover completed (patronictl list)
3. Verify application: check scheduler logs for reconnection success
4. Check lag: did any outbox events get lost? Query new primary for PENDING outbox events
5. If execution history gap: jobs that were running during failover may have stuck status
   a. Run orphan detection manually
6. Verify read replicas are syncing from new primary
7. Post-incident: confirm old primary (now replica) catches up before re-enabling as candidate
```

---

## 5. F-04: Redis Cluster Failure

### Scenario
All Redis nodes become unavailable (network partition, cluster instability, cascading OOM).

### Impact
- Distributed lock acquisition fails → scheduler cannot safely dispatch (must not dispatch without lock)
- Worker registry unavailable
- Rate limiting falls open (or closed, configurable)
- Job metadata cache unavailable → workers must query PostgreSQL

### Critical Question: Should the scheduler dispatch without Redis locks?

**Option A: Fail safe (stop dispatching)**
- Scheduler detects Redis unavailable, enters safe mode — stops dispatching
- Risk: jobs are not triggered during Redis outage (may last minutes)
- Benefit: no double dispatch

**Option B: Fall back to PostgreSQL advisory locks**
- `SELECT pg_try_advisory_lock(job_id_hash)` before dispatch
- Risk: PostgreSQL lock contention increases dramatically; scheduler performance degrades
- Benefit: dispatch continues without Redis

**Recommendation:** Option A for Phase 1 (simplicity, safety). Option B for Phase 2 (availability over safety, with explicit acknowledgment of double-dispatch risk).

### Recovery
```
1. Alert: Redis sentinel alerts on primary failure + replica promotion failure
2. Check Redis Cluster: redis-cli cluster info
3. If transient: Redis Cluster heals automatically (with Sentinel: within 30s)
4. If persistent: restore Redis from RDB/AOF backup
5. During outage: scheduler in safe mode (no dispatch); API rate limiting falls open
6. After recovery: Redis state (locks, registry) is rebuilt
   a. Worker registry: workers re-register on next heartbeat (within 30s)
   b. Locks: rebuilt as jobs are dispatched
   c. Metadata cache: rebuilt lazily on next access
7. Post-recovery: check for any jobs that were "due" during outage; scheduler re-dispatches
```

---

## 6. F-05: Kafka Broker Failure

### Scenario
One of three Kafka brokers fails. With `min.insync.replicas=2`, the cluster continues functioning. If 2+ brokers fail, writes begin failing.

### Impact (1 broker failure)
- Kafka continues operating (replication factor=3, min.insync=2)
- Partition leadership rebalances to surviving brokers (30-60 seconds)
- During rebalance: brief producer/consumer stalls (milliseconds to seconds)
- No job loss

### Impact (2 broker failures)
- Producers fail to write (acks=all, min.insync=2 not met)
- Outbox relay accumulates PENDING events in PostgreSQL outbox table
- Workers drain existing messages from Kafka partitions on surviving broker
- After workers exhaust available messages: executions stall

### Recovery
```
1. Alert: under-replicated partitions > 0 (CRITICAL alert)
2. Identify failed brokers: kafka-topics --describe --under-replicated-partitions
3. Single broker failure: wait for automatic partition re-election (typically 60s)
4. Multiple broker failure:
   a. Restore failed broker instance (K8s pod restart, or new EC2 instance)
   b. Kafka controller re-assigns partitions to recovered broker
   c. Replica sync: watch ISR (in-sync replicas) grow back to 3
5. During outage: outbox table accumulates; dispatch resumes automatically after Kafka recovery
6. Post-recovery: monitor outbox PENDING count drain rate (should clear within minutes)
```

---

## 7. F-06: Clock Skew Between Scheduler Nodes

### Scenario
In active-active mode, two scheduler nodes have clocks that differ by >500ms. Scheduler Node A believes it is 00:00:00.000, Node B believes it is 23:59:59.600. Node A dispatches jobs due at midnight 400ms before Node B would.

### Impact
- Minor: jobs trigger slightly early/late (sub-second in normal operations with NTP)
- Severe (>1 second skew): In edge cases, jobs could be double-dispatched if both nodes see the same job as "due" within their respective windows

### Prevention
- NTP/PTP enforced at infrastructure level (K8s nodes sync to NTP servers)
- Kubernetes enforces time sync as a cluster health prerequisite
- 5-second lookahead window in scheduler (small skews are absorbed)
- Distributed lock (Redis SETNX) prevents double dispatch even with clock skew

### Detection
```
Custom metric: scheduler_clock_skew_ms (compare scheduler node time to NTP reference)
Alert if: abs(node_time - ntp_reference_time) > 500ms
```

---

## 8. F-07: Job Storm (Thundering Herd at Midnight)

### Scenario
100,000 jobs are configured to run at midnight (`0 0 * * *`). At 00:00:00, the scheduler discovers all 100k jobs simultaneously. The system must dispatch, execute, and record all 100k jobs without degrading other jobs.

### Cascade Risks
1. Scheduler PostgreSQL query returns 100k rows → OOM on scheduler node
2. Outbox table receives 100k inserts simultaneously → WAL pressure
3. Kafka ingest rate spikes to 100k messages in seconds
4. Workers scale too slowly → queue lag builds up
5. PostgreSQL result writes from 100k executions arriving together → lock contention

### Mitigations (see also 07-scaling-strategy.md)

1. **Scheduler batch limit:** Poll max 1,000 jobs per cycle; process 1,000, wait 1 second, repeat. Natural rate limiting.
2. **Pre-warming at 23:55:** Detect upcoming burst (query `schedules WHERE next_execution_time BETWEEN now() AND now() + 5 min COUNT(*) > 10000`); begin pre-loading outbox early.
3. **Outbox relay rate limit:** Configurable dispatch rate (default 5,000/sec). 100k jobs take 20 seconds to dispatch — acceptable.
4. **KEDA pre-scaling:** Custom metric "jobs_due_in_5_minutes" → KEDA scales worker pods 5 minutes early.
5. **Jitter for non-critical jobs:** Optional `startDelayJitterSeconds` on job config.

---

## 9. F-08: DLQ Overflow

### Scenario
A systematic bug causes 50% of all job executions to fail. DLQ accumulates faster than the monitor can process or operators can address.

### Impact
- `job-dlq` topic fills beyond retention window — oldest DLQ entries overwritten
- `monitoring.dlq_entries` table grows unbounded
- Operations team overwhelmed with volume of alerts

### Detection
- DLQ consumer lag > 10,000 → CRITICAL alert
- DLQ entry count growth rate > 100/min → HIGH alert

### Recovery
```
1. Identify root cause: what jobs are failing? What's the common error?
   GET /api/v1/admin/dlq?limit=100 — sample recent entries
2. Fix the underlying issue (bad job config, target service down, etc.)
3. If target service recoverable: bulk retry DLQ entries
   Script: for each DLQ entry in namespace, POST /admin/dlq/{id}/retry
4. If jobs are fundamentally broken: bulk discard
   Script: for each DLQ entry, DELETE /admin/dlq/{id}
5. After fix: monitor failure rate returns to baseline
6. DLQ table: if too large, archive old resolved entries to S3
```

---

## 10. F-10: DAG Dependency Deadlock

### Scenario
Job A depends on Job B, and Job B depends on Job A (circular dependency). Neither can start.

### Prevention
**At registration time:** The API validates DAG acyclicity using DFS when a job with dependencies is registered. Circular dependencies are rejected with `400 Bad Request`.

### Scenario (partial deadlock — all deps FAILED)
Job C depends on Jobs A and B completing successfully. Job A fails and exhausts retries (DLQ). Job B completes. Job C's dependency on A is never satisfied — Job C waits indefinitely.

### Detection
`TimeoutMonitor` checks for jobs with `triggerType=DEPENDENCY` and `status=QUEUED` older than their `time_window_seconds`:
```sql
SELECT * FROM job_dependencies jd
JOIN scheduling.schedules s ON s.job_id = jd.job_id
WHERE jd.time_window_seconds < EXTRACT(EPOCH FROM (now() - s.last_execution_time))
  AND s.status = 'WAITING_FOR_DEPENDENCY'
```

### Recovery
- Mark the waiting job as `DEPENDENCY_TIMEOUT`, publish to DLQ
- Operator reviews: should the downstream job run anyway? Manual trigger available.

---

## 11. F-11: Long-Running Job Never Terminates

### Scenario
A job is supposed to run for 30 minutes but due to a bug (infinite loop, stuck HTTP client), it runs for 6 hours with no termination.

### Impact
- Redis lock held for `timeoutSeconds + 60s` — after expiry, scheduler re-dispatches the job
- Worker pod accumulates resource usage (CPU, memory, file handles)
- If the job is exclusive (`exclusiveLock=true`), no new runs start until the lock expires

### Detection
`TimeoutMonitor` detects executions where `started_at + timeoutSeconds < now()`:
```sql
SELECT * FROM execution.job_executions
WHERE status = 'EXECUTING'
  AND started_at + (timeout_seconds * INTERVAL '1 second') < now()
```

### Recovery
1. `TimeoutMonitor` marks execution as `TIMED_OUT`
2. Sends interrupt signal to worker via worker management API: `POST /workers/{id}/interrupt/{executionId}`
3. Worker attempts graceful termination of the job (SIGTERM to subprocess / HTTP abort)
4. If worker does not respond in 30s: worker is marked for drain and pod is terminated by K8s
5. New execution created (if retry allowed)

---

## 12. F-12: Split-Brain (Two Scheduler Leaders)

### Scenario
Redis Sentinel experiences a network partition. Two scheduler nodes both believe they are the leader (old leader can't reach Sentinel; new leader was elected by majority). Both begin polling and dispatching.

### Impact
**CRITICAL:** Double dispatch of all due jobs. Two workers execute the same job simultaneously.

### Why This Is Rare But Possible
- Redis Sentinel requires quorum (majority) to elect a new leader
- If old leader can't reach Sentinel but can still reach PostgreSQL, it continues operating as "leader"
- New leader is simultaneously elected by the majority

### Mitigation
1. **Fencing tokens:** Even in split-brain, only one worker's result is accepted (the one with the higher fencing token). Both workers execute, but only one result is recorded. **Execution still happens twice** — this is the key risk.
2. **Lock check via PostgreSQL:** Before dispatching, the scheduler records its leadership claim in PostgreSQL. A second scheduler writing the same leadership entry detects the conflict via unique constraint violation.
3. **Epoch-based leadership:** Each election has an epoch number (stored in Redis and PostgreSQL). A scheduler dispatching with an old epoch is rejected by the outbox relay.

### Recovery
```
1. Detect: two scheduler nodes report is_leader=true in monitoring
2. Immediate: manually demote one leader (POST /internal/admin/stepdown)
3. Audit: identify jobs that were double-dispatched (two executions with same job_id and scheduledFor)
4. Cleanup: mark duplicate executions as CANCELLED (keep the one with higher fencing token / completed one)
5. Root cause: was Redis Sentinel misconfigured? Network partition? Investigate and fix
6. Post-incident: consider adding PostgreSQL distributed lock as secondary leader election mechanism
```

---

## Interview Discussion Points

**Q: What's the most catastrophic failure in this system and how do you design against it?**
A: Split-brain (F-12) is the most catastrophic because it causes double execution — which can mean double payments, double emails, double database writes in the downstream system. The defense is layered: fencing tokens reject stale results, but they don't prevent double execution. The final defense is idempotency in the downstream job itself — jobs must be designed to be safely re-run. This is a contractual requirement communicated to job owners.

**Q: How long does the system take to recover from a scheduler leader failure?**
A: In active-passive with Redis Sentinel: maximum 30 seconds (lock TTL). In active-active: no leader failure possible; a partitioned scheduler just stops processing its partition's jobs. The orphan detector picks up any executions that were in-flight within 60 seconds.

**Q: Can a job be permanently lost (never executed, no DLQ entry)?**
A: Yes, in one narrow scenario: the scheduler acquires the PostgreSQL row lock, updates `next_execution_time`, begins the outbox transaction, and crashes before committing. After failover: the DB rolled back, `next_execution_time` reverted, new leader sees the job as due and re-dispatches. So the job is NOT lost in this scenario. The only permanent loss scenario is if all PostgreSQL replicas fail simultaneously before the outbox write replicates — which is a catastrophic infrastructure failure (data center down), mitigated by multi-AZ replication.
