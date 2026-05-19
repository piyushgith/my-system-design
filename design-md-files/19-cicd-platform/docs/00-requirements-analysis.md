# 00 — Requirements Analysis: CI/CD Platform (Mini GitHub Actions / Jenkins)

---

## Objective

Define functional and non-functional requirements, constraints, assumptions, and capacity estimates for a production-grade continuous integration and continuous delivery platform capable of running distributed build and deployment pipelines at scale.

---

## Functional Requirements

### Core (MVP)
- Users define pipelines as YAML/JSON configuration files checked into source control
- Pipeline triggered on code push, pull request open/update, or manual invocation
- Pipeline consists of sequential or parallel **jobs**; each job consists of ordered **steps**
- Each step runs a shell command or a reusable action in an isolated container
- Pipeline status (pending, running, succeeded, failed, cancelled) tracked and exposed via UI and API
- Step logs streamed in real-time to the user (not batched at end)
- Jobs run in ephemeral environments: container per job, destroyed after completion
- Artifacts (build outputs, test reports) can be uploaded and downloaded across jobs

### Extended (V1)
- **Job dependencies:** jobs can declare `needs: [build]` → run only after specified jobs complete
- **Matrix builds:** run same job across multiple parameter sets (OS × language version)
- **Reusable workflows / composite actions:** define actions as reusable building blocks
- **Secrets management:** encrypted secrets injected as environment variables at runtime (never visible in logs)
- **Environment promotion:** deploy to staging → manual gate → deploy to production
- **Timeout enforcement:** per-job and per-step configurable timeouts; kill runaway builds
- **Pipeline caching:** cache dependencies (node_modules, Maven .m2) between runs
- **Concurrency control:** limit concurrent runs per branch/workflow to prevent queue flooding
- **Notification hooks:** Slack/webhook notification on success/failure

### Advanced (V2+)
- **Self-hosted runners:** users register their own machines as runners for private network access
- **Runner autoscaling:** dynamic pool of runner VMs/pods scaled by queue depth
- **Artifact retention policies:** configurable retention; tiered to object storage
- **GitHub/GitLab integration:** webhook-driven triggers, PR status checks, deployment environments
- **OIDC federation:** runners can request short-lived cloud credentials (AWS/GCP) without storing static secrets
- **Flaky test detection:** track test result history, flag consistently-flaky tests
- **Pipeline visualization:** DAG view of job dependencies with real-time status
- **Cost tracking:** per-runner-minute attribution per project/team

---

## Non-Functional Requirements

| Property | Target |
|---|---|
| Availability | 99.9% (control plane); 99.5% (runner pool) |
| Job start latency (trigger → running) | < 30 seconds for queue wait; < 60s including runner boot |
| Log streaming latency | < 2 seconds from step execution to browser display |
| Throughput | 10,000 concurrent jobs; 1M jobs/day |
| Pipeline definition parse time | < 1 second |
| Artifact upload/download | < 2 minutes for 1 GB artifact |
| Scalability | Horizontal — add runner nodes independently |
| Security | Job isolation (containers); secret values never logged |

---

## Assumptions

- Source code is hosted in Git (GitHub, GitLab, Bitbucket, or self-hosted)
- Pipelines are defined in repository YAML files (not a web UI)
- Runners are stateless containers or VMs (no persistent build state between runs)
- Each job runs in a fresh container — no residual state from previous runs
- Docker is available on runner nodes; Kubernetes is the runner orchestration layer
- Network access from runners to the internet is permitted (for package downloads)
- Secrets are rotated by operators; platform provides storage and injection only
- Multi-tenancy: multiple teams/organizations share the same platform infrastructure
- Code changes are the primary trigger; time-based (cron) triggers are secondary

---

## Constraints

- Pipeline YAML schema must be validated before job dispatch — invalid syntax = immediate failure, no runner wasted
- Secrets must never appear in logs (masked before streaming)
- A job cannot read secrets from other pipelines or other organizations
- Artifact storage is bounded per project (quota enforced)
- Runner machines must not retain filesystem artifacts between jobs (ephemeral)
- Step log output size bounded (default max 10 MB per step — truncated after)

---

## Scale Estimation

### Traffic Assumptions

| Metric | Value |
|---|---|
| Organizations | 10,000 |
| Repositories | 500,000 |
| Pipelines triggered per day | 1,000,000 |
| Average jobs per pipeline | 5 |
| Average job duration | 5 minutes |
| Concurrent jobs at peak | 10,000 |
| Average log lines per job | 10,000 lines × 200 bytes = 2 MB |
| Artifact uploads per day | 500,000 |
| Average artifact size | 50 MB |

### Back-of-the-Envelope Calculations

**Runner capacity:**

```
Concurrent jobs: 10,000
Average job duration: 5 min
Required runner pool (steady state): 10,000 / 1 job per runner = 10,000 runners
Runner utilization target: 70% → provision 14,000 runners
```

**Log storage:**

```
1M jobs/day × 2 MB average logs = 2 TB/day raw
Compressed (gzip ~5:1): ~400 GB/day
7-day hot storage: 2.8 TB
90-day archive: 36 TB (cold object storage)
```

**Artifact storage:**

```
500K uploads/day × 50 MB = 25 TB/day
Retention: 30 days → 750 TB total (tiered to object storage after 7 days)
```

**Queue throughput:**

```
Peak trigger rate: 1M jobs/day / 86,400 sec = ~12 pipeline triggers/sec
Jobs dispatched: 12 × 5 jobs/pipeline = 60 jobs/sec
Queue: 60 messages/sec — trivial for any message queue
Peak burst: 10x = 600 jobs/sec
```

**Database writes:**

```
Pipeline state transitions: avg 10 per job × 1M jobs/day = 10M writes/day
Log writes: stream → chunk in object storage (not per-line writes to DB)
```

---

## Read/Write Patterns

| Operation | Pattern | Frequency |
|---|---|---|
| Pipeline trigger (webhook) | Write (create run) | 12/sec avg, 120/sec peak |
| Job dispatch (scheduler → runner) | Write + queue message | 60/sec avg, 600/sec peak |
| Step log streaming | Append (object storage) | 100K lines/sec across all jobs |
| Job status poll (UI) | Read (REST API) | High (100K RPS across all users) |
| Artifact upload | Large PUT to object storage | 6/sec avg |
| Artifact download | GET from object storage | 30/sec avg |
| Secret fetch (runner) | Read (encrypted) | 60/sec (one fetch per job start) |
| Webhook delivery (git provider → platform) | Write (trigger) | 12/sec avg |

---

## Latency Expectations

| Operation | Target P50 | Target P99 |
|---|---|---|
| Pipeline trigger to job queued | < 500ms | < 2s |
| Job queued to runner picked up | < 10s | < 30s |
| Runner start (container boot) | < 15s | < 45s |
| Log line appears in browser | < 2s | < 5s |
| Artifact upload (1 GB) | < 60s | < 120s |
| Pipeline status API | < 50ms | < 200ms |

---

## Availability Targets

| Component | Availability |
|---|---|
| Control plane (API + scheduler) | 99.9% |
| Runner pool | 99.5% (individual runners can fail; queue absorbs) |
| Log streaming | 99.5% (buffered; temporary gaps acceptable) |
| Secret service | 99.99% (job startup blocked if unavailable) |
| Artifact storage | 99.9% (backed by object storage SLA) |

---

## Tradeoffs Acknowledged at Requirements Level

| Decision | Tradeoff |
|---|---|
| YAML-in-repo vs UI-defined pipelines | YAML = version-controlled, reviewable; harder to debug syntax errors |
| Ephemeral containers per job | Clean isolation; slower start than persistent runners |
| Object storage for logs | Cheap and durable; slight latency vs in-memory streaming |
| Shared runner pool vs dedicated | Cost-efficient; noisy neighbor risk for CPU/IO |
| 30s p99 job start latency | Requires pre-warmed runner pool; cold start is ~2min |

---

## Interview Discussion Points

- **How do you prevent one team from starving others in the job queue?** Fair-share scheduling per organization — each org gets a share of the runner pool proportional to their tier/subscription. No single org can consume all runners
- **How do you handle a PR that triggers 100 workflow runs from mass-push?** Concurrency controls: limit concurrent runs per branch, per workflow. Auto-cancel in-progress runs when a new push arrives on same branch
- **What guarantees does the platform make about secret security?** Secrets encrypted at rest (KMS), decrypted only in runner memory, masked in log output before streaming, never stored in DB alongside run data
- **What happens when the control plane is down?** In-progress jobs on runners continue (runner is autonomous once job assigned). New jobs cannot be dispatched. Queue buffers triggers. Short outage: jobs queue up, resume on recovery. Long outage: triggers may expire
