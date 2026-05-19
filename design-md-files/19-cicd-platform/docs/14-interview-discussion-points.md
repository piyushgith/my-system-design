# 14 — Interview Discussion Points: CI/CD Platform

---

## Objective

Prepare for staff-level system design interviews by anticipating follow-up questions, common traps, scaling evolution discussions, and the "what breaks first" analysis specific to a distributed CI/CD execution platform.

---

## Expected Interviewer Questions by Area

### Architecture Fundamentals

**Q: Why is the CI/CD platform event-driven rather than request-response?**
> Pipeline execution is inherently async — a push event triggers a build that takes 5–30 minutes. The triggering client (GitHub webhook) needs an immediate response, but the work happens later. Event-driven (Kafka) decouples trigger acceptance from execution: webhook ACKs immediately, Scheduler processes asynchronously. This also gives durability (triggers not lost on Scheduler restart) and backpressure (job queue absorbs burst while runners scale up). A synchronous design would require the webhook endpoint to hold a connection open for 30 minutes — unworkable.

**Q: How do you ensure exactly-once job execution?**
> Defense in depth: (1) Kafka consumer groups ensure each JobDispatchEvent consumed by exactly one runner group, (2) Runner performs optimistic lock: `UPDATE jobs SET status=RUNNING WHERE job_id=? AND status=QUEUED` — if 0 rows updated, another runner claimed it (runner discards), (3) Idempotent DB writes for status updates: `ON CONFLICT DO UPDATE` ensures duplicate status reports are handled safely. Not perfectly exactly-once (if runner crashes after starting, job may re-run from scratch) but all failure modes are handled safely.

**Q: Why did you choose PostgreSQL for run state over a NoSQL store?**
> Run state is strongly relational: run → jobs → steps hierarchy, with transactional state transitions. PostgreSQL ACID guarantees ensure: (1) a job transitions from QUEUED to RUNNING atomically (no two runners claim same job), (2) run status transitions are consistent (FAILED if any non-optional job failed), (3) complex queries (find all QUEUED jobs with met dependencies) are natural with SQL JOINs. Cassandra/DynamoDB would require application-level consistency logic that PostgreSQL handles natively.

---

### Scalability

**Q: How does the system handle a monorepo with 10,000 files and 500 workflows?**
> 500 workflows × 5 jobs each = 2500 jobs per push. At 100 pushes/day to that repo: 250,000 jobs/day from one repo. Mitigations: (1) Path filters: each workflow runs only if matching path changes → most workflows skip irrelevant pushes, (2) Concurrency limits per org prevent one repo from consuming all runners, (3) Workflow caching (YAML parsed per commitSha, cached immutably) — parse cost is one-time per commit. Key design: concurrency limits protect the platform; path filters protect the developer (don't run unaffected workflows).

**Q: How do you scale the Scheduler if it becomes a bottleneck?**
> Single leader Scheduler handles ~1000 job dispatches/sec (DB writes + Kafka publishes). At 10M jobs/day: ~120 dispatches/sec — safe headroom. If throughput exceeds single instance: shard by org_id. Consistent hash: `shard = hash(orgId) % numShards`. Each shard is an independent leader-elected Scheduler pair. Cross-shard dependencies (a job in one shard waiting on another shard's job): coordinator pattern or simpler — never allow cross-org dependencies (workflows are org-scoped anyway).

**Q: What limits job parallelism?**
> Hard ceiling: partition count of job-queue Kafka topics (96 partitions = 96 concurrent consumer threads). Practical ceiling: cluster autoscaler max node count × runners per node (e.g., 500 nodes × 20 runners = 10K). Business ceiling: concurrency limit per org. Soft ceiling: database connection pool under burst (PgBouncer prevents connection exhaustion). The key insight: add more Kafka partitions + more nodes to scale linearly.

---

### Reliability

**Q: What happens if a runner is preempted by the cloud provider (spot instance)?**
> Kubernetes receives SIGTERM 30 seconds before preemption. Runner agent: (1) stops accepting new jobs, (2) sends graceful shutdown signal to current job subprocess, (3) flushes partial logs to S3, (4) publishes `JobStatusEvent(PREEMPTED)` to Kafka, (5) exits. Scheduler receives PREEMPTED event → re-queues job (if retry_count < max). New runner on new instance picks it up. Job restarts from scratch. Impact: ~1-2 minute delay for affected job. For well-designed pipelines (< 30 min jobs with artifacts), this is acceptable at 70% spot discount.

**Q: How do you prevent secrets from appearing in logs?**
> Runner-side masking before streaming: runner maintains a `Set<String> secretValues` (from Secret Service). All log bytes pass through `MaskProcessor`: replace any occurrence of secret value with `***` before sending to Log Service. Edge cases: (1) partial matches — mask even partial secret appearances (trunc to min 8 chars), (2) base64/URL-encoded secrets — also mask encoded forms, (3) secrets split across log chunks — buffer last N bytes to handle boundary splits. Log Service only receives already-masked bytes — never exposed to actual values.

**Q: What is the SLA for the Secret Service and what happens if it's down?**
> Secret Service SLA: 99.99% (< 52 min downtime/year). In-progress jobs: unaffected (secrets already in memory). New jobs: fail to start (can't fetch secrets). Impact: runners report `SECRET_FETCH_FAILED`, Scheduler re-queues (up to max retries). With 3 replicas + PodDisruptionBudget (min 2 available), Secret Service can survive 1 pod failure + rolling deploy without downtime.

---

### Design Decisions

**Q: Why store workflow YAML in the repository instead of the platform database?**
> Git is the source of truth. Benefits: (1) version controlled — pipeline changes are reviewed in PR, (2) rollback — revert a bad workflow change by reverting the commit, (3) no sync problem — platform always uses the exact YAML from the commit that triggered the run, (4) audit — who changed what workflow and when is in git history. Alternative (platform-stored): risk of platform database and git diverging; workflow changes can't be code-reviewed; harder to rollback.

**Q: How does log streaming work and what is the latency?**
> Runner executes step → captures stdout/stderr → buffers 100 lines or 100ms → sends chunked HTTP POST to Log Service. Log Service: RPUSH to Redis + PUBLISH to pubsub channel. Browser SSE consumer subscribed to pubsub → receives notification → reads from Redis buffer → sends to browser. Total latency: 100ms (runner buffer) + 1ms (Redis) + browser render = ~150–300ms. P99: < 1 second. Alternative (direct websocket runner→browser): lower latency but routing complexity (how does browser connect to specific runner pod?).

**Q: Why use presigned S3 URLs for artifact upload instead of proxying through the service?**
> Direct runner→S3 upload bypasses the Artifact Service for the data plane: (1) Service doesn't handle hundreds of MB/sec of artifact bandwidth — S3 handles it natively, (2) Artifact Service doesn't become a bandwidth bottleneck, (3) Presigned URL is temporary (15 min) and scoped to specific S3 key — no credential leakage, (4) S3 handles multipart upload, retry, checksum natively. Downside: URL expiry; runner must start upload before 15 min. For large uploads: request fresh presigned URL if old one expires.

---

## Scaling Evolution Questions

**Q: How does the platform evolve from 100 to 10M jobs/day?**

> Phase 1 (100 jobs/day): Monolithic, single PostgreSQL, basic queue. Phase 2 (100K jobs/day): Extract runner pool (K8s Deployment), add Kafka, separate Scheduler. Phase 3 (1M jobs/day): Read replicas, log streaming to Redis/S3, runner autoscaling, secret service as separate microservice. Phase 4 (10M jobs/day): PostgreSQL sharding by org, Kafka scaling (more partitions), multi-region, tiered log storage. The key scaling lever at each phase: Phase 2=runner isolation, Phase 3=DB read scaling, Phase 4=DB write sharding.

**Q: How would you add support for Windows runners?**
> Windows runners are different: (1) Docker images are Windows-based (different registry, larger, slower pull), (2) Shell is CMD/PowerShell not bash, (3) Path separators differ. Implementation: (1) Separate `job-queue-windows-2022` Kafka topic, (2) Windows runner pool deployed on Windows K8s nodes, (3) Runner agent compiled for Windows (Go or Java, both cross-platform), (4) Step execution: detect OS, use correct shell (`cmd /c` vs `bash`). The platform layer (Kafka, DB, secrets) is OS-agnostic — only the runner agent needs OS-specific implementation.

---

## Common Mistakes (Trap Questions)

**Trap: "Should the Secret Service cache secrets in Redis?"**
> No. Secret caching risks stale values after rotation. If a developer rotates a compromised key, they expect the new value to take effect immediately. A cache with even 1-minute TTL would serve the old (compromised) value to jobs starting during that window. Consistency > performance for secrets. Secret fetch is a one-time operation per job start — the latency (1-2ms gRPC call) is negligible compared to job duration.

**Trap: "Why not use a single Kafka topic for all jobs?"**
> Single topic → runners for all labels compete on same partitions. A backlog of `ubuntu-22.04` jobs would delay `self-hosted` jobs (partition assignment is round-robin — slow consumer on ubuntu-partitions doesn't free self-hosted capacity). Separate topics → independent consumer groups, independent HPA scaling, independent backlog handling. The operational overhead of multiple topics is worth the isolation.

**Trap: "Can you use the CI/CD platform to deploy the CI/CD platform itself?"**
> Yes — this is dogfooding and is highly desirable. Key requirement: maintain a "break glass" deployment path that doesn't depend on the platform (e.g., `kubectl apply` with cluster admin credentials) for emergencies when the platform itself is broken. Dogfooding provides constant real-world testing of the platform and creates strong incentive to fix platform issues quickly.

---

## Staff/Principal Engineer Discussion Points

**System Boundaries:**
- CI/CD platform is infrastructure — it's in the critical path for all engineering teams. Its reliability directly determines how fast the engineering org can ship. This creates extreme pressure: any platform outage blocks 100s of developers
- The platform runs untrusted code (user workflows) with access to org secrets. This is a unique security challenge vs most systems — the executor IS the attack surface
- Build caches are shared state between runs — cache poisoning attacks are a real threat

**What Would Break First Analysis:**

| At Scale | What Breaks | Signal |
|---|---|---|
| 10K concurrent jobs | PostgreSQL connection exhaustion | `FATAL: remaining connection slots reserved` |
| Viral open-source repo | Single-org job queue starvation | Other orgs see increased job start latency |
| Rolling deploy of runner image | HPA lags behind; brief runner shortage | Job queue depth spike |
| Redis memory exhaustion (log buffer) | Log Service OOM, pod restarts, log loss | `cicd_log_redis_buffer_size_bytes > 90%` |
| Kafka consumer lag grows | Jobs queue but not dispatched | `cicd_job_queue_depth` growing monotonically |
| Secret Service pod restart | New jobs fail for 2 min (cold start) | `cicd_secret_fetch_error_rate > 0` |

**Architecture Critique:**
- **No mid-job checkpoint/resume** is the biggest user experience gap. A 2-hour build that fails at 119 minutes due to a transient network error restarts from scratch. Build graph tools (Gradle, Bazel) provide internal incrementality — but platform-level checkpointing would be powerful
- **Scheduler as single-leader** is correct for simplicity but becomes bottleneck at very high throughput. Sharding by org_id defers the problem but adds routing complexity
- **Job start latency (30–60s cold start)** is a constant developer pain point. Pre-warmed runner pools mitigate but don't eliminate. True solution: persistent runner processes with workspace reset (like GitHub's "just-in-time" runners)

---

## Senior vs Staff vs Principal Lens

| Level | Focus |
|---|---|
| Senior | "How does job dispatch work?" "How do secrets stay secure?" "What happens when a runner crashes?" |
| Staff | "How do you scale to 100K concurrent jobs?" "Design fair-share multi-tenant scheduling." "How do you prevent pipeline injection?" |
| Principal | "How would you design an execution model that supports DAGs across organizations?" "What are the fundamental tradeoffs between safety and developer flexibility in pipeline definitions?" "Design for 1-second job start latency." |

---

## Interview Discussion Points

- **How would you design exactly-once job execution?** Optimistic locking in DB (compare-and-swap). Idempotent job steps (output to deterministic path, skip if artifact exists). Deduplication via Kafka message key + consumer group. Note: true exactly-once is hard — most systems settle for at-least-once with idempotent runners
- **What is the hardest part of building a CI/CD platform?** Secret security with high developer usability. Secrets must be secure (isolated per job, never logged, rotatable) but also easy to configure (no per-job provisioning). OIDC federation is the elegant solution — no secrets needed at all for cloud credentials
- **How does a CI/CD platform differ from a task scheduler (like Kubernetes Jobs)?** CI/CD platform adds: workflow DAG execution, secret injection, log streaming with secret masking, artifact management, source control integration (git checkout), environment promotion, PR status checks. Kubernetes Jobs are the execution substrate; CI/CD is the layer on top
- **What would you change if starting over?** Build for checkpoint/resume from day 1 — it's a first-class product feature, not an afterthought. Design the runner as a separate open-source binary (GitHub did this — `actions/runner` is open source). First-class OIDC support before static secrets — eliminates the secret management burden for cloud deployments
