# 16 — Advanced Improvements & Architecture Critique: CI/CD Platform

---

## Objective

Critique the core design decisions, identify scaling limits and tech debt risks, and define advanced improvements that would emerge at FAANG/enterprise scale. Prepare for staff-level "what would you challenge" discussions.

---

## Architecture Critique

### Critique 1: No Mid-Job Checkpoint = Expensive Failures for Long Builds

**The Problem:**
A job that takes 2 hours (large monorepo, comprehensive test suite) restarts from scratch on failure. If it fails at minute 119 due to a transient network timeout, the developer waits another 2 hours.

**Impact quantification:**
- 1% transient failure rate × average 30-minute job = 18 seconds of wasted compute/job on average
- For 1-hour jobs: 36 seconds wasted per job on average
- At 10M jobs/day: thousands of CPU-hours wasted on retries of near-complete jobs

**What better looks like:**
1. **Artifact-based checkpointing:** Job saves checkpoint artifacts at N% completion. On retry, restore from checkpoint S3 artifact. Requires job definition support: `checkpoint: every 25% complete`
2. **Build graph tools:** Gradle/Bazel/Buck provide incremental builds at the build tool level. CI platform can cache the build graph state, not just files. Bazel remote execution + remote cache achieves this without platform-level checkpointing
3. **Persistent runner workspaces (like GitHub larger runners):** Keep workspace between runs for the same branch. Only re-clone changes. Risk: shared state between runs can cause non-reproducibility

**Current mitigation:** Split large jobs into smaller jobs with artifact hand-off. A 2-hour monolith build becomes 8 × 15-minute jobs. Failure re-runs only the failed shard.

---

### Critique 2: Scheduler Single-Leader Limits Dispatch Throughput

**The Problem:**
Single active Scheduler processes all job dispatches. At 10M jobs/day (~120 jobs/sec), a single Spring Boot process handles this comfortably. But:
- At 100M jobs/day (10 GitHub-scale customers): 1200 dispatches/sec → PostgreSQL write bottleneck
- Scheduler downtime (5s) blocks all dispatches globally
- All dependency DAG evaluations run serially — complex DAGs (50+ jobs) add latency

**What breaks:**
- Scheduler process CPU saturation → dispatch latency grows → job start latency SLO violated
- PostgreSQL `jobs` table write contention under burst (100K jobs dispatched in 1 minute)

**Better approach:**
Shard Scheduler by `orgId`:
- Hash: `shard = hash(orgId) % numShards`
- Each shard is an independent leader-elected Scheduler pair
- Cross-shard dependencies: not needed (workflows are org-scoped)
- Each shard has dedicated Kafka consumer group partition range

**Tradeoff:** Routing layer needed for cross-org admin queries. Shard rebalancing when adding/removing shards. Operational complexity 3x vs single-leader.

---

### Critique 3: Runner Cold Start Latency (30–60s) is a Developer Pain Point

**The Problem:**
A developer pushes, waits for CI to start. With runner autoscaling + container image pull:
- Pod scheduling: 5–10s
- Container image pull (runner agent image ~200 MB): 10–20s
- Workspace setup (git clone): 5–15s
- Dependency restore (cache hit): 5s OR dependency install (cache miss): 30–120s

Total to first meaningful build output: **30–180 seconds** from push.

**Comparison:** GitHub Actions p50 job start: ~20s. CircleCI: ~15s. Jenkins persistent agents: ~3s.

**Mitigations:**

1. **Pre-warm base pool (current):** Keep N idle runners. Reduces scheduling/pull latency but not git clone latency.

2. **Runner workspace persistence (advanced):** Keep runner pods alive between jobs. Pre-clone repos on idle runners. On job start: `git fetch && git checkout` (~2s) instead of full clone (~15s). Risk: non-reproducibility from stale workspace state. Mitigation: detect and clean workspace for security-sensitive operations.

3. **Image pre-pull as DaemonSet:**
```yaml
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: runner-image-puller
spec:
  template:
    spec:
      initContainers:
      - name: pull
        image: cicd/runner-agent:latest
        command: ["echo", "image pulled"]
```
Every node pre-pulls runner image → pod start = 0s for image pull.

4. **Virtual machine snapshots (AWS SnapshotStart):** Pre-snapshot VMs with workspace ready → restore from snapshot = 2s boot instead of 60s cold start.

---

### Critique 4: Pipeline YAML is Expressive Enough to Be Dangerous

**The Problem:**
GitHub Actions YAML allows arbitrary shell commands, network access, and docker builds. A developer (or a compromised dependency) can:
1. Exfiltrate all org secrets if masking is bypassed
2. Mine cryptocurrency using org-paid compute
3. Inject malicious artifacts into the pipeline

**Real-world incidents:**
- CodeCov breach (2021): malicious bash script in CI exfiltrated env vars
- Action poisoning: popular GitHub Action maintainer account compromised → malicious action version

**Mitigations:**

1. **Action version pinning to SHA (not tag):**
```yaml
uses: actions/checkout@v3      # BAD: tag can be moved
uses: actions/checkout@abc123  # GOOD: SHA is immutable
```
Platform should WARN on tag usage, enforce SHA pinning for production workflows.

2. **Allowlist for outbound network access:** Restrict runner internet access to approved registries (npm, Maven Central, Docker Hub). Block arbitrary outbound connections. Difficult UX tradeoff — developers need flexibility.

3. **Action marketplace with security scanning:** All public actions scanned for secret exfiltration patterns before marketplace listing. Curated "verified" tier of actions.

4. **Fork PR protection:** Workflows from forks run with zero secrets, no write permissions to main branch artifacts.

---

### Critique 5: Artifact Storage Grows Without Governance

**The Problem:**
At 500K artifact uploads/day × 50 MB = 25 TB/day. With 30-day retention: 750 TB. Cost: ~$17,500/month on S3 (at $0.023/GB/month). Without governance:
- High-value artifacts (Docker images, signed releases) mixed with low-value artifacts (test reports, debug dumps)
- No incentive for developers to clean up artifacts (platform pays the bill)
- Quota enforcement is blunt (reject everything when quota exceeded)

**Better approach:**

1. **Tiered artifact storage:**
   - HOT (S3 Standard): last 7 days — fast access for downstream jobs
   - WARM (S3 Infrequent Access): 7–30 days — ~40% cheaper
   - COLD (S3 Glacier): 30–90 days — ~75% cheaper
   - Lifecycle rules: automatic tiering, no developer action needed

2. **Artifact tagging and retention policy:**
```yaml
- uses: upload-artifact@v2
  with:
    name: release-binary
    path: dist/
    retention-days: 90  # developer specifies
    tier: release        # keeps in WARM longer
```

3. **Cost attribution per project:** Show teams how much their artifacts cost. Teams with high storage bills self-regulate (delete old branches, compress artifacts).

4. **Deduplication:** SHA256 hash of artifact content. If same content uploaded from two runs: store once, reference twice. Saves 20-40% on common base artifacts.

---

## Advanced Improvements

### Improvement 1: Pipeline Analytics with Flaky Test Detection

**Current:** No insight into test health trends across runs.
**Improved:** Per-test result tracking with flakiness detection.

**Implementation:**
```
Runner parses JUnit XML after test run:
  {testCase: "UserLoginTest", status: FAILED, duration: 1.2s}

Results stored in ClickHouse (columnar, fast aggregation):
  CREATE TABLE test_results (
    test_class String, test_name String,
    run_id UUID, job_id UUID, repo_id UUID,
    status Enum('PASSED', 'FAILED', 'SKIPPED'),
    duration_ms UInt32,
    occurred_at DateTime
  ) ENGINE = MergeTree()

Flakiness detection query:
  SELECT test_name,
    countIf(status = 'FAILED') / count() AS failure_rate
  FROM test_results
  WHERE repo_id = ? AND occurred_at > now() - INTERVAL 30 DAY
  GROUP BY test_name
  HAVING failure_rate BETWEEN 0.05 AND 0.95  -- flaky range
  ORDER BY failure_rate DESC
```

**Value:** Developers can automatically quarantine flaky tests, reducing noise in CI signal.

---

### Improvement 2: Pipeline Visualization — Real-Time DAG View

**Current:** List of jobs with status.
**Improved:** Interactive DAG visualization showing job dependencies, critical path, and bottlenecks.

```
Visual DAG:
  [checkout] → [build] → [test-unit] → [deploy-staging] → [deploy-prod]
                       ↗ [test-integration] ↗
                       ↗ [test-e2e]        ↗

Critical path highlighted in red
Currently running jobs pulsing
Completed jobs with duration annotation
```

**Implementation:** D3.js or Mermaid.js DAG rendering. Job dependency graph fetched from API as adjacency list. Real-time status updates via WebSocket.

---

### Improvement 3: Ephemeral PR Review Environments

**Current:** CI runs tests, deploys to staging on merge.
**Improved:** Automatically deploy a preview environment per PR.

```yaml
on:
  pull_request:
    types: [opened, synchronize]

jobs:
  preview:
    steps:
    - run: helm install pr-${{ pr.number }} ./helm --set image.tag=${{ sha }}
    - uses: comment-pr@v1
      with:
        body: "Preview deployed at https://pr-${{ pr.number }}.preview.example.com"

  cleanup:
    on: pull_request.closed
    steps:
    - run: helm uninstall pr-${{ pr.number }}
```

**Platform support:** Named deployments with PR lifecycle hooks. DNS provisioning (`pr-123.preview.example.com`). Automatic cleanup on PR close.

---

### Improvement 4: Execution Engine Migration to WebAssembly Sandbox

**Current:** Container-based isolation (Docker-in-Docker or Kaniko). Security depends on container runtime.
**Improved:** WASM sandbox for individual steps.

**How:**
- Steps that don't require system-level access (linting, testing, analysis) run in WASM sandbox
- WASM: deterministic execution, capability-based I/O, ~100ms startup (vs 10s container)
- Steps explicitly requesting system access (docker build, network access) still run in container

**Benefits:**
- 100x faster step isolation startup
- Stronger security guarantees (capability model)
- Reproducible execution (WASM is deterministic)

**Tradeoff:** Not all build tools support WASM compilation. Requires WASM-compatible tool ecosystem (gradually improving). Pragmatic today for lint/test steps; not universal.

---

## What a FAANG Interviewer Would Challenge

| Challenge | Expected Response |
|---|---|
| "How do you handle a 10,000-job commit from a monorepo?" | Path filters + concurrency limits prevent queue flooding. Matrix builds distribute work. Fair-share scheduling ensures other orgs aren't starved. Pre-warmed runner pool absorbs burst. |
| "What is your security model for user-provided workflow YAML?" | Principle of least privilege for runners (no K8s API access, no cloud metadata). HMAC-validated secrets (never in YAML). Fork PR runs without secrets. Action version pinning enforced. No root in container (unless explicitly opted-in). |
| "How does the platform know if a deployment succeeded?" | Deployment job exit code (0 = success). Plus: health check step post-deploy (`curl https://app/health → 200`). Optional: deployment environment status API (GitHub Environments). Human confirmation for production gate. |
| "What happens when the git provider (GitHub) is down?" | Webhooks stop arriving. No new pipeline triggers. Running jobs complete. Queue processes existing jobs. When GitHub recovers: missed webhooks are NOT retried by GitHub (GitHub retries for ~72h if received non-2xx). Solution: polling fallback — periodically check new commits if webhook delivery confirms missed |
| "How do you handle secrets in matrix builds?" | Each matrix job gets same set of secrets (all within same org/repo). Secrets fetched per job. Matrix jobs are parallel — no shared state, no race on secrets. |

---

## Tech Debt Risks

| Risk | Trigger | Consequence |
|---|---|---|
| Monolithic YAML parser | Complex expression language grows (like GitHub Actions `${{ }}`) | Parser becomes security-critical, hard to sandbox |
| No runner-pool per AZ | Single AZ failure → all runners unavailable | Platform down during cloud AZ incident |
| Hardcoded concurrency limits | Customer upsells to higher tier | Code change required (should be DB config) |
| Log masking in runner only | Runner agent vulnerability | Secret exfiltration in logs |
| Shared artifact S3 bucket | Multi-tenant isolation failure | Org A can access Org B's artifacts via guessed key |

---

## Operational Burden Analysis

| Operation | Effort | Frequency | Automation Possible? |
|---|---|---|---|
| Runner image update | Medium (build + deploy new image) | Monthly | Yes (automated build + HPA drain) |
| KMS key rotation for secrets | High (re-encrypt all secrets) | Annually | Partial (AWS automatic key rotation) |
| PostgreSQL major version upgrade | High (pg_upgrade, downtime or replication upgrade) | Every 3 years | Partial (RDS managed upgrade) |
| Kafka partition scaling | Medium (topic recreation + migration) | Quarterly | Partial (tooling exists) |
| Orphaned artifact cleanup | Low (cron job) | Weekly | Yes |
| Certificate rotation (TLS) | Low (cert-manager auto-renew) | Every 90 days | Yes (cert-manager) |

---

## Interview Discussion Points

- **What is the fundamental tension in CI/CD platform design?** Security vs flexibility. Developers want to run arbitrary code with arbitrary tool access. Security requires isolation, least privilege, and secret protection. Every security control (network restrictions, no root, secret masking) limits what developers can do. The sweet spot: sensible defaults that handle 95% of use cases, with explicit escape hatches for the remaining 5% (self-hosted runners, privileged containers with approval)
- **What would you build differently at Google scale?** Borg/Kubernetes integration from day 1 (not added later). Remote build execution (Bazel-style) — build tool is the execution unit, not the container. Hermetic builds enforced by the platform (all dependencies declared, no `apt-get` without pre-approval). Full distributed tracing mandatory for all jobs
- **How do you keep the platform from becoming a dependency on itself?** Maintain a "bootstrap" path: human-runnable scripts that can deploy the platform from scratch without relying on the platform. Document it. Test it quarterly. The CI/CD platform is infrastructure — it must be deployable even when it's broken
- **What metrics indicate a healthy CI/CD culture (not just healthy CI/CD platform)?** Deployment frequency > 1/day per team. Mean time to detect (build failure alert) < 10 min. Mean time to recover from failed deploy < 30 min. These are DORA metrics — they measure the engineering org's productivity, not just platform uptime
