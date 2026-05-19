# 03 — DDD Bounded Contexts: CI/CD Platform

---

## Objective

Define bounded contexts, their responsibilities, internal domain models, and integration contracts for the CI/CD platform. Establish where context boundaries prevent unwanted coupling.

---

## Architecture Choice: Microservices per Context

**Why microservices over modular monolith here?**
- Runner pool must scale to 10,000 pods independently from control plane
- Secret Service requires a different security boundary (HSM, stricter access control)
- Log streaming has unique stateful buffering requirements (Redis + S3)
- Each context has distinct scaling requirements, deployment cadence, and failure modes

**Migration path if starting small:** Begin as modular monolith with well-defined package boundaries per context. Extract to microservices when a specific scaling or isolation requirement emerges.

---

## Bounded Contexts

### 1. Trigger Ingestion Context

**Responsibility:** Accept pipeline triggers from external systems and validate them.

**Owns:**
- Webhook signature validation (HMAC-SHA256 per provider)
- Webhook event normalization (GitHub push event → PlatformPushEvent)
- Idempotency: deduplicate duplicate webhook deliveries (Git providers retry on timeout)
- Rate limiting per repository (prevent webhook flood)
- Raw webhook event archival

**Does NOT own:** Pipeline evaluation, run creation, job dispatch

**Key Entities:** `WebhookEvent`, `TriggerSource` (GitHub, GitLab, etc.)

**Language:**
- "validate webhook"
- "normalize event"
- "deduplicate delivery"
- "publish trigger"

**Integration:**
- Publishes `PipelineTriggerEvent` to Kafka `pipeline-triggers` topic
- Downstream consumers (Scheduler) are decoupled from provider-specific webhook semantics

---

### 2. Pipeline Configuration Context

**Responsibility:** Parse, validate, and serve pipeline definitions from repository YAML.

**Owns:**
- YAML parsing and schema validation (workflow definition language)
- Resolving composite actions / reusable workflows (fetch from other repos)
- Caching parsed workflow definitions (per commit SHA — immutable)
- Storing organization-level default pipeline configs
- Validating secret references exist before run starts

**Does NOT own:** Execution state, run creation, secrets values

**Key Entities:** `Workflow`, `JobDefinition`, `StepDefinition`, `TriggerCondition`, `Action` (reusable step)

**Language:**
- "parse workflow"
- "resolve action"
- "validate pipeline"
- "cache definition"

**Integration:**
- Read by Scheduler when creating a run (Scheduler calls Parse API)
- Scheduler passes parsed Workflow to Run Creation

---

### 3. Execution Orchestration Context (Scheduler)

**Responsibility:** Own the lifecycle of pipeline runs and job dispatch. The "brain" of the system.

**Owns:**
- `PipelineRun` aggregate (status, job collection)
- `Job` entity lifecycle (QUEUED → RUNNING → SUCCEEDED/FAILED)
- Dependency graph evaluation (DAG of `needs:` relationships)
- Job dispatch to runner pool (via job queue)
- Concurrency enforcement (max concurrent jobs per org)
- Timeout monitoring (kill jobs/runs that exceed limits)
- Retry logic (re-dispatch failed jobs up to max retries)
- Cron schedule triggering

**Does NOT own:** Runner identity, log content, secret values, artifact storage

**Key Entities:** `PipelineRun`, `Job`, `Step` (status only, not content)

**Language:**
- "create run"
- "dispatch job"
- "resolve dependencies"
- "advance run state"
- "timeout job"

**Integration with Trigger Ingestion:**
- Consumes from `pipeline-triggers` Kafka topic

**Integration with Pipeline Config:**
- Calls Parse API to get Workflow definition before creating jobs

**Integration with Runner Context:**
- Publishes `JobDispatchEvent` to `job-queue` Kafka topic
- Consumes `JobStatusEvent` from `job-status` Kafka topic

---

### 4. Runner Execution Context

**Responsibility:** Execute individual jobs in isolated environments. Completely autonomous once job is assigned.

**Owns:**
- Job step execution (shell commands, action downloads)
- Container lifecycle management (spin up, execute, tear down)
- Log line streaming to Log Streaming Context
- Artifact upload to Artifact Context
- Heartbeat reporting to Orchestration Context
- Action resolution and caching (download reusable actions)
- Environment variable injection (regular + secrets)
- Workspace management (git checkout, file permissions)

**Does NOT own:** Job scheduling decisions, secret storage, log persistence, pipeline definition parsing

**Key Entities:** `RunnerAgent` (the process), `JobExecution` (local state of running job)

**Language:**
- "execute job"
- "run step"
- "stream logs"
- "report heartbeat"
- "upload artifact"

**Runner Autonomy Principle:**
Once a runner picks up a job, it can execute the job to completion even if the control plane (Scheduler, API) is temporarily unavailable. It only needs the Scheduler to report final status.

---

### 5. Log Management Context

**Responsibility:** Buffer, store, and stream execution logs.

**Owns:**
- Real-time log line buffer (Redis — last N lines per job)
- Async log persistence to object storage (S3 gzipped chunks)
- Log line masking (replace secret values with `***` before storage)
- Server-Sent Events (SSE) endpoint for browser streaming
- Log replay for completed jobs (serve from S3)
- Log search/indexing for completed runs (future: Elasticsearch)

**Does NOT own:** Runner lifecycle, job status, secret values (only masked patterns)

**Key Entities:** `LogChunk` (time-windowed batch of lines), `LogStream` (open SSE connection), `MaskedPattern` (per-job secrets to redact)

**Language:**
- "ingest log chunk"
- "mask secrets"
- "flush to storage"
- "stream to browser"
- "replay log"

**Anti-Corruption Layer: Secret Masking**
Runner sends raw log bytes. Log Service has a `MaskingProcessor` that receives secret patterns (not values) from Scheduler at job start: `{jobId: "abc", patterns: ["\\*\\*\\*\\*"]}`. Patterns are the masked form — Log Service never needs to know actual secret values. This ACL prevents secret context from leaking into log context.

---

### 6. Secret Management Context

**Responsibility:** Secure storage and controlled injection of secrets.

**Owns:**
- Secret CRUD API (write-only value, read metadata only)
- Encryption/decryption (KMS integration)
- Access control: only runner assigned to a specific job can fetch secrets for that job
- Audit trail of all secret accesses (who, when, which job, which secret)
- Short-lived secret injection tokens (runner presents job token, gets secrets once)
- Secret rotation support (update value without changing name)

**Does NOT own:** Secret validation, secret business logic (secrets are opaque strings)

**Key Entities:** `Secret`, `SecretAccessToken` (short-lived, job-scoped), `AuditEntry`

**Language:**
- "store secret"
- "issue access token"
- "decrypt and inject"
- "audit access"

**Security Boundary:**
Secret Service is deployed with separate Kubernetes namespace, separate network policy (only runner pods can reach it), and separate encryption keys. This hard boundary prevents accidental secret leakage through shared services.

---

### 7. Artifact Management Context

**Responsibility:** Store and serve job-produced artifacts.

**Owns:**
- Presigned URL generation for direct runner-to-S3 upload
- Artifact metadata (name, size, retention, expiry)
- Quota enforcement per organization
- Artifact download (presigned URL or proxied)
- Retention cleanup (background job deletes expired artifacts)
- Cross-job artifact sharing within a run (Job B downloads Job A's artifacts)

**Does NOT own:** Run/job lifecycle, secret management, log content

**Key Entities:** `Artifact`, `ArtifactQuota`, `RetentionPolicy`

**Language:**
- "request upload URL"
- "register artifact"
- "download artifact"
- "enforce quota"
- "expire artifact"

---

### 8. Notification Context

**Responsibility:** Notify external systems and users about pipeline events.

**Owns:**
- Slack/email/webhook notification delivery
- Notification rules (e.g., "notify on failure only")
- Retry logic for failed notification deliveries
- Template rendering (success/failure messages with run details)
- PR status check updates (post status back to GitHub/GitLab)
- Deployment environment status (integrates with GitHub Environments API)

**Does NOT own:** Run state (subscribes to events), secret storage (notification tokens stored separately)

**Language:**
- "deliver notification"
- "update PR status"
- "render template"
- "retry notification"

---

## Context Map

```
Trigger Ingestion
      │ PipelineTriggerEvent (Kafka)
      ▼
Pipeline Config Context ◄─── Scheduler calls Parse API
      │
      ▼
Execution Orchestration (Scheduler)
      │ JobDispatchEvent (Kafka)       │ ConsumesJobStatusEvent
      ▼                                ▼
Runner Execution Context ─────────────────────────────
      │                 │              │
      │ LogChunk        │ ArtifactUpload  │ SecretFetch
      ▼                 ▼              ▼
Log Management    Artifact Mgmt    Secret Mgmt
      │
      ▼
Browser (SSE)

Execution Orchestration ──→ Notification Context (via RunCompleted event)
```

---

## Integration Contracts

| From | To | Type | Contract |
|---|---|---|---|
| Trigger Ingestion | Scheduler | Kafka | `PipelineTriggerEvent {repoId, orgId, commitSha, branch, eventType, webhookPayload}` |
| Scheduler | Pipeline Config | REST | `GET /parse?repoId=&commitSha=` → `Workflow` |
| Scheduler | Runner Pool | Kafka | `JobDispatchEvent {jobId, orgId, repoId, commitSha, runsOn, steps, env, secretNames, artifactConfig}` |
| Runner | Scheduler | Kafka | `JobStatusEvent {jobId, status, stepStatuses, startedAt, completedAt, exitCode}` |
| Runner | Log Service | HTTP stream | Chunked POST `/logs/{jobId}` with raw log bytes |
| Runner | Secret Service | gRPC | `GetSecrets(jobToken, secretNames) → Map<name, value>` |
| Runner | Artifact Service | REST | `POST /artifacts/upload-url {jobId, name}` → `{presignedUrl}` |
| Scheduler | Log Service | REST | `POST /jobs/{jobId}/mask-patterns {patterns: []}` |
| Scheduler | Notification Context | Kafka | `RunCompletedEvent {runId, status, orgId, commitSha, branch, duration}` |

---

## Anti-Corruption Layers

**Runner ↔ Secret Service:**
Runner presents a short-lived `JobToken` (issued by Scheduler at dispatch time, ~15 min TTL). Secret Service validates token, decrypts secrets, injects as response. If token expired/invalid → secrets denied. Runner doesn't have long-lived credentials to the Secret Service.

**Log Service ↔ Runner:**
Log Service never parses log content — it's opaque bytes + mask patterns. Log Service doesn't know what secrets look like, only their masked form (the pattern string `***`). This prevents secret values from ever reaching Log Service in cleartext.

**Notification ↔ Scheduler:**
Notification context subscribes to events but never calls Scheduler APIs. Decoupled — Notification failure doesn't affect run execution.

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Separate Secret Context | Strict security boundary; different access control | Extra service call at job start |
| Kafka for cross-context events | Durability; Scheduler restart doesn't lose triggers | Kafka operational overhead |
| Parse workflow on each run | Git is source of truth; no sync issues | Re-parsing cost; invalid YAML fails at trigger |
| Log masking at Log Service | Centralized masking; runner can't forget to mask | Log Service must know patterns before log lines arrive (startup race) |

---

## Interview Discussion Points

- **What is the consistency boundary for a PipelineRun?** The Scheduler owns run and job state. No two services update run status simultaneously — Scheduler is the single writer for state transitions. Runners report to Scheduler via Kafka events; Scheduler makes the state change
- **How do you prevent a malicious runner from reading other orgs' secrets?** JobToken scoped to specific jobId + orgId. Secret Service validates token signature and checks that the job belongs to the requesting runner (runner-to-job mapping stored by Scheduler). Runner receives ONLY the secrets for its specific job
- **Why is Log Service separate from the runner?** Runner should be focused on execution, not log infrastructure. Separating allows: independent log scaling, centralized secret masking, log search indexing, and log streaming to multiple consumers simultaneously (browser, CI integration tools)
- **What happens if Notification service is down?** RunCompletedEvents accumulate in Kafka. When Notification recovers, it processes the backlog. Notifications are delayed but not lost. Execution orchestration is unaffected
