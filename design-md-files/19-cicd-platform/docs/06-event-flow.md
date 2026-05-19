# 06 — Event Flow: CI/CD Platform

---

## Objective

Trace the complete lifecycle of a pipeline run from Git push through job execution, artifact upload, and notification delivery. Include failure paths, timeout handling, and the job dependency resolution flow.

---

## Flow 1: Git Push → Pipeline Trigger → Job Dispatch

```mermaid
sequenceDiagram
    participant GH as GitHub
    participant WH as Webhook Service
    participant Kafka as Kafka
    participant S as Scheduler
    participant PC as Pipeline Config Service
    participant DB as PostgreSQL
    participant Q as Job Queue (Kafka)
    participant R as Runner

    GH->>WH: POST /webhooks/github (push event)
    WH->>WH: Validate HMAC-SHA256 signature
    WH->>DB: Check delivery_id uniqueness (idempotency)
    WH->>DB: INSERT webhook_events (delivery_id, payload)
    WH->>Kafka: Publish PipelineTriggerEvent
    WH-->>GH: 200 OK

    Kafka-->>S: PipelineTriggerEvent consumed
    S->>PC: GET /parse?repoId=&commitSha= (fetch YAML)
    PC-->>S: Workflow{jobs: [build, test, deploy], triggers: [...]}
    S->>S: Evaluate trigger conditions (branch match "main")
    S->>DB: BEGIN
    S->>DB: INSERT pipeline_runs(runId, status=PENDING)
    S->>DB: INSERT jobs[build(QUEUED), test(WAITING), deploy(WAITING)]
    S->>DB: COMMIT
    S->>Q: Publish JobDispatchEvent(jobId=build) [only build is QUEUED — no dependencies]
    S->>DB: UPDATE pipeline_runs SET status=QUEUED

    Q-->>R: JobDispatchEvent consumed (runner picks up)
    R->>DB: UPDATE jobs SET status=RUNNING, runner_id=R1, started_at=NOW()
    R->>DB: UPDATE pipeline_runs SET status=IN_PROGRESS, started_at=NOW()
```

**Why only dispatch `build` initially?** `test` has `needs: [build]` and `deploy` has `needs: [test]`. Scheduler evaluates DAG — only jobs with no unmet dependencies are dispatched. This prevents test from starting before build completes.

---

## Flow 2: Job Execution on Runner

```mermaid
sequenceDiagram
    participant R as Runner Pod
    participant SS as Secret Service
    participant LS as Log Service
    participant AS as Artifact Service
    participant S3 as Object Storage
    participant DB as PostgreSQL
    participant Q as Kafka

    R->>SS: gRPC GetSecrets(jobToken, ["AWS_KEY", "NPM_TOKEN"])
    SS->>SS: Validate jobToken (signature + expiry check)
    SS->>SS: Decrypt secrets from DB (KMS call)
    SS-->>R: {AWS_KEY: "...", NPM_TOKEN: "..."} (in-memory only)
    SS->>DB: INSERT secret_access_log(job_id, secrets_accessed)

    R->>R: git clone repo@commitSha (authenticated via install token)
    R->>LS: POST /logs/{jobId}/start (mask patterns: ["****"])

    loop For each step
        R->>DB: UPDATE steps SET status=RUNNING, started_at=NOW()
        R->>R: Execute step command in subprocess
        R->>LS: POST /logs/{jobId}/chunk (log bytes, stepIndex)
        LS->>LS: Apply mask patterns to log chunk
        LS->>Redis: RPUSH log:stream:{jobId}, publish notification
        LS->>S3: (async) Flush gzipped log chunk every 10s
        R->>DB: UPDATE steps SET status=SUCCEEDED/FAILED, exit_code=0, completed_at
    end

    R->>AS: POST /artifacts/upload-url {jobId, name="test-results"}
    AS-->>R: {presignedUrl: "https://s3.../...?sig=..."}
    R->>S3: PUT artifact bytes (direct to S3, no proxy)
    R->>AS: POST /artifacts/register {artifactId, sizeBytes}
    AS->>DB: INSERT artifacts(...)

    R->>Q: Publish JobStatusEvent(jobId, status=SUCCEEDED, steps=[...], exitCode=0)
    R->>R: Destroy workspace, exit
```

---

## Flow 3: Job Completion → Dependency Resolution → Next Job Dispatch

```mermaid
sequenceDiagram
    participant Q as Kafka
    participant S as Scheduler
    participant DB as PostgreSQL

    Q-->>S: JobStatusEvent(jobId=build, status=SUCCEEDED)
    S->>DB: UPDATE jobs SET status=SUCCEEDED WHERE job_id=build
    S->>DB: SELECT jobs WHERE run_id=? AND status=WAITING
    Note over S: Check if dependencies of WAITING jobs are now met

    S->>S: Evaluate DAG: test needs [build] → build SUCCEEDED → test can run
    S->>DB: UPDATE jobs SET status=QUEUED WHERE job_id=test
    S->>Q: Publish JobDispatchEvent(jobId=test)

    Note over S: deploy still WAITING (needs: [test])

    Q-->>S: JobStatusEvent(jobId=test, status=SUCCEEDED)
    S->>DB: UPDATE jobs SET status=SUCCEEDED WHERE job_id=test
    S->>S: deploy needs [test] → test SUCCEEDED → deploy can run
    S->>DB: UPDATE jobs SET status=QUEUED WHERE job_id=deploy
    S->>Q: Publish JobDispatchEvent(jobId=deploy)
```

---

## Flow 4: Job Failure with Dependency Cascade

```mermaid
sequenceDiagram
    participant Q as Kafka
    participant S as Scheduler
    participant DB as PostgreSQL

    Q-->>S: JobStatusEvent(jobId=test, status=FAILED)
    S->>DB: UPDATE jobs SET status=FAILED WHERE job_id=test
    S->>DB: UPDATE pipeline_runs SET jobs_failed=1

    S->>S: Check deploy job: needs [test] → test FAILED
    S->>S: No continue-on-error → deploy should be SKIPPED
    S->>DB: UPDATE jobs SET status=SKIPPED WHERE job_id=deploy

    S->>S: All jobs terminal → run conclusion = FAILED
    S->>DB: UPDATE pipeline_runs SET status=FAILED, conclusion=FAILED, completed_at=NOW()
    S->>Q: Publish RunCompletedEvent(runId, status=FAILED, branch, commitSha)
```

---

## Flow 5: Real-Time Log Streaming to Browser

```mermaid
sequenceDiagram
    participant B as Browser
    participant LSApi as Log Service API
    participant Redis as Redis
    participant S3 as Object Storage

    B->>LSApi: GET /v1/jobs/{jobId}/logs/stream (SSE)
    LSApi->>LSApi: Check job status — RUNNING
    LSApi->>Redis: LRANGE log:stream:{jobId} 0 -1 (replay buffered lines)
    LSApi-->>B: SSE: data: {"lineNum":1, "text":"+ npm install"}
    LSApi-->>B: SSE: data: {"lineNum":2, "text":"added 1023 packages"}

    Note over LSApi: Subscribe to Redis pubsub for new lines
    loop While job running
        Runner->>LSApi: POST new log chunk
        LSApi->>Redis: RPUSH + PUBLISH log:pubsub:{jobId}
        Redis-->>LSApi: Notification (new lines)
        LSApi-->>B: SSE: data: {"lineNum":245, "text":"✓ All tests pass"}
    end

    Note over Runner: Job completes
    Runner->>Q: Publish JobStatusEvent(SUCCEEDED)
    S->>Q: (consumed, run state updated)
    LSApi-->>B: SSE: data: {"event":"job_complete","status":"SUCCEEDED"}
    LSApi-->>B: SSE: close connection

    Note over B: User views historical log
    B->>LSApi: GET /v1/jobs/{jobId}/logs?startLine=500&endLine=600
    LSApi->>S3: GetObject(log key, byte range)
    S3-->>LSApi: Gzipped log chunk
    LSApi-->>B: 200 JSON log lines
```

---

## Flow 6: Runner Heartbeat and Orphaned Job Recovery

```mermaid
sequenceDiagram
    participant R as Runner Pod
    participant S as Scheduler
    participant DB as PostgreSQL
    participant Q as Kafka

    loop Every 30 seconds (heartbeat)
        R->>S: gRPC Heartbeat(runnerId, jobId, status)
        S->>DB: UPDATE runners SET last_heartbeat=NOW()
        S-->>R: HeartbeatResponse(CONTINUE / CANCEL)
    end

    Note over R: Runner pod crashes (OOM, node failure)
    Note over S: Heartbeat timeout detector (background thread)

    S->>DB: SELECT runners WHERE last_heartbeat < NOW() - INTERVAL '2min'
              AND status = 'BUSY'
    S->>DB: [Result: runner R1 with job_id=build is orphaned]

    S->>DB: UPDATE runners SET status=OFFLINE WHERE runner_id=R1
    S->>DB: SELECT jobs WHERE runner_id=R1 AND status='RUNNING'
    Note over S: Check retry count
    alt retry_count < max_retries
        S->>DB: UPDATE jobs SET status=QUEUED, runner_id=NULL, retry_count=retry_count+1
        S->>Q: Publish JobDispatchEvent(jobId=build) [re-dispatch]
    else max_retries exceeded
        S->>DB: UPDATE jobs SET status=FAILED, conclusion='RUNNER_FAILURE'
        S->>S: Trigger dependency cascade (skip downstream jobs)
    end
```

---

## Flow 7: Manual Cancellation

```mermaid
sequenceDiagram
    participant U as User
    participant API as Control Plane API
    participant DB as PostgreSQL
    participant Q as Kafka
    participant R as Runner

    U->>API: POST /v1/runs/{runId}/cancel
    API->>DB: SELECT pipeline_runs WHERE run_id=? FOR UPDATE
    API->>API: Validate status is cancellable (not terminal)
    API->>DB: UPDATE pipeline_runs SET status=CANCELLED
    API->>DB: UPDATE jobs SET status=CANCELLED WHERE status IN (QUEUED, WAITING, RUNNING)
    API->>Q: Publish RunCancelledEvent(runId, cancelledJobIds=[...])
    API-->>U: 200 {status: CANCELLED}

    Q-->>R: RunCancelledEvent consumed
    R->>R: Received cancellation for current job
    R->>R: Send SIGTERM to running subprocess
    R->>R: Wait 30s (graceful), then SIGKILL
    R->>Q: Publish JobStatusEvent(jobId, status=CANCELLED)
    R->>R: Clean up workspace, exit
```

---

## Flow 8: Scheduled (Cron) Pipeline Trigger

```mermaid
sequenceDiagram
    participant Cron as Scheduler (Cron Component)
    participant DB as PostgreSQL
    participant Q as Kafka

    loop Every minute
        Cron->>DB: SELECT workflows WHERE cron_schedule IS NOT NULL
                   AND next_run_at <= NOW()
                   AND is_enabled = TRUE
        Note over Cron: Distributed lock prevents duplicate triggers
        Cron->>DB: SELECT pg_try_advisory_lock(hash(workflowId + minute))
        Note over Cron: Only one scheduler instance holds lock
        Cron->>DB: UPDATE workflows SET next_run_at = cron_next(schedule, NOW())
        Cron->>Q: Publish PipelineTriggerEvent(source=CRON, repoId, branch)
        Cron->>DB: pg_advisory_unlock(...)
    end
```

**Distributed lock for cron:** Multiple Scheduler instances run for HA. Without locking, all would fire the same cron trigger simultaneously. `pg_try_advisory_lock` on the workflow-minute composite key ensures only one scheduler fires each cron event.

---

## Flow 9: Artifact Download by Downstream Job

```mermaid
sequenceDiagram
    participant R2 as Runner (test job)
    participant AS as Artifact Service
    participant S3 as Object Storage

    Note over R2: Pipeline YAML: download-artifact: name=build-output
    R2->>AS: GET /artifacts?runId=&name=build-output
    AS->>AS: Verify jobToken belongs to same run
    AS-->>R2: {artifactId, s3Key, presignedDownloadUrl}
    R2->>S3: GET <presignedUrl>
    S3-->>R2: Artifact bytes
    R2->>R2: Extract artifact to workspace
```

---

## Event Schemas (Key Events)

**PipelineTriggerEvent:**
```json
{
  "eventId": "uuid",
  "repoId": "uuid",
  "orgId": "uuid",
  "triggerType": "PUSH",
  "branch": "main",
  "commitSha": "abc123",
  "actor": "user123",
  "timestamp": "ISO8601",
  "webhookDeliveryId": "github-uuid"
}
```

**JobDispatchEvent:**
```json
{
  "jobId": "uuid",
  "runId": "uuid",
  "orgId": "uuid",
  "workflowJobId": "build",
  "runsOn": "ubuntu-22.04",
  "repoCloneUrl": "https://...",
  "commitSha": "abc123",
  "workflowYaml": "base64...",
  "secretNames": ["AWS_KEY", "NPM_TOKEN"],
  "jobToken": "short-lived-jwt",
  "timeoutMinutes": 30,
  "retryCount": 0
}
```

**RunCompletedEvent:**
```json
{
  "runId": "uuid",
  "orgId": "uuid",
  "status": "FAILED",
  "branch": "main",
  "commitSha": "abc123",
  "workflowName": "ci",
  "durationSeconds": 245,
  "jobsSummary": {"total": 3, "succeeded": 2, "failed": 1, "skipped": 0},
  "triggeredBy": "PUSH",
  "actor": "developer123"
}
```

---

## Tradeoffs

| Flow | Decision | Cost |
|---|---|---|
| Dependency resolution | Scheduler re-evaluates DAG on each job completion | Extra DB reads per job completion; negligible at scale |
| Orphaned job recovery | Heartbeat timeout (2 min) | 2-minute window of "zombie" job occupying runner slot |
| Cron distributed lock | Advisory lock in PostgreSQL | Single DB call per cron check per minute; PG dependency |
| Log streaming via Redis | Real-time without SSE long-poll delays | Redis memory usage; log data in flight during Redis failure |

---

## Interview Discussion Points

- **What happens if the Scheduler crashes while dispatching jobs?** Jobs in DB with status=QUEUED but not yet in Kafka queue. On Scheduler restart: query jobs with QUEUED status, re-dispatch. Idempotent: Kafka job key ensures no duplicate dispatch if event was already sent
- **How do you prevent a job from running twice?** Runner performs compare-and-swap on job status: `UPDATE jobs SET status=RUNNING WHERE job_id=? AND status=QUEUED` → if 0 rows updated, another runner claimed it. Exactly-once assignment via optimistic locking
- **What is the maximum depth of a job dependency chain?** No hard limit in the design. Platform imposes soft limit (e.g., max 20 levels) to prevent infinite dependency chains. Cycle detection runs at pipeline parse time — circular dependencies are validation errors
- **How do you handle a step that hangs indefinitely?** Each step has a configurable timeout. Runner monitors step execution time, sends SIGTERM after timeout, SIGKILL after grace period. Job fails with exit code 124 (timeout). Scheduler also monitors job-level timeout independently
