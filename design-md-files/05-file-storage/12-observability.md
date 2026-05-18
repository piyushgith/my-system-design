# 12 — Observability: File Storage System

## Objective
Define the complete observability strategy — metrics, logging, distributed tracing, alerting, SLIs/SLOs, and dashboards — for a production file storage platform. Storage systems have unique observability challenges: upload pipelines are async multi-step, download errors may only surface at the CDN edge, and storage quota drift is a silent business problem.

---

## Observability Pillars

| Pillar | Tool | Purpose |
|--------|------|---------|
| **Metrics** | Prometheus + Grafana | Service health, business KPIs, infrastructure |
| **Logs** | ELK Stack (Elasticsearch + Logstash + Kibana) | Structured, searchable log aggregation |
| **Distributed Tracing** | Jaeger (OpenTelemetry SDK) | Multi-service request tracing, latency attribution |
| **Alerting** | Alertmanager + PagerDuty | SLO breach, error spikes, infrastructure alerts |
| **Business Analytics** | ClickHouse → Grafana | Storage usage trends, upload success rates, feature adoption |
| **Uptime Monitoring** | Pingdom / Blackbox Exporter | External availability checks |

---

## SLI / SLO / SLA Definitions

### Upload Service
| SLI | SLO | SLA |
|-----|-----|-----|
| Upload init API P99 latency < 500ms | 99.5% of requests | 99% monthly |
| Upload session success rate (complete without error) | > 98% | > 97% monthly |
| Quota enforcement accuracy (no over-quota) | 100% | 100% |

### Download / Delivery
| SLI | SLO | SLA |
|-----|-----|-----|
| Download redirect P99 < 200ms | 99.9% of requests | 99.5% monthly |
| CDN cache hit ratio | > 85% | > 80% monthly |
| File bytes served without corruption | 100% (integrity check via hash) | 100% |

### Metadata Service
| SLI | SLO | SLA |
|-----|-----|-----|
| File list API P99 < 200ms | 99.5% of requests | 99% monthly |
| File metadata read P99 < 100ms | 99.5% of requests | 99% monthly |
| Write API P99 < 500ms | 99% of requests | 99% monthly |

### Search Service
| SLI | SLO | SLA |
|-----|-----|-----|
| Search result P99 < 500ms | 99.5% of requests | 99% monthly |
| Search index freshness (time from file create to searchable) | < 30s for 99% | < 60s monthly |

### Sync Service
| SLI | SLO | SLA |
|-----|-----|-----|
| Sync poll P99 < 200ms | 99.5% of requests | 99% monthly |
| Change propagation latency (file change → sync client sees it) | < 10s for 99% | < 30s monthly |

---

## Key Prometheus Metrics

### Upload Service Metrics
```
# Upload session initiation rate
file_upload_init_total{status="success|quota_exceeded|rate_limited"}

# Upload completion rate  
file_upload_complete_total{status="success|failed|timeout"}

# Upload size distribution
file_upload_size_bytes_histogram{bucket}

# Chunk presigned URL generation latency
file_upload_presigned_url_latency_seconds{quantile}

# Active upload sessions count
file_upload_sessions_active

# Quota check latency
file_quota_check_latency_seconds{quantile}
```

### Metadata Service Metrics
```
# API request rates by endpoint
http_requests_total{service="metadata", endpoint, method, status_code}

# API latency histograms
http_request_duration_seconds{service="metadata", endpoint, quantile}

# File operation rates
file_operations_total{operation="create|update|delete|move|trash"}

# Database connection pool stats
db_connection_pool_active{pool="primary|replica"}
db_connection_pool_waiting

# Cache hit/miss ratios
cache_hits_total{cache="redis", operation="file_metadata|permissions|folder_contents"}
cache_misses_total{cache="redis", operation="..."}
```

### Storage GC Metrics
```
# Chunks garbage collected
storage_gc_chunks_deleted_total

# ref_count decrements
storage_gc_ref_count_decrements_total

# S3 deletion lag (time from ref_count=0 to actual S3 deletion)
storage_gc_deletion_lag_seconds{quantile}

# Orphaned upload sessions cleaned up
storage_gc_upload_sessions_cleaned_total

# GC DLQ depth
storage_gc_dlq_messages_total
```

### Kafka Consumer Metrics
```
# Consumer group lag per topic per partition
kafka_consumer_group_lag{consumer_group, topic, partition}

# Consumer throughput
kafka_consumer_records_processed_total{consumer_group, topic}

# DLQ message count
kafka_dlq_messages_total{dlq_topic}
```

---

## Logging Strategy

### Structured Log Format (JSON)
All services emit JSON logs. Mandatory fields:

```json
{
  "timestamp": "2024-01-15T08:00:00.123Z",
  "level": "INFO",
  "service": "upload-service",
  "version": "2.3.1",
  "traceId": "abc123",
  "spanId": "def456",
  "userId": "user-uuid",
  "requestId": "req-uuid",
  "uploadId": "upload-uuid",
  "fileId": "file-uuid",
  "message": "Upload session completed",
  "durationMs": 1234,
  "chunkCount": 10,
  "fileSizeBytes": 52428800
}
```

### Log Levels Policy
| Level | When to Use | Example |
|-------|------------|---------|
| ERROR | Unhandled exception, DB connection failure, S3 error | "Failed to complete multipart upload: S3 timeout" |
| WARN | Retry attempt, slow query, quota approaching limit | "Quota at 90% for user X" |
| INFO | Upload complete, download served, file deleted | "File created: fileId=abc" |
| DEBUG | Individual chunk upload, cache hit/miss | Disabled in production by default |

### PII in Logs — Policy
| Field | Policy |
|-------|--------|
| File name | Log (business context — not PII under most frameworks) |
| File content | **Never log** |
| User email | **Never in application logs** — only userId (UUID) |
| IP addresses | Log in access logs (audit trail), not application logs |
| File paths | Log (helpful for debugging folder issues) |
| Share link tokens | **Never log** (same as a password) |

### Log Retention
| Log Type | Retention | Storage |
|----------|-----------|---------|
| Application logs | 30 days | Elasticsearch |
| Access logs (download, upload) | 1 year | S3 Standard |
| Audit logs (permission changes, deletions) | 7 years | S3 Glacier |
| Security logs (auth failures, permission denials) | 1 year | S3 Standard |

---

## Distributed Tracing

### Trace Coverage
Every inbound HTTP request generates a trace. Kafka event processing generates a linked trace (trace context propagated in Kafka message headers). All traces exported to Jaeger via OpenTelemetry.

### Critical Trace Paths

**Upload Flow Trace** (most complex):
```
Upload Service: POST /uploads/init
  ├── Quota Service: CheckQuota (sync gRPC)
  ├── PostgreSQL: INSERT upload_session
  └── S3: GeneratePresignedUrls × N chunks (batch)

[later — async]
Upload Service: POST /uploads/complete
  ├── S3: CompleteMultipartUpload
  ├── Kafka: Publish UploadCompleted
  └── [trace ends — async handoff]

Metadata Service: Consume UploadCompleted [new trace, linked to parent]
  ├── PostgreSQL: INSERT file_version + UPDATE files + UPDATE quota (transaction)
  ├── PostgreSQL: INSERT outbox_event
  └── [commits, outbox poller publishes FileCreated to Kafka]

Search Service: Consume FileCreated [new trace, linked]
  └── Elasticsearch: Index file document

Sync Service: Consume FileCreated [new trace, linked]
  └── PostgreSQL: INSERT sync_event
```

### Sampling Strategy
| Trace Type | Rate |
|------------|------|
| Upload init + complete | 100% |
| Download redirect | 1% (12,000 RPS → too high to trace all) |
| File metadata reads | 0.5% |
| Search queries | 5% |
| Error traces | 100% (force-sample on any 4xx/5xx) |
| Admin operations | 100% |
| GC operations | 10% |

---

## Dashboards

### Grafana Dashboard 1: Upload Health (Primary On-Call View)

| Panel | Metric | Alert |
|-------|--------|-------|
| Upload init rate | `file_upload_init_total` rate | — |
| Upload success rate | success / total | < 95% → WARN, < 90% → CRITICAL |
| Upload P99 latency | `http_request_duration_seconds{endpoint="/uploads/init"}` | > 2s → WARN |
| Active upload sessions | `file_upload_sessions_active` | > 50K → WARN |
| Quota exceeded rate | `file_upload_init_total{status="quota_exceeded"}` rate | > 5% → check pricing page funnel |
| S3 error rate | `file_upload_complete_total{status="failed"}` rate | > 1% → CRITICAL |

### Grafana Dashboard 2: Download & CDN Health

| Panel | Metric | Alert |
|-------|--------|-------|
| Download RPS | `http_requests_total{endpoint="/download"}` rate | — |
| CDN cache hit ratio | From CDN metrics (CloudWatch) | < 80% → WARN |
| P99 download redirect latency | `http_request_duration_seconds{endpoint="/download"}` | > 500ms → WARN |
| Origin egress (S3) | CloudWatch S3 BytesDownloaded | > 70 GB/s → WARN |

### Grafana Dashboard 3: Storage System Health

| Panel | Metric | Alert |
|-------|--------|-------|
| Kafka consumer lag — search | `kafka_consumer_group_lag{consumer_group="search-indexer-cg"}` | > 10,000 → WARN |
| Kafka consumer lag — sync | `kafka_consumer_group_lag{consumer_group="sync-service-cg"}` | > 5,000 → CRITICAL |
| GC DLQ depth | `kafka_dlq_messages_total{dlq_topic="storage-gc-dlq"}` | > 0 → CRITICAL |
| Redis memory usage | `redis_memory_used_bytes / redis_memory_max_bytes` | > 80% → WARN |
| PostgreSQL primary QPS | `pg_stat_database_xact_commit` rate | > 30,000 → WARN |
| DB replication lag | `pg_replication_slot_lag_bytes` | > 50MB → WARN |

### Grafana Dashboard 4: Business Metrics

| Panel | Metric | Notes |
|-------|--------|-------|
| Files uploaded/day | From analytics pipeline | Business KPI |
| Storage used (total, by tier) | Sum of quota events | Capacity planning |
| DAU active on platform | Unique userId in request logs | Growth metric |
| Search queries/day | From Search Service metrics | Feature engagement |
| Shares created/day | From Sharing Service | Collaboration metric |
| Top 10 largest accounts | From quota events | Pricing optimization |

---

## Alerting Rules

### CRITICAL (PagerDuty — immediate wake-up)
| Alert | Condition | Runbook |
|-------|-----------|---------|
| Upload success rate < 90% | 5-minute window | Check S3 health, Upload Service errors |
| GC DLQ has messages | Any message in `storage-gc-dlq` | Stop GC, investigate, restore S3 objects |
| DB primary unavailable | Health check fails for > 30s | Check Aurora failover, verify new primary |
| Redis cluster 2+ shards down | Shard health check | Emergency cache bypass mode |
| Search consumer lag > 50,000 | Sustained for 5 min | Check Search Service pods, Elasticsearch health |

### WARNING (Slack — business hours response)
| Alert | Condition |
|-------|-----------|
| Upload P99 > 2s | Sustained 10 min |
| CDN cache hit ratio < 80% | 30 min window |
| Sync consumer lag > 5,000 | Sustained 5 min |
| Redis memory > 80% | — |
| PostgreSQL replication lag > 50 MB | — |
| Quota exceeded error rate > 5% | Spike indicates pricing page funnel issue |

---

## Integrity Monitoring (File Storage Specific)

### Weekly S3 Integrity Check
- Job scans all chunks referenced in `file_version_chunks` → confirms each `storage_key` exists in S3 (`HeadObject`).
- Any missing chunk → P0 incident, halt GC, investigate.
- Reports: total chunks, total size, orphaned chunks (in S3 but no DB reference).

### Quota Drift Detection
- Daily reconciliation: compare `users.storage_used_bytes` (cached counter) with `SUM(delta_bytes) FROM storage_quota_events WHERE user_id = X`.
- If drift > 1% → log warning, update cached counter.
- If drift > 10% → alert on-call — indicates GC bug or quota update failure.

### Upload Session Orphan Check
- Hourly job: find upload sessions in `IN_PROGRESS` state for > 25 hours (near expiry).
- Log for analysis: how many sessions are abandoned? What file sizes? (Inform client-side retry UX improvements.)

---

## Interview-Level Discussion Points

- **How do you correlate a user complaint ("my file is stuck uploading") with a specific trace?** — User provides uploadId from their UI. Search Kibana: `uploadId="abc123"` → find all log lines across services for this upload. Link to Jaeger trace → see exactly which step (S3 PUT, quota check, Kafka publish) failed or was slow.
- **How do you detect a storage leak (GC not running correctly)?** — Two signals: (1) GC DLQ depth > 0 (most direct). (2) Weekly S3 scan finds chunks in S3 with no `file_version_chunks` reference (orphaned objects) — these are leaked. Alert if orphaned object count > 1% of total.
- **What's your error budget for download?** — Download SLO: 99.9% of requests < 200ms P99 per month. Error budget: 0.1% × 30 days × 24h × 60min = 43.2 minutes of "bad" minutes allowed per month. If burn rate is 2× expected → deploy freeze + investigation.
- **How do you catch quota enforcement bugs before they hit production?** — Staging environment: run 1000 concurrent synthetic uploads per user (Locust). Verify that `storage_used_bytes` after 1000 uploads equals exact sum of file sizes. Atomic quota tests in integration test suite.
- **Why not use Datadog/New Relic instead of the ELK + Prometheus + Jaeger stack?** — Cost: Datadog at Google Drive scale = millions per month. Self-hosted ELK + Prometheus + Jaeger: tens of thousands per month. FAANG companies universally build internal observability stacks for cost. Startups: Datadog is fine until $1M ARR then evaluate.
