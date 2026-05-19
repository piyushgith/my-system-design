# 12 — Observability: CI/CD Platform

---

## Objective

Define logging, metrics, tracing, alerting, and SLI/SLO strategy for a CI/CD platform. Unlike infrastructure observability, CI/CD observability has a unique challenge: the platform's own health directly determines developer productivity across the entire organization.

---

## Observability Stack

| Layer | Tool | Purpose |
|---|---|---|
| Metrics | Prometheus + Grafana | Platform health, throughput, runner utilization |
| Distributed Tracing | OpenTelemetry + Jaeger | End-to-end pipeline trigger → job completion |
| Logging | ELK / OpenSearch | Structured service logs, audit trail |
| Real-time Events | Kafka metrics + kminion | Queue depth, consumer lag |
| Uptime Monitoring | Blackbox exporter / Pingdom | External availability from user perspective |
| Pipeline Analytics | ClickHouse | Job duration trends, flaky test detection |

---

## Key Metrics

### Control Plane Metrics

**Scheduler:**

| Metric | Label | Alert |
|---|---|---|
| `cicd_trigger_processing_latency_seconds` | p50/p99 | p99 > 5s |
| `cicd_job_dispatch_rate` | per_second | Sudden drop (Scheduler stalled) |
| `cicd_active_runs` | by_status | PENDING > 10K for > 10min |
| `cicd_job_dispatch_lag_seconds` | (trigger → dispatch) | > 60s |
| `cicd_orphaned_jobs_recovered` | counter | Any spike |
| `cicd_cron_triggers_fired` | counter | Drop to 0 (cron scheduler dead) |

**Runner Pool:**

| Metric | Label | Alert |
|---|---|---|
| `cicd_runner_utilization_ratio` | by_label | > 0.95 for > 5min (queue building) |
| `cicd_runner_count` | by_status (IDLE/BUSY/OFFLINE) | OFFLINE > 5% of pool |
| `cicd_job_start_latency_seconds` | p50/p99 | p99 > 120s (runner cold start) |
| `cicd_job_queue_depth` | by_label | Sustained growth (runners can't keep up) |
| `cicd_job_success_rate` | by_org, by_repo | < 50% for sustained period (something wrong) |
| `cicd_runner_oom_kills` | counter | Any OOM kill |

**Log Service:**

| Metric | Label | Alert |
|---|---|---|
| `cicd_log_streaming_connections` | current | > 8K per pod (approaching limit) |
| `cicd_log_redis_buffer_size_bytes` | total | > 80% Redis capacity |
| `cicd_log_s3_flush_lag_seconds` | p99 | > 60s (data not persisted) |

---

### Business Metrics (Developer Experience)

These are the metrics that matter to developers, not just ops:

| Metric | SLI | Target SLO |
|---|---|---|
| `cicd_e2e_latency_seconds` | Push → job running | p99 < 90s |
| `cicd_job_failure_rate_infra` | Jobs failed due to infra (not code) | < 1% |
| `cicd_log_delivery_latency` | Log line → browser | p99 < 5s |
| `cicd_run_queue_time_seconds` | Job queued → running | p99 < 60s |
| `cicd_artifact_upload_rate_mbps` | Artifact upload throughput | p99 > 50 MB/s |

---

## Distributed Tracing

### Trace Propagation

Every pipeline trigger carries a trace context from webhook receipt through job completion:

```
Span: "webhook.receive" (Webhook Service)
  → Span: "trigger.parse" (Webhook Service)
  → Span: "trigger.publish" (Kafka publish)
    → Span: "scheduler.consume_trigger" (Scheduler)
      → Span: "workflow.parse" (Pipeline Config Service)
      → Span: "run.create" (DB write)
      → Span: "job.dispatch" (Kafka publish)
        → Span: "runner.consume_job" (Runner)
          → Span: "runner.secret_fetch" (Secret Service gRPC)
          → Span: "runner.checkout" (git clone)
          → Span: "step.execute[0]" (shell command)
          → Span: "step.execute[1]"
          → Span: "artifact.upload"
          → Span: "runner.status_report" (Kafka publish)
            → Span: "scheduler.process_completion"
              → Span: "notification.send"
```

**Trace context carried via:**
- HTTP/gRPC headers: `traceparent` (W3C format)
- Kafka message headers: `traceparent` header
- Artifact/log S3 object metadata: `x-trace-id`

**Example Jaeger query:**
```
Service: webhook-service
Operation: webhook.receive
Filter: http.url = "/webhooks/github" AND status_code != 200
→ Find all failed webhook ingestions with full trace
```

---

## Structured Logging

### Log Format (JSON, ELK-indexed)

```json
{
  "timestamp": "2024-01-15T10:30:01.234Z",
  "level": "INFO",
  "service": "scheduler",
  "traceId": "abc123",
  "spanId": "def456",
  "orgId": "uuid",
  "runId": "uuid",
  "jobId": "uuid",
  "message": "Job dispatched to runner pool",
  "fields": {
    "runnerLabel": "ubuntu-22.04",
    "retryCount": 0,
    "queueLatencyMs": 245
  }
}
```

**Correlation IDs:**
- `traceId`: end-to-end trace across services
- `runId`: all logs for a pipeline run
- `jobId`: all logs for a specific job
- `orgId`: all logs for an organization

ELK index: `cicd-service-logs-{YYYY.MM.DD}` — enables fast time-range filtering.

### Log Levels Policy

| Level | When | Example |
|---|---|---|
| ERROR | Operation failed; human intervention likely needed | DB write failed, secret decryption error |
| WARN | Operation degraded but continuing | Retry attempt 2/3, runner heartbeat delayed |
| INFO | Normal operational events | Job dispatched, run created, webhook received |
| DEBUG | Detailed flow (disabled in production) | SQL query text, Kafka message content |

**Never log:** Secret values, access tokens, webhook secrets, user passwords.

---

## Dashboards

### Dashboard 1: Platform Health (Ops SLA)

```
Row 1: RED metrics (Rate, Errors, Duration)
  - Webhook ingestion rate + error rate
  - Job dispatch rate + queue depth
  - Runner pool utilization (busy/idle/offline ratio)

Row 2: Latency percentiles
  - Job start latency p50/p95/p99 (last 1h)
  - E2E pipeline latency p50/p99

Row 3: Queue health
  - job-queue consumer lag by label
  - pipeline-triggers lag
  - DLQ depth
```

### Dashboard 2: Runner Fleet Health

```
- Runner pool size over time (scaling events visible)
- Active jobs per runner node
- OOM kills per hour
- Job retry rate (infra failures)
- Job duration distribution (histogram)
- Runner utilization by org (top 10 consumers)
```

### Dashboard 3: Developer Experience (DORA Metrics)

```
- Deployment frequency (runs to production per day)
- Lead time for changes (commit → deploy duration)
- Change failure rate (% of deploys that fail)
- Mean time to recovery (failed deploy → successful redeploy)
- Build success rate trend (last 30 days by repo)
- Flaky test detection (jobs that succeed on retry > X% of the time)
```

---

## SLI / SLO / SLA

### SLIs

| SLI | Measurement |
|---|---|
| Trigger availability | % of webhooks processed successfully (not 5xx) over 5-min window |
| Job start latency | p99 of (job_queued → job_running) over 1-hour window |
| E2E latency | p99 of (webhook_received → first_job_running) |
| Log streaming availability | % of log streaming connections that successfully receive first log line |
| Platform availability | Synthetic check: trigger test run every 5 min, measure success + latency |

### SLOs

| SLO | Target | Window |
|---|---|---|
| Trigger processing availability | ≥ 99.9% | 30-day rolling |
| Job start latency p99 | < 60 seconds | 1-hour window |
| E2E trigger → running | < 90 seconds | 1-hour window |
| Log streaming availability | ≥ 99.5% | 30-day rolling |
| Infra job failure rate | < 0.5% of all jobs | 24-hour window |

### SLO Burn Rate Alerting

```yaml
# Trigger availability fast burn (5% budget in 1 hour)
- alert: TriggerAvailabilityFastBurn
  expr: |
    (1 - rate(cicd_webhooks_processed_success[5m]) / rate(cicd_webhooks_total[5m]))
    > (1 - 0.999) * 14.4
  for: 2m
  severity: critical
  annotations:
    message: "Webhook availability burning error budget at 14.4x rate"

# Job start latency degradation
- alert: JobStartLatencyHigh
  expr: histogram_quantile(0.99, rate(cicd_job_start_latency_seconds_bucket[10m])) > 60
  for: 5m
  severity: warning
```

---

## Alerting Runbooks

### Alert: High Job Queue Depth

**Trigger:** `cicd_job_queue_depth > 1000` for > 5 minutes

**Runbook:**
1. Check runner pool utilization: `kubectl get pods -n cicd-runners | grep Running | wc -l`
2. Check HPA status: `kubectl describe hpa runner-hpa -n cicd-runners`
3. If HPA not scaling: check node capacity `kubectl describe nodes | grep -A5 "Allocated resources"`
4. If node capacity exhausted: trigger cluster autoscaler or add node group
5. Check for per-org throttling: which org has most queued jobs?
6. Temporary: increase `maxReplicas` in HPA if cluster capacity allows

### Alert: Scheduler Not Dispatching

**Trigger:** `cicd_job_dispatch_rate = 0` for > 2 minutes

**Runbook:**
1. Check Scheduler pods: `kubectl get pods -n cicd-control | grep scheduler`
2. Check leader lock: `SELECT pg_advisory_lock_status()`
3. Check Kafka connectivity: `kafka-consumer-groups.sh --describe --group scheduler-trigger-consumer`
4. Check Scheduler logs for error: `kubectl logs -n cicd-control deployment/scheduler | grep ERROR`

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| ClickHouse for pipeline analytics | Columnar aggregation 100x faster than PG for job duration trends | Additional data store; ETL pipeline needed |
| Synthetic probe every 5 min | Measure user-visible E2E latency proactively | Consumes runner slots; adds ~2% to runner utilization |
| DORA metrics dashboard | Align platform observability with business outcome (deployments) | Requires integration with deployment events (not just CI runs) |
| Trace context in Kafka headers | Full distributed trace; no "black holes" in async hops | ~100 bytes overhead per Kafka message |

---

## Interview Discussion Points

- **What is the most important SLO for a CI/CD platform?** Job start latency. If developers push and wait 5 minutes for CI to even start, they context-switch. < 30s start time keeps them in flow. This is the metric most directly tied to developer productivity
- **How do you detect infrastructure failures vs user code failures?** Track job failure reason: `RUNNER_FAILURE` (infra) vs `EXIT_CODE_1` (user code). Alert only on `RUNNER_FAILURE` rate. User code failures are expected and not platform incidents
- **What metrics help you identify flaky tests?** Track per-test pass/fail over rolling 30 days. A test that fails 20% of runs on retry is flaky. Requires structured test result parsing (JUnit XML format). Store in ClickHouse for trend analysis
- **How do you correlate a developer's "my build is slow" complaint?** RunId → trace_id → Jaeger trace → shows each span. Identify which span is longest: git clone slow? Secret fetch slow? npm ci slow (cache miss)? This is the power of distributed tracing in CI/CD
- **How do you monitor the monitoring?** Meta-monitoring: run a synthetic "canary" pipeline every 5 minutes. Alert if canary fails or exceeds latency threshold. This tests the entire CI/CD stack end-to-end from outside
