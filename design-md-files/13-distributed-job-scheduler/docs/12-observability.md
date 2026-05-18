# 12 — Observability: Distributed Job Scheduler

## Objective
Define the complete observability strategy — metrics, distributed tracing, structured logging, alerting, SLIs/SLOs, and dashboards — that enables engineers to understand system health, diagnose failures, and maintain reliability in production.

---

## 1. Observability Pillars

| Pillar | Tool | Purpose |
|---|---|---|
| Metrics | Prometheus + Grafana | Quantitative system health, alerting |
| Distributed Tracing | OpenTelemetry + Jaeger | Trace job execution flow across components |
| Structured Logging | Logback (JSON) + ELK | Searchable, contextual logs |
| Alerting | Prometheus Alertmanager + PagerDuty | Proactive incident notification |
| Dashboards | Grafana | Operational visibility |
| Synthetic Monitoring | Custom probe jobs | End-to-end health verification |

---

## 2. Key Metrics

### 2.1 Scheduling Metrics

| Metric | Type | Description | Labels |
|---|---|---|---|
| `scheduler_jobs_polled_total` | Counter | Jobs retrieved in each polling cycle | namespace, partition |
| `scheduler_dispatch_attempts_total` | Counter | Lock acquisition attempts | namespace |
| `scheduler_dispatch_success_total` | Counter | Successful dispatches (lock acquired) | namespace, priority |
| `scheduler_dispatch_skipped_total` | Counter | Skipped (lock already held) | namespace |
| `scheduler_poll_duration_seconds` | Histogram | Time to complete one poll cycle | partition |
| `scheduler_dispatch_latency_seconds` | Histogram | Time from trigger time to Kafka publish | namespace, priority |
| `scheduler_next_execution_compute_seconds` | Histogram | Time to compute next execution time | schedule_type |
| `scheduler_is_leader` | Gauge | 1 if this node is the leader, 0 otherwise | node_id |
| `scheduler_outbox_pending_count` | Gauge | Current size of outbox_events with PENDING status | — |

### 2.2 Execution Metrics

| Metric | Type | Description | Labels |
|---|---|---|---|
| `execution_queued_total` | Counter | Total executions queued | namespace, job_type, priority |
| `execution_started_total` | Counter | Total executions started | namespace, job_type |
| `execution_completed_total` | Counter | Successful completions | namespace, job_type |
| `execution_failed_total` | Counter | Failed executions | namespace, job_type, failure_reason |
| `execution_timed_out_total` | Counter | Timed-out executions | namespace, job_type |
| `execution_dlq_total` | Counter | Executions sent to DLQ | namespace |
| `execution_duration_seconds` | Histogram | Job execution wall-clock time | namespace, job_type |
| `execution_queue_latency_seconds` | Histogram | Time from QUEUED to EXECUTING | namespace, priority |
| `execution_concurrent_count` | Gauge | Current in-flight executions | namespace, worker_pool |
| `execution_retry_attempts_total` | Counter | Retry attempts | namespace, attempt_number |

### 2.3 Worker Metrics

| Metric | Type | Description | Labels |
|---|---|---|---|
| `worker_active_count` | Gauge | Number of active workers | type, zone |
| `worker_load_ratio` | Gauge | current_load / max_concurrency | worker_id, type |
| `worker_jobs_processed_total` | Counter | Jobs processed per worker | worker_id, type, status |
| `worker_heartbeat_age_seconds` | Gauge | Time since last heartbeat | worker_id |
| `worker_draining_count` | Gauge | Workers in draining state | type |

### 2.4 Kafka Metrics

| Metric | Type | Description | Labels |
|---|---|---|---|
| `kafka_consumer_lag` | Gauge | Messages behind latest offset | consumer_group, topic, partition |
| `kafka_producer_errors_total` | Counter | Producer send failures | topic |
| `kafka_consumer_rebalances_total` | Counter | Consumer group rebalances | consumer_group |
| `kafka_message_age_seconds` | Histogram | Age of messages when consumed | topic |

### 2.5 Cache Metrics (Redis)

| Metric | Type | Description | Labels |
|---|---|---|---|
| `redis_cache_hit_ratio` | Gauge | Cache hit rate | cache_name |
| `redis_lock_acquisition_duration_seconds` | Histogram | Time to acquire distributed lock | — |
| `redis_lock_acquisition_failures_total` | Counter | Failed lock acquisitions | reason |
| `redis_connection_errors_total` | Counter | Redis connection failures | — |

---

## 3. SLIs and SLOs

### Scheduling SLI/SLO

**SLI:** Percentage of jobs triggered within 5 seconds of their scheduled time.

```
SLI = (jobs with |actual_trigger_time - scheduled_for| <= 5s) / total_jobs_triggered
Target SLO: 99.9% of jobs within 5 seconds
```

Measurement: `scheduler_dispatch_latency_seconds` — alert when p99 > 5s.

### Execution Start SLI/SLO

**SLI:** Percentage of queued jobs that begin execution within 10 seconds.

```
SLI = (executions with start_latency <= 10s) / total_executions
Target SLO: 99.5% within 10 seconds
```

Measurement: `execution_queue_latency_seconds` — alert when p99 > 10s.

### API Availability SLI/SLO

**SLI:** Percentage of API requests returning non-5xx response within 500ms.

```
SLI = (requests with response_code < 500 AND response_time < 500ms) / total_requests
Target SLO: 99.99% for GET endpoints, 99.9% for mutation endpoints
```

### Job Failure Rate SLI/SLO

**SLI:** Percentage of job executions that complete successfully on first attempt.

```
SLI = successful_completions_attempt_1 / total_executions_attempt_1
Target SLO: 95% success rate on first attempt
Note: This SLO depends on the job implementations, not just the scheduler
```

---

## 4. Distributed Tracing

### Trace Coverage

Every job execution is a complete trace spanning multiple services:

```
Trace: job-execution
├── Span: scheduler.poll_cycle (Scheduler Engine)
│   └── Span: scheduler.dispatch (includes lock acquisition)
│       └── Span: outbox.write (DB write)
├── Span: outbox.relay.publish (Kafka produce)
├── Span: worker.consume (Worker picks up Kafka message)
│   ├── Span: worker.fetch_job_definition (Redis/DB read)
│   └── Span: worker.execute (actual job logic)
│       └── Span: worker.http_call (for HTTP worker type)
└── Span: result_processor.process (Result Processor)
    ├── Span: db.update_execution (PostgreSQL write)
    └── Span: kafka.publish_event (job-events topic)
```

### Trace Context Propagation

The `correlationId` (trace ID) is set when the `JobTriggered` event is created and flows through:
- Kafka message headers (`correlation-id`)
- HTTP headers between services (`X-Trace-Id`)
- Log statements (`traceId` field)
- Database writes (`correlation_id` column in `job_executions`)

```java
// OpenTelemetry instrumentation
@Observed(name = "scheduler.dispatch")
public void dispatchJob(Job job) {
    Span span = tracer.spanBuilder("scheduler.dispatch")
        .setAttribute("job.id", job.getJobId().toString())
        .setAttribute("job.namespace", job.getNamespace().getValue())
        .setAttribute("job.priority", job.getPriority().getValue())
        .startSpan();
    // ...
}
```

### Trace Sampling Strategy

| Environment | Sampling Rate | Reason |
|---|---|---|
| Production | 1% for P2-P4; 10% for P0-P1; 100% for errors | Cost vs. visibility tradeoff |
| Staging | 100% | Full visibility for testing |
| Performance testing | 10% | Representative sample at load |

**Tail-based sampling (Phase 2):** Jaeger's tail-based sampler captures 100% of traces containing errors or high latency, regardless of head sampling rate. This ensures all anomalous traces are captured.

---

## 5. Structured Logging

### Log Format (JSON)

All application logs are emitted as structured JSON:

```json
{
  "timestamp": "2024-01-16T10:00:05.123Z",
  "level": "INFO",
  "logger": "SchedulerEngine",
  "message": "Job dispatched successfully",
  "traceId": "7e3d2a1b0f9c4e5d",
  "spanId": "f1a2b3c4d5e6",
  "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "executionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "namespace": "production",
  "fencingToken": 42,
  "scheduledFor": "2024-01-16T10:00:00Z",
  "dispatchLatencyMs": 120,
  "nodeId": "scheduler-0"
}
```

### Log Levels and Their Meaning

| Level | Use Case |
|---|---|
| ERROR | Unexpected failure requiring attention (failed outbox relay, Redis unavailable) |
| WARN | Expected but notable condition (lock not acquired, retry attempted) |
| INFO | Normal operation milestones (job dispatched, execution completed) |
| DEBUG | Per-operation detail (not enabled in prod by default) |

### Log Retention

| Log Type | Hot Retention | Archive |
|---|---|---|
| Application logs | 7 days (Elasticsearch) | 90 days (S3, Glacier after 30d) |
| Job execution logs | 30 days (S3 hot tier) | 2 years (S3 Glacier) |
| Audit logs | 1 year (Elasticsearch) | 7 years (S3 + Object Lock) |

---

## 6. Alerting

### Critical Alerts (PagerDuty, immediate)

| Alert | Condition | Runbook |
|---|---|---|
| `SchedulerLeaderLost` | No node has `scheduler_is_leader=1` for >60s | Check Redis, check scheduler pods |
| `DatabasePrimaryDown` | Patroni health check fails | Verify failover, check connection pool |
| `KafkaUnderReplicated` | `kafka_under_replicated_partitions > 0` for >5min | Check broker health |
| `SplitBrain` | Two nodes report `scheduler_is_leader=1` | Manual stepdown immediately |
| `ExecutionQLagCritical` | `kafka_consumer_lag{group="workers-http"} > 100000` | Scale workers, check K8s HPA |

### High Alerts (PagerDuty, 15-minute acknowledgment)

| Alert | Condition |
|---|---|
| `DLQGrowthHigh` | `execution_dlq_total` rate > 100/min |
| `WorkerUtilizationHigh` | Average `worker_load_ratio > 0.9` for >5min |
| `OutboxPendingHigh` | `scheduler_outbox_pending_count > 10000` |
| `ExecutionStartLatencyHigh` | `execution_queue_latency_seconds{quantile="0.99"} > 30s` |
| `SchedulerDispatchLatencyHigh` | `scheduler_dispatch_latency_seconds{quantile="0.99"} > 10s` |

### Medium Alerts (Slack notification, next business day)

| Alert | Condition |
|---|---|
| `RedisHitRatelow` | Cache hit rate < 80% |
| `HighRetryRate` | `execution_retry_attempts_total` rate > 10% of `execution_started_total` |
| `StuckExecution` | Any execution in EXECUTING for > job timeout + 5min |
| `DLQEntryOld` | Any DLQ entry unresolved for > 24h |

---

## 7. Grafana Dashboards

### Dashboard 1: Scheduler Health (NOC view)

```
Row 1: System Status
  - Scheduler leader status (green/red)
  - Total active jobs
  - Jobs due in next 5 minutes
  - Scheduler poll duration (p99)

Row 2: Dispatch Performance
  - Dispatches/second (last 1h)
  - Dispatch latency (p50, p95, p99)
  - Lock contention rate (skipped / total)
  - Outbox pending count

Row 3: Execution Throughput
  - Executions/second
  - Concurrent executions (vs. capacity)
  - Success rate (24h rolling)
  - Failure rate by reason (stacked bar)
```

### Dashboard 2: Worker Pool Health

```
Row 1: Capacity
  - Total workers active (by type)
  - Average utilization rate
  - Workers draining / offline

Row 2: Queue Health
  - Kafka consumer lag (by priority tier)
  - Queue latency (time QUEUED → EXECUTING)
  - Worker scale events (HPA actions)

Row 3: Per-Worker Detail
  - Load ratio heatmap (worker_id × time)
  - Executions/worker/hour
```

### Dashboard 3: Job Reliability

```
Row 1: SLO Burn Rate
  - Scheduling SLO (% within 5s target)
  - Execution start SLO (% within 10s target)
  - First-attempt success rate

Row 2: Retry and DLQ
  - DLQ entries (unresolved, resolved today)
  - Retry distribution (attempt 1, 2, 3, DLQ)
  - DLQ growth rate

Row 3: Per-Namespace Breakdown
  - Jobs by namespace (table)
  - Failure rate by namespace
  - DLQ entries by namespace
```

---

## 8. Synthetic Monitoring

A dedicated "canary job" runs every 5 minutes in each environment:
1. Registers a test job via API
2. Triggers it manually
3. Waits for completion (timeout: 60s)
4. Validates: execution completed successfully within SLO
5. Cleans up test job

**Metrics emitted:**
- `synthetic_job_e2e_latency_seconds` — full cycle time
- `synthetic_job_success` — 0 or 1

**Alert:** If `synthetic_job_success = 0` for 2 consecutive runs → CRITICAL alert (system is not end-to-end functional).

---

## 9. Correlation ID Strategy

Every operation has a correlation ID that flows from API request to job execution:

```
Request ID (from API Gateway) → included in JobTriggered Kafka message
  → execution.job_executions.correlation_id
  → Worker logs (X-Trace-Id)
  → Execution result (job-results Kafka message)
  → ELK log field (traceId)
  → Grafana Explore trace link
```

Search for a specific execution end-to-end:
```
ELK query: traceId:"7e3d2a1b0f9c4e5d"
→ Returns: API request, scheduler dispatch, worker execution, result processing — all in one timeline
```

---

## Interview Discussion Points

**Q: How do you detect a job that's "stuck" — not failed but not making progress?**
A: A stuck execution has `status=EXECUTING` and `updated_at` is not changing. The `TimeoutMonitor` detects this when `started_at + timeout_seconds < now()`. For jobs without a hard timeout, a separate "heartbeat from worker" mechanism is used: long-running workers emit a heartbeat every 30 seconds (`POST /executions/{id}/heartbeat`). If no heartbeat in 90 seconds, the job is marked as potentially stuck and an alert fires.

**Q: How do you distinguish between "the scheduler is fine but all jobs are failing" and "the scheduler itself is broken"?**
A: Separate metrics: `scheduler_dispatch_success_total` and `scheduler_is_leader=1` confirm the scheduler is dispatching. If dispatches are happening but `execution_failed_total` is high, the issue is in the jobs or workers — not the scheduler. The synthetic canary job confirms end-to-end functionality. Separate dashboards for scheduler health vs. execution health make this distinction immediately visible.

**Q: What's your approach to log sampling at high throughput (3,000 executions/sec)?**
A: Structured logging with sampling: DEBUG and INFO logs for routine operations (job dispatched, execution started) are sampled at 10% in production. ERROR and WARN are always logged (100%). All logs for failed executions are included without sampling. Log shipping via Filebeat uses async buffering to prevent backpressure on the application. This keeps log volume manageable while preserving full observability for failures.
