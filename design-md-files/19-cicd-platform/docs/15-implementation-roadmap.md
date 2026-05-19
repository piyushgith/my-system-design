# 15 — Implementation Roadmap: CI/CD Platform

---

## Objective

Define a phased implementation plan from minimal pipeline executor through a production-grade, multi-tenant, auto-scaling CI/CD platform with advanced security and observability.

---

## Phase 0: MVP — Trigger-to-Execution Core (Weeks 1–8)

### Goals
Build the smallest system that can accept a Git push, parse a YAML pipeline, and execute a job in a container. No multi-tenancy, no secrets, no artifacts.

### Features
- Single Git provider integration: GitHub webhook ingestion (HMAC validation)
- YAML pipeline parsing: `jobs` with sequential `steps` (run: commands only)
- Job execution: Docker container on local machine (no Kubernetes yet)
- Real-time log output: console + file (no streaming to browser)
- PostgreSQL schema: organizations, repositories, pipeline_runs, jobs, steps
- REST API: trigger run, get run status, get job status
- Simple web UI: list runs for a repo, view job status (no real-time)
- No authentication (single-tenant, internal use)

### Architecture
- Spring Boot monolith (all contexts in one service)
- PostgreSQL + Flyway migrations
- Docker SDK (Java) for container management
- No Kafka (direct in-process dispatch)

### Done When
- Push to GitHub → webhook fires → job runs in Docker container → logs to file → status visible in UI

### Risks
- YAML parsing edge cases (anchors, complex expressions, nested structures)
- Docker lifecycle management (container cleanup on failure)

---

## Phase 1: Job Queue, Log Streaming, Kubernetes (Weeks 9–18)

### Goals
Move from local Docker execution to Kubernetes-based runner pool. Add real-time log streaming. Extract Scheduler from monolith.

### Features
- Kafka for job dispatch (decouple trigger ingestion from execution)
- Runner pool as Kubernetes Deployment (dynamic pod count)
- PostgreSQL advisory lock for Scheduler leader election
- Real-time log streaming: Redis buffer + SSE endpoint
- Runner heartbeat + orphaned job detection/recovery
- Horizontal Pod Autoscaler for runner pool (by Kafka consumer lag)
- Job timeout enforcement
- Retry logic (up to 3 retries on RUNNER_FAILURE)
- Basic web UI: real-time log streaming to browser

### Architecture
- Extract Scheduler as separate service (first microservice extraction)
- Runner agent: Java/Go process that polls Kafka, manages Docker-in-Docker
- Log Service: Spring Boot + Redis + SSE streaming
- Deploy to Kubernetes (Helm chart)

### Done When
- Push → Kafka → Runner picks up → K8s pod → logs stream to browser
- Kill a runner pod → job re-queues → another runner picks it up
- HPA scales runner pool from 2 to 10 pods based on queue depth

---

## Phase 2: Security — Secrets, Auth, Isolation (Weeks 19–28)

### Goals
Multi-tenant with proper isolation. Add secrets, authentication, and container network isolation.

### Features
- JWT authentication (OAuth2 with GitHub as IdP)
- RBAC: org member, repo admin, org admin roles
- Secret Service: KMS-encrypted secrets, job-scoped tokens, gRPC
- Runner-side secret masking in log stream
- Container network isolation (Kubernetes NetworkPolicy)
- Artifact upload/download: presigned S3 URLs
- Multi-organization support: all data scoped to orgId
- Per-org concurrency limits (Redis atomic counter)
- HMAC webhook signature validation (was mocked in Phase 0)
- Audit log (secret access, run triggers, admin actions)

### Architecture
- Secret Service as dedicated microservice (separate namespace + NetworkPolicy)
- Runner agent: fetches job-scoped token from Scheduler, uses for secret fetch
- All services: JWT validation at API Gateway (Kong/Nginx)
- S3 integration (MinIO locally, AWS S3 in staging/production)

### Done When
- Org A's secrets cannot be read by Org B's jobs (verify via penetration test)
- Secret values never appear in stored log output
- Artifact from Job A downloadable by Job B in same run (cross-job artifact sharing)

---

## Phase 3: Pipeline Features (Weeks 29–38)

### Goals
Full pipeline feature set: job dependencies, matrix builds, environment promotion, caching.

### Features
- Job dependencies (`needs:` keyword) with DAG resolution
- Matrix builds (one workflow job → N parallel jobs)
- `if:` conditional steps and jobs
- `continue-on-error:` support
- Build dependency caching: cache action (S3-backed per project)
- Docker layer caching (BuildKit remote cache)
- Multiple trigger types: push, pull_request, schedule (cron), workflow_dispatch (manual)
- Environment promotion: deploy to staging → manual gate → deploy to production
- Concurrency groups: cancel in-progress runs when new push arrives on same branch
- Reusable workflows (reference another repo's workflow)

### Architecture
- Pipeline Config Service: standalone service for YAML parsing, action resolution
- Cron scheduler: dedicated component in Scheduler Service
- Matrix expansion at run creation time (N jobs created for N matrix combinations)
- DAG evaluation: topological sort, dependency tracking in Scheduler

### Done When
- 3-job pipeline (build → test → deploy) executes in correct order
- Matrix build: `os: [ubuntu, windows]` creates 2 parallel jobs
- Manual gate: deploy-production job waits for human approval
- Cron trigger fires on schedule ±1 minute accuracy

---

## Phase 4: Self-Hosted Runners and OIDC (Weeks 39–46)

### Goals
Allow customers to bring their own runners. Eliminate static cloud credentials via OIDC.

### Features
- Self-hosted runner registration and management
- Runner agent binary downloadable (Linux/macOS/Windows)
- Runner groups: assign self-hosted runners to specific orgs/repos
- OIDC token issuance: platform as OIDC provider
- AWS STS OIDC integration: short-lived AWS credentials per job
- GCP Workload Identity integration
- Sandboxed container execution: rootless Docker + gVisor option

### Architecture
- Runner registration API: one-time token exchange for long-lived runner credentials
- OIDC provider service: JWK endpoint, token issuance with job context claims
- Self-hosted runner label routing: dedicated `job-queue-self-hosted-{groupId}` topics

### Done When
- Customer registers a runner from their private network
- Jobs run on self-hosted runner, accessing internal services
- AWS deployment job uses OIDC — no static `AWS_SECRET_KEY` needed
- GCP deployment job uses Workload Identity — no service account JSON key

---

## Phase 5: Enterprise Scale and Observability (Weeks 47–60)

### Goals
Full observability, distributed tracing, pipeline analytics, flaky test detection, and multi-region.

### Features
- Distributed tracing: OpenTelemetry across all services → Jaeger
- Structured log aggregation: ELK stack
- DORA metrics dashboard (Grafana)
- Flaky test detection: JUnit XML result parsing → ClickHouse → trend analysis
- Pipeline analytics: p50/p99 job duration by repo, week-over-week trends
- Multi-region support: webhook routing + runner pools in EU/APAC
- Tiered log storage: Redis (live) → S3 Standard (7 days) → S3 IA (90 days)
- Artifact retention policies: configurable per project
- Enterprise SSO: SAML 2.0 integration
- Advanced RBAC: custom roles with permission sets
- Cost attribution: runner-minutes per team/project (billing integration)

### Done When
- Full trace visible in Jaeger: GitHub push → runner execution (end-to-end)
- Flaky test report: "test_user_login flakes 23% of runs in the last 30 days"
- Multi-region: EU customers' logs stay in EU S3 buckets (data residency)

---

## Phase Summary

| Phase | Duration | Key Milestone |
|---|---|---|
| 0: MVP | Weeks 1–8 | Push → container execution |
| 1: Queue + Streaming | Weeks 9–18 | K8s runners, live log streaming |
| 2: Security | Weeks 19–28 | Multi-tenant, secrets, isolation |
| 3: Pipeline Features | Weeks 29–38 | Dependencies, matrix, caching |
| 4: Self-Hosted + OIDC | Weeks 39–46 | BYO runners, no static secrets |
| 5: Enterprise | Weeks 47–60 | Observability, analytics, multi-region |

---

## Team Scaling

| Phase | Team Size | Roles |
|---|---|---|
| 0–1 | 3 engineers | 2 backend + 1 frontend |
| 2 | 5 engineers | Add security engineer + SRE |
| 3 | 7 engineers | Add 2 for pipeline DSL + testing |
| 4 | 9 engineers | Add runner platform engineer + security |
| 5 | 12 engineers | Add data engineer (analytics) + 2 SRE |

---

## Prioritization for Interview Context

**If asked "what would you build for a tech interview demo?"**

Phase 0 MVP in 48 hours:
1. Single endpoint: `POST /trigger {repo, branch, yaml}` → runs YAML in Docker
2. `GET /runs/{id}` → JSON status with step results
3. PostgreSQL for persistence (Flyway migrations)
4. Docker SDK for container management
5. Simple React UI showing job status tree

This demonstrates: API design, DB schema, Docker integration, async job execution — all core concepts without K8s/Kafka complexity.

---

## Interview Discussion Points

- **What would you defer to Phase 2+ if building an MVP?** Authentication, multi-tenancy, secrets, artifact storage, auto-scaling. MVP proves the core pipeline execution model works. Security and scale come after the model is validated
- **What is the riskiest technical decision in Phase 1?** Runner isolation: Docker-in-Docker security model. Allowing jobs to build Docker images requires Docker access — but that access can be used to break container isolation. Evaluating rootless Docker vs Kaniko is the right Phase 1 decision to make
- **How does database schema evolve across phases?** Flyway migrations with backward-compatible changes. New columns nullable (existing rows get NULL). New tables. Never remove/rename columns without migration period. Phase 0 schema is minimal — adds columns as features require. No breaking schema changes after Phase 2 (when external consumers exist)
