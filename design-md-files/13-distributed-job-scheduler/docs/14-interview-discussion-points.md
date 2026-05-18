# 14 — Interview Discussion Points: Distributed Job Scheduler

## Objective
Comprehensive Q&A covering every angle an interviewer at Staff+ level would probe — from fundamental correctness guarantees to scaling evolution, operational trade-offs, and "what would break first" analysis.

---

## Section 1: Core Correctness — Preventing Double Execution

### Q1: How do you prevent a job from being executed twice?

**Answer (layered defense):**

The system uses three complementary mechanisms, because no single mechanism is sufficient:

**Layer 1: Distributed Lock (Redis SETNX)**
Before the Scheduler Engine publishes a job to Kafka, it acquires a Redis lock keyed on `job_id`:
```
SET job-lock::{job_id} {scheduler_node_id}:{fencing_token} NX EX {timeout_seconds + 60}
```
If the lock exists (job is already running or recently dispatched), the scheduler skips this job in the current poll cycle. This prevents dispatch from ever creating two executions for the same job simultaneously.

**Layer 2: Fencing Token**
Even if two scheduler nodes both attempt to dispatch the same job (split-brain scenario), each execution is assigned a unique, monotonically increasing fencing token. When a worker completes and tries to write its result, the Result Processor checks:
```sql
UPDATE job_executions SET status=? WHERE execution_id=? AND fencing_token = ?
```
The stale worker (with the lower fencing token) gets 0 rows updated and discards its result. This prevents stale results from overwriting correct results.

**Layer 3: Idempotent Job Design (contract with job owners)**
The system cannot prevent a job from being *executed* twice in all split-brain scenarios. Therefore, jobs must be designed to be idempotent — safe to execute twice with the same parameters. This is communicated as a contractual requirement.

**The key insight:** Layer 1 prevents double dispatch in normal operation. Layer 2 prevents stale results in edge cases. Layer 3 is the safety net for the truly catastrophic case.

---

### Q2: Exactly-once vs. at-least-once — which do you choose and why?

**Answer:**

The system provides **at-least-once dispatch with idempotency controls** rather than true exactly-once execution. Here's why:

**True exactly-once execution is impossible to guarantee end-to-end** when:
- The network between the scheduler and the worker can partition
- The worker can crash mid-execution before reporting completion
- The job execution itself interacts with external systems (HTTP calls, database writes) that don't participate in the distributed transaction

**What we actually provide:**
- **Exactly-once dispatch** (the job is enqueued to Kafka exactly once, via outbox + idempotent Kafka producer)
- **At-least-once execution** (retries are possible if a worker crashes)
- **At-most-once result recording** (fencing tokens reject stale worker results)

**When to use at-most-once (no retries):**
For jobs where a duplicate execution is more harmful than a missed execution (e.g., "send a payment"). These jobs should be configured with `maxAttempts: 1` and the downstream system must implement its own idempotency (payment idempotency keys).

**When to use at-least-once:**
For jobs where missing an execution is worse than a duplicate (e.g., "generate a report", "refresh a cache"). These can retry freely because the downstream operation is idempotent.

**The FAANG answer:** At large scale, you must decide this per-job, not system-wide. The scheduler supports both via `maxAttempts` configuration. The semantic guarantee is documented clearly so job owners make an informed decision.

---

## Section 2: Scheduler High Availability

### Q3: Active-passive vs. active-active scheduler — walk me through the tradeoffs.

**Answer:**

| | Active-Passive | Active-Active (Partitioned) |
|---|---|---|
| **Complexity** | Low | High (consistent hashing, partition rebalance) |
| **Throughput** | Single node throughput limit | Linear scaling with nodes |
| **Failure recovery** | 10-30s leader election | No leader — a node failure just reduces capacity |
| **Scheduling correctness** | Simple — one node dispatches | Complex — partition overlap during rebalance |
| **When to use** | Up to ~5M active jobs | Beyond 5M or when single node is bottleneck |

**Active-Passive in depth:**
- Redis lock: `SET scheduler-leader {node_id} NX EX 30`
- Leader refreshes every 10s; followers watch for key expiry
- Failover: lock expires → first follower to acquire becomes leader
- Risk: 30s gap during failover; jobs scheduled in that window may be delayed

**Active-Active in depth:**
- Jobs partitioned: `partition = hash(job_id) % num_scheduler_nodes`
- Each node owns its partition and polls independently
- No coordination needed in steady state
- Risk during node join/leave: partition rebalance — some jobs temporarily unowned
- Mitigation: overlap window (new node starts owning partition N seconds after joining; old node stops N seconds after handoff begins)

**My recommendation:** Start active-passive. Move to active-active when scheduler poll cycles exceed 100ms (indicating contention or query slowness at scale).

---

### Q4: How do you handle the midnight cron spike — 100k jobs triggered simultaneously?

**Answer:**

The spike has multiple compounding effects. Address each separately:

**Problem 1: Scheduler overwhelm**
Solution: Scheduler polls max 1,000 jobs per cycle (configurable batch limit). With 100k due jobs and 1,000/cycle at 1s intervals, dispatch takes 100 seconds — during which no job is lost (they all have `next_execution_time = 00:00:00`). Jobs may start late by up to 100 seconds, which is acceptable.

Better solution: Lookahead pre-dispatch starting at 23:55 for jobs due between 00:00 and 00:05. Dispatcher processes them over 5 minutes instead of 0 seconds.

**Problem 2: Worker saturation**
Solution: KEDA pre-scaling based on a custom metric: `scheduler_jobs_due_lookahead_total`. At 23:55, this metric spikes to 100k → KEDA scales workers up before midnight → workers ready when jobs arrive.

**Problem 3: Kafka burst**
Solution: Outbox relay rate-limiting at 5,000 messages/sec. 100k messages → 20 seconds of dispatch. Kafka receives a smooth 5k/sec rate, not a 100k burst.

**Problem 4: Result processor saturation**
Solution: 100k jobs completing over ~30 minutes → ~55 results/second if average duration is 30 minutes. Result processor (15 pods, 100 records/sec/pod) has 1,500 records/sec capacity — ample headroom.

**Key insight for the interviewer:** The solution is not one thing. It requires smoothing the spike at every stage — dispatch, queue, execution, result recording.

---

## Section 3: Priority and Fairness

### Q5: How do you implement job priority without building a separate queue per priority?

**Answer:**

In Kafka, you cannot reprioritize messages within a partition after they're written. The options are:

**Option 1 (Recommended): Separate topics per priority tier**
- `job-triggers-p0` (critical), `job-triggers-p1` (high), `job-triggers` (normal/bulk)
- Separate worker consumer groups for each
- P0 workers are always available (never scaled to zero)
- Simple, operationally transparent, hard SLA isolation

**Option 2: Local priority sorting at the worker**
- Single topic; worker maintains a local priority queue
- Worker pre-fetches N messages, sorts by priority header, processes highest first
- Problem: local sorting only — a P4 message that reached the worker before a P0 message on a different partition gets processed later, but there's no global ordering guarantee
- Problem 2: prefetch buffer adds memory pressure
- OK for best-effort priority, not for strict SLA guarantees

**Option 3: Kafka Streams topology (complex, Phase 3)**
- Merge priority streams into one processing topology with a PriorityBlockingQueue
- True global priority ordering
- High complexity, not justified unless P0 SLAs require sub-second dispatch guarantees

**The interview answer:** "Priority is about SLA isolation, not strict ordering. Separate topics with dedicated consumer groups give you isolation at the cost of more operational complexity. Within each tier, Kafka preserves partition order, which is sufficient."

---

## Section 4: DAG Execution

### Q6: How would you implement DAG job dependencies?

**Answer:**

**Data model:**
- Job stores its dependencies: `[(depends_on_job_id, condition_type, time_window)]`
- NOT the inverse graph — Job A doesn't know who depends on it

**Resolution logic (DAG Resolution Service):**
1. Subscribe to `job-events` Kafka topic
2. On `ExecutionCompleted(jobId=A, status=COMPLETED)`:
   a. Query `job_dependencies WHERE depends_on_job_id = A`
   b. For each dependent job B: check all of B's dependencies
   c. Are all satisfied? → trigger B
   d. Not all satisfied? → record A's completion; wait for remaining deps

**State tracking:**
```sql
-- Track which dependencies have been satisfied for a given triggered execution
CREATE TABLE execution.dependency_satisfaction (
    waiting_job_id  UUID,
    satisfied_dep_job_id UUID,
    satisfied_at     TIMESTAMPTZ,
    execution_id     UUID,         -- the execution that satisfied it
    PRIMARY KEY (waiting_job_id, satisfied_dep_job_id)
);
```

**Preventing infinite waits:**
Each dependency has a `time_window_seconds`. If the dependency is not satisfied within that window, the waiting job is marked `DEPENDENCY_TIMEOUT` and sent to DLQ.

**Acyclicity enforcement:**
At registration time, DFS from the new job through all dependencies to detect cycles. O(n) where n is the number of jobs in the DAG — must be bounded (max DAG depth: 50).

**Key complexity:** In a distributed system, two DAG nodes might receive their "upstream completed" events out of order from Kafka. The `dependency_satisfaction` table handles this: the second event to arrive checks if all deps are now satisfied.

---

## Section 5: Long-running vs. Short-running Jobs

### Q7: How does your design handle both short-running (10ms) and long-running (8hr) jobs?

**Answer:**

These have very different operational profiles and cannot be treated identically.

**Short-running jobs (< 30 seconds):**
- High throughput per worker (hundreds per minute)
- Lock TTL can be short (timeoutSeconds + 60s = ~90s)
- Many fit on a single worker pod
- Kafka `max-poll-interval-ms` is not a concern (jobs complete well within Kafka's interval)

**Long-running jobs (hours):**
- One job monopolizes a worker pod for the entire duration
- Lock TTL must be very long (8hr + 1hr buffer = 9hr)
- `max-poll-interval-ms` must be set to at least 9 hours — or, better, commit offset immediately on pickup and track progress separately
- Worker heartbeat becomes critical — without heartbeat, orphan detection fires after 60s and marks the job as orphaned
- Worker sends periodic heartbeats: `POST /internal/executions/{id}/heartbeat` every 30 seconds
- Timeout monitor only fires after `timeoutSeconds` — not after the orphan detection window

**The key design decision:** Do NOT use the same Kafka consumer configuration for both job types. Use separate Kafka consumer groups with different `max-poll-interval-ms` settings. Long-running job consumers commit offset immediately on pickup.

**Worker separation:**
- Short-running worker pods: high concurrency (20 concurrent), fast heartbeat
- Long-running worker pods: low concurrency (1-2 concurrent), extended `max-poll-interval-ms`
- This is a separate worker pool, consuming from a separate `job-triggers-longrunning` topic

---

## Section 6: Scaling Breaking Points

### Q8: What breaks first when you 10x the scale?

**Answer:**

Starting from the Phase 2 baseline (10M jobs, 100k concurrent executions), 10x means 100M jobs and 1M concurrent executions.

**First to break: PostgreSQL single primary**
- Result processor writes at 33,000 executions/second (10x of 3,333)
- PostgreSQL single primary throughput: ~50,000 writes/second (with optimized settings)
- At 33,000 writes/second, we're approaching the limit, especially with the partitioned table overhead
- Solution: horizontal write sharding (Citus) or migrate to CockroachDB

**Second to break: Scheduler Engine throughput**
- 100M active jobs with 10% due per hour = 10M dispatches/hour = 2,778/second
- A single scheduler node handles ~1,000-2,000 dispatches/second
- Solution: active-active scheduler with 5-10 nodes

**Third to break: Kafka partition count**
- 1M concurrent executions at average 30s = 33,333 messages/second to Kafka
- With 64 partitions: 520 messages/second/partition — near the practical limit for consumer groups of this size
- Solution: increase partitions to 256+

**Fourth to break: Worker pod count**
- 1M concurrent executions / 10 per pod = 100,000 pods
- A single K8s cluster supports ~5,000 nodes / 30 pods per node = 150,000 pods — tight but feasible
- Solution: multi-cluster worker pools; cluster federation

**Fifth to break: Redis single cluster**
- 1M concurrent lock acquisitions is manageable for Redis Cluster
- Job metadata cache at 10% of 100M = 10M cached jobs × 2KB = 20GB — requires larger Redis cluster (8+ nodes)

---

## Section 7: Common Candidate Mistakes

### Q9: What are common mistakes candidates make when designing a job scheduler?

**Answer (what to avoid):**

**Mistake 1: Using the database as the message queue**
Many candidates use a `job_queue` table with `status=PENDING` and workers polling it with `SELECT ... SKIP LOCKED`. This works at small scale but becomes a write-heavy bottleneck at 1,000+ dispatches/second. Also, there's no fan-out (multiple consumer types), no replayability, and no native priority support.

**Mistake 2: Not distinguishing between scheduling time and execution time**
The scheduler's responsibility ends when it publishes to Kafka. Execution is the worker's responsibility. Conflating these leads to designs where the scheduler waits for execution to complete before computing the next run — creating a blocking dependency.

**Mistake 3: Ignoring the misfire problem**
"What happens if the scheduler is down for 2 hours?" Candidates who don't think about misfire policies will have a system that either drops those jobs silently or tries to replay all of them at once (avalanche). The answer involves misfire policy configuration per job.

**Mistake 4: Not addressing exactly-once semantics explicitly**
"The scheduler won't submit a job twice" is not a guarantee — it's an aspiration. The interviewer will probe: what if there's a network partition? What if Redis loses state? The answer must address layers of defense.

**Mistake 5: Ignoring clock skew**
Assuming all nodes have synchronized clocks leads to subtle bugs. The scheduling window (lookahead window) implicitly handles small skews, but the design must acknowledge NTP and the implications of clock drift.

**Mistake 6: Designing the lock TTL incorrectly**
TTL too short → lock expires during legitimate execution → double dispatch. TTL too long → orphaned executions take too long to recover. The correct value is `timeoutSeconds + buffer`, where buffer accounts for network latency and cleanup overhead.

---

## Section 8: Staff and Principal Engineer Discussion Points

### Q10: How would you handle multi-region active-active scheduling?

**Answer:**

This is Phase 3 territory. The core challenge: two regions both wanting to schedule the same global job.

**Option A: Region-partitioned jobs (Recommended)**
Each job is assigned to a "home region." The scheduler in that region owns the job. Workers can run in any region (dispatched via cross-region Kafka replication, or regional Kafka clusters with topic mirroring).

**Option B: Global consensus (Zookeeper/etcd)**
A global consensus system (etcd multi-region or CockroachDB) manages leadership. All regions participate; only the elected leader dispatches. Expensive, high-latency consensus calls in the hot path.

**Option C: CRDT-based scheduling (Research territory)**
Use CRDTs (Conflict-free Replicated Data Types) to represent the dispatch decision — designed so concurrent decisions from two regions are mergeable without conflict. Not practical with current tooling.

**Recommendation:** Region partitioning is the pragmatic choice. Jobs are created with an `affinityRegion` field. The regional scheduler only dispatches jobs with its region affinity. Cross-region failover: if a region is down, its jobs are re-assigned to another region's scheduler after a configurable failover timeout.

---

### Q11: What would you change if you had to rebuild this system from scratch with today's tooling?

**Answer:**

1. **Use a streaming database (e.g., Materialize or RisingWave)** for the execution history projections instead of Kafka → Elasticsearch. A streaming SQL database can maintain materialized views of execution aggregates in real-time, eliminating the separate projection consumer.

2. **Consider using CockroachDB instead of PostgreSQL** for the job store. CockroachDB's distributed SQL gives active-active write capability natively — eliminating the need for application-level partitioning in the scheduler.

3. **Worker execution model**: Replace the HTTP/Shell/gRPC worker types with a WASM-based execution sandbox. Job logic is compiled to WASM and executed in a sandbox — better security isolation, faster cold starts than containers, no separate worker pool per job type.

4. **Temporal.io as the workflow engine** instead of custom DAG implementation. Temporal handles state persistence, retries, timeouts, and long-running workflows with exactly-once semantics — replacing most of the Execution bounded context.

5. **Keep the outbox pattern** — it's still the correct solution for transactional message publishing. No modern alternative is simpler.

---

### Q12: How would an interviewer challenge your architecture?

**Challenges and responses:**

**Challenge:** "Your distributed lock uses Redis. What if Redis has a short partition and two schedulers both acquire the lock?"
**Response:** Redis Sentinel requires quorum for failover. With 3 Sentinel nodes, network partition that splits 1 from 2 means the minority cannot claim leadership. The old leader (now partitioned) cannot refresh its lock (TTL expires). The majority elects new leader. The old leader's lock expires before the new leader acquires it — by design. The window of double leadership is bounded by the lock TTL.

**Challenge:** "Why PostgreSQL for the job store? What about a NoSQL approach for scale?"
**Response:** The job store has strong consistency requirements (optimistic locking, atomic outbox writes, transaction-safe status updates). NoSQL databases that provide eventual consistency introduce the risk of losing outbox entries or accepting conflicting job updates. PostgreSQL with read replicas and partitioning handles the job store scale (10M jobs = 20GB — trivial for Postgres). NoSQL would be considered if the job store needed to scale to billions of jobs with multi-region writes — a constraint not present here.

**Challenge:** "What's your SLA for job execution completion? How do you enforce it?"
**Response:** The scheduler SLA covers trigger latency and execution start latency — not completion time. Completion time depends on the job logic and the target system. We provide timeout enforcement (mark as TIMED_OUT after N seconds) but cannot guarantee completion time. The appropriate answer for a FAANG system: document what the scheduler guarantees (dispatch within 1s, start within 10s) and what it doesn't (completion time, external service availability). Job owners set `timeoutSeconds` based on their own SLAs.

---

### Q13: What are the operational burdens of this system that don't show up in the design?

**Answer (honest operational assessment):**

1. **Partition management**: Monthly PostgreSQL partitions must be created 30 days ahead. If the automation fails, the job will fail at midnight on the first day of the new month. Requires monitoring + alerting on partition creation.

2. **Kafka topic partition increases**: Kafka partitions can only be increased, never decreased. Each increase causes a consumer group rebalance (brief processing pause). Consumer group lag metrics must be watched closely during partition changes.

3. **Worker pool tuning**: `max-poll-interval-ms` must be carefully tuned per job type. Too low → rebalances on long-running jobs. Too high → slow orphan detection. This is a subtle operational parameter that causes incidents when misconfigured.

4. **Redis lock TTL management**: As job timeout configurations change, the lock TTL must be updated. If a job's timeout is increased but the lock TTL isn't, the lock expires before the job finishes → double dispatch. There's no automatic enforcement of this invariant.

5. **Schema Registry maintenance**: Avro schema evolution rules must be enforced. A developer who adds a required field without a default breaks all existing consumers. Requires team education and CI enforcement.

6. **DLQ review process**: DLQ entries accumulate silently if no one reviews them. An operational process (weekly DLQ review) must be established — technology alone doesn't solve this.

---

### Q14: How does your design differ for a startup with 2 engineers vs. a FAANG team with 50?

**Answer:**

| Aspect | Startup (2 engineers) | FAANG (50 engineers) |
|---|---|---|
| Architecture | Single-node scheduler, PostgreSQL, no Kafka (use Redis Queue) | Full distributed design as described |
| HA | Cron job + auto-restart | Active-passive → active-active |
| Priority | Single queue | Multi-tier priority topics |
| DAGs | Not supported | Full DAG with cycle detection |
| Multi-tenancy | Single tenant | Full namespace isolation, per-tenant quotas |
| Observability | Basic logging | Full tracing, metrics, SLO alerting |
| CI/CD | Manual deploy | GitOps, canary releases, automated rollback |
| Cost | ~$200/month | ~$50,000/month |

**The startup answer:** Use Celery Beat + Redis + PostgreSQL. Ship in a week. The distributed design in this document is for a platform team at a company with dozens of teams submitting millions of jobs. A startup with 10 jobs/day should not build this.

**The scaling answer:** Know where the inflection points are. When do you need Kafka? When you need replayability, fan-out, or > 5,000 dispatches/second. When do you need active-active scheduler? When single-node throughput is your bottleneck. Don't add complexity before the problem exists.
