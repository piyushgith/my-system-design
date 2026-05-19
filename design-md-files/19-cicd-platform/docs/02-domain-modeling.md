# 02 — Domain Modeling: CI/CD Platform

---

## Objective

Define core domain entities, value objects, aggregates, state machines, and domain events for a CI/CD platform. Establish clear vocabulary and invariants that survive implementation changes.

---

## Core Domain Concepts

### Organization
The top-level multi-tenant unit. Each organization owns repositories, secrets, runners, and pipeline runs.

**Properties:**
- `orgId`: UUID, globally unique
- `name`: slug (e.g., `my-company`)
- `plan`: enum (FREE, PRO, ENTERPRISE)
- `concurrentJobLimit`: max simultaneous jobs (enforced by scheduler)
- `artifactStorageQuotaGb`: total artifact storage cap
- `runnerPool`: shared (platform runners) or self-hosted (per org)

**Invariants:**
- All repositories, secrets, and runs are scoped to exactly one organization
- Concurrent job count never exceeds `concurrentJobLimit` per org

---

### Repository
A git repository registered with the platform. Source of pipeline definitions and webhook triggers.

**Properties:**
- `repoId`: UUID
- `orgId`: parent organization
- `gitUrl`: repository clone URL
- `provider`: enum (GITHUB, GITLAB, BITBUCKET, SELF_HOSTED)
- `defaultBranch`
- `webhookSecret`: HMAC key for webhook signature validation (encrypted at rest)
- `accessToken`: encrypted PAT or installation token for cloning

**Repository does NOT own pipeline definitions** — those live in the repository's YAML files (and are parsed dynamically), not in the platform database.

---

### Workflow
Parsed representation of a pipeline definition file (`.github/workflows/ci.yml` equivalent). Workflows are re-parsed on every run trigger — not stored as immutable records. This ensures the pipeline definition in code is always authoritative.

**Properties:**
- `name`: human-readable
- `triggers`: list of trigger conditions (push branches, PR events, cron, manual)
- `jobs`: map of jobId → JobDefinition
- `defaults`: env vars, working directory defaults

**JobDefinition:**
- `jobId`: local name within workflow
- `needs`: list of upstream jobIds (dependency)
- `runsOn`: runner label (e.g., `ubuntu-22.04`, `self-hosted`)
- `strategy.matrix`: matrix parameter combinations
- `steps`: ordered list of StepDefinition
- `timeout`: per-job timeout in minutes
- `continueOnError`: bool

**StepDefinition:**
- `name`: human-readable
- `uses`: reference to reusable action (`org/action@version`) OR
- `run`: inline shell command
- `env`: additional env vars for this step
- `with`: input parameters (for `uses` steps)
- `if`: conditional expression (run only if condition is true)

---

### PipelineRun (Aggregate Root)
An instantiation of a Workflow triggered by a specific event. The PipelineRun aggregate is the central consistency boundary for execution state.

**Properties:**
- `runId`: UUID
- `repoId`, `orgId`
- `workflowName`
- `triggerEvent`: what caused this run (push, PR, manual, cron)
- `commitSha`: Git commit that triggered the run
- `branch`
- `status`: enum (PENDING | QUEUED | IN_PROGRESS | SUCCEEDED | FAILED | CANCELLED | TIMED_OUT)
- `createdAt`, `startedAt`, `completedAt`
- `jobs`: list of Job (child entities)

**Invariants:**
- `status` transitions are one-directional (no transition from SUCCEEDED back to IN_PROGRESS)
- Run is `SUCCEEDED` only when all required jobs succeed
- Run is `FAILED` if any non-optional job fails
- Run can only be `CANCELLED` if in PENDING, QUEUED, or IN_PROGRESS state

### PipelineRun State Machine

```
                     ┌─────────┐
                     │ PENDING  │ (created, not yet queued)
                     └────┬────┘
                          │ Scheduler picks up
                          ▼
                     ┌─────────┐
                     │ QUEUED   │ (in job queue, waiting for runner)
                     └────┬────┘
                          │ Runner picks up first job
                          ▼
                     ┌───────────┐
                     │ IN_PROGRESS│
                     └─────┬─────┘
              ┌────────────┼────────────┐
              ▼            ▼            ▼
         ┌─────────┐  ┌────────┐  ┌──────────┐
         │SUCCEEDED│  │ FAILED │  │TIMED_OUT │
         └─────────┘  └────────┘  └──────────┘
              
Any state → CANCELLED (user cancels, except terminal states)
```

---

### Job (Entity within PipelineRun)
A discrete execution unit within a run. Maps to one container/runner.

**Properties:**
- `jobId`: UUID
- `runId`: parent run
- `workflowJobId`: local name from pipeline YAML (e.g., "build")
- `status`: enum (QUEUED | WAITING | RUNNING | SUCCEEDED | FAILED | CANCELLED | SKIPPED | TIMED_OUT)
- `runnerId`: which runner executed this job
- `startedAt`, `completedAt`
- `matrixIndex`: for matrix builds, which combination this job represents
- `steps`: ordered list of Step (child entities)
- `logPath`: S3 path to consolidated log file
- `artifactIds`: list of artifacts produced

**WAITING** state: job has unmet `needs:` dependencies — waiting for upstream jobs to complete.
**SKIPPED** state: job's `if:` condition evaluated to false OR all upstream jobs were SKIPPED.

### Job State Machine

```
QUEUED → WAITING (has unmet dependencies)
WAITING → QUEUED (all dependencies met)
QUEUED → RUNNING (runner picks up)
RUNNING → SUCCEEDED
RUNNING → FAILED
RUNNING → TIMED_OUT
Any → CANCELLED
WAITING → SKIPPED (if upstream job failed and no continue-on-error)
```

---

### Step (Entity within Job)
A single step in a job. Runs sequentially. Atomic execution unit.

**Properties:**
- `stepId`: sequential index within job
- `name`
- `type`: enum (RUN | USES | CHECKOUT | UPLOAD_ARTIFACT | DOWNLOAD_ARTIFACT)
- `status`: enum (PENDING | RUNNING | SUCCEEDED | FAILED | SKIPPED)
- `startedAt`, `completedAt`
- `exitCode`: process exit code (0 = success)
- `logStartLine`, `logEndLine`: range within consolidated job log file

---

### Runner
A compute resource that picks up and executes jobs.

**Properties:**
- `runnerId`: UUID
- `orgId`: owning org (null for platform-owned shared runners)
- `label`: string (e.g., `ubuntu-22.04`, `self-hosted`, `gpu-runner`)
- `type`: enum (MANAGED | SELF_HOSTED)
- `status`: enum (IDLE | BUSY | OFFLINE)
- `currentJobId`: if BUSY
- `lastHeartbeatAt`
- `capabilities`: set of labels/tags for job matching
- `version`: runner agent version

**For Kubernetes-based managed runners:** Runner is a K8s Pod. `runnerId` = pod name. Status managed via pod lifecycle.

---

### Secret
An encrypted key-value pair scoped to an org or repository.

**Properties:**
- `secretId`: UUID
- `orgId`, `repoId` (nullable — org-level secrets accessible to all repos)
- `name`: environment variable name (e.g., `AWS_SECRET_KEY`)
- `encryptedValue`: KMS-encrypted bytes
- `createdAt`, `updatedAt`
- `lastAccessedAt`

**Invariants:**
- Secret value is NEVER returned in API responses (write-only from user perspective)
- Secret name must be uppercase letters, numbers, underscores only
- `value` in log output masked with `***` before storage

---

### Artifact
A file or archive produced by a job and stored for downstream use or download.

**Properties:**
- `artifactId`: UUID
- `runId`, `jobId`
- `name`: logical name (e.g., "test-reports", "docker-image")
- `storageKey`: S3 object key
- `sizeBytes`
- `contentType`
- `uploadedAt`
- `expiresAt`: deletion timestamp (based on retention policy)

---

## Domain Events

| Event | Trigger | Consumers |
|---|---|---|
| `PipelineTriggerReceived` | Git webhook arrives | Scheduler (create run) |
| `RunCreated` | Scheduler creates run | Notification service |
| `JobQueued` | Scheduler dispatches job | Runner pool (consume job) |
| `JobStarted` | Runner picks up job | Status service, UI |
| `StepCompleted` | Step finishes (success or fail) | Status service |
| `JobCompleted` | All steps done | Scheduler (check dependent jobs), notification |
| `RunCompleted` | All jobs terminal | Notification service, external webhook |
| `RunCancelled` | User cancels or timeout | Runner (stop current job) |
| `ArtifactUploaded` | Runner uploads artifact | Artifact service (record metadata) |
| `SecretAccessed` | Runner fetches secret | Audit log |

---

## Value Objects

| Object | Description |
|---|---|
| `TriggerCondition` | Immutable config: branch pattern + event type (push, PR, cron) |
| `JobDependency` | Immutable `(jobId, condition)` pair for `needs:` relationships |
| `MatrixCombination` | Immutable parameter set for matrix build instance |
| `CommitRef` | Immutable `(sha, branch, tag)` — git identity of run |
| `RunnerLabel` | String label for job→runner matching |
| `CronSchedule` | Parsed cron expression with timezone |
| `RetentionPolicy` | Immutable `(days, maxSizeGb)` tuple for artifact cleanup |

---

## Aggregate Boundaries

```
┌───────────────────────────────────────────────┐
│  PipelineRun (Aggregate Root)                  │
│  ├── Jobs[]                                    │
│  │   ├── Steps[]                              │
│  │   └── Artifacts[]                          │
│  └── TriggerEvent (value object)              │
└───────────────────────────────────────────────┘

┌───────────────────────────────────────────────┐
│  Organization (Aggregate Root)                 │
│  ├── Repositories[]                           │
│  ├── Secrets[]                                │
│  └── Runners[] (self-hosted only)             │
└───────────────────────────────────────────────┘
```

**Why PipelineRun as aggregate root?** All job and step state transitions must maintain consistency within a run. No two jobs can simultaneously claim to be "the last job in the run" without coordination. The run aggregate enforces this invariant.

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Workflow parsed on each run (not stored) | Git is source of truth; no sync problem | Re-parse overhead; invalid YAML fails at trigger time not at commit |
| Run as aggregate containing jobs | Consistent status aggregation | Large aggregate for runs with 100+ jobs; pagination needed |
| Separate domain for secrets | Security boundary — secret service has different access control | Extra service call at job start |

---

## Interview Discussion Points

- **Where is the pipeline definition stored?** In Git, as YAML. Platform fetches it at trigger time. This is the "configuration as code" philosophy — the repo IS the source of truth, not the platform database
- **How do you handle a pipeline YAML syntax error?** Validation on parse at trigger time → run created with status=FAILED immediately, no runner used. Error message includes YAML parse error with line number
- **What is the difference between WAITING and QUEUED for a job?** QUEUED = ready to run (no dependencies, waiting for runner). WAITING = dependencies not yet complete. Scheduler only dispatches QUEUED jobs to job queue
- **How does matrix build work?** JobDefinition with `strategy.matrix` explodes into N Job entities at run creation time. E.g., `os: [ubuntu, windows] × node: [16, 18]` = 4 jobs. Each job gets a MatrixCombination value object identifying its parameters
