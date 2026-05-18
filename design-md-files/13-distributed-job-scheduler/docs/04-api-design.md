# 04 — API Design: Distributed Job Scheduler

## Objective
Design a complete, production-grade REST API for the distributed job scheduler, covering job management, execution control, worker visibility, and operational endpoints. Address versioning, idempotency, pagination, error handling, and backward compatibility.

---

## 1. API Design Principles

- **Resource-oriented REST** — nouns, not verbs; HTTP methods carry semantic meaning
- **Versioned from day 1** — URL path versioning (`/api/v1/`) to allow breaking changes in v2
- **Idempotent mutations** — job creation and manual triggers use client-provided idempotency keys
- **Consistent error format** — RFC 7807 Problem Details for HTTP APIs
- **Cursor-based pagination** — for all list endpoints (not offset, due to insert-heavy writes)
- **Partial updates** — PATCH with JSON Merge Patch (RFC 7396) for job updates
- **Rate limiting response headers** — `X-RateLimit-Remaining`, `X-RateLimit-Reset`

---

## 2. Base URL and Versioning

```
Base: https://scheduler.internal/api/v1
Content-Type: application/json
Accept: application/json
Authorization: Bearer <JWT>
Idempotency-Key: <UUID> (required for POST/PATCH)
X-Namespace: <namespace> (required for multi-tenant, or derived from JWT claims)
```

**Versioning Strategy:**
- URL path versioning (`/api/v1/`) for major breaking changes
- Header versioning (`API-Version: 2024-01-15`) for minor field additions (additive changes don't bump major version)
- Deprecated endpoints: 6-month sunset window with `Deprecation` and `Sunset` response headers

---

## 3. Job Management APIs

### 3.1 Register a Job

```
POST /api/v1/jobs
Headers: Idempotency-Key: <uuid>

Request Body:
{
  "name": "daily-report-generator",
  "description": "Generates daily sales report",
  "jobType": "HTTP",
  "priority": "P1",
  "jobGroup": "reporting",
  "schedule": {
    "type": "CRON",
    "cronExpression": "0 2 * * *",
    "timezone": "America/New_York",
    "misfirePolicy": "FIRE_ONCE",
    "startAt": "2024-01-01T00:00:00Z",
    "endAt": null
  },
  "executionConfig": {
    "timeoutSeconds": 3600,
    "maxConcurrent": 1,
    "exclusiveLock": true,
    "retryPolicy": {
      "maxAttempts": 3,
      "backoffType": "EXPONENTIAL",
      "initialDelaySeconds": 60,
      "multiplier": 2.0,
      "maxDelaySeconds": 3600,
      "jitterEnabled": true
    }
  },
  "httpConfig": {
    "url": "https://reports.internal/generate",
    "method": "POST",
    "headers": {"Authorization": "{{secrets.REPORT_API_KEY}}"},
    "bodyTemplate": "{\"date\": \"{{trigger_date}}\", \"format\": \"PDF\"}"
  },
  "tags": {"team": "data", "env": "prod"},
  "dependencies": []
}

Response 201 Created:
{
  "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "daily-report-generator",
  "status": "ACTIVE",
  "nextExecutionTime": "2024-01-02T07:00:00Z",
  "createdAt": "2024-01-01T12:00:00Z",
  "_links": {
    "self": "/api/v1/jobs/3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "executions": "/api/v1/jobs/3fa85f64-5717-4562-b3fc-2c963f66afa6/executions",
    "trigger": "/api/v1/jobs/3fa85f64-5717-4562-b3fc-2c963f66afa6/trigger"
  }
}
```

**Idempotency Behavior:** Requests with the same `Idempotency-Key` within 24 hours return the same `jobId` without creating a duplicate. The server stores idempotency keys with their response for 24 hours.

---

### 3.2 Get Job Details

```
GET /api/v1/jobs/{jobId}

Response 200 OK:
{
  "jobId": "3fa85f64-...",
  "name": "daily-report-generator",
  "status": "ACTIVE",
  "priority": "P1",
  "schedule": { ... },
  "executionConfig": { ... },
  "nextExecutionTime": "2024-01-02T07:00:00Z",
  "lastExecutionTime": "2024-01-01T07:00:00Z",
  "lastExecutionStatus": "COMPLETED",
  "version": 3,
  "createdAt": "2024-01-01T12:00:00Z",
  "updatedAt": "2024-01-01T15:00:00Z"
}
```

---

### 3.3 List Jobs

```
GET /api/v1/jobs?cursor=<opaque>&limit=50&status=ACTIVE&jobGroup=reporting&sortBy=nextExecutionTime&order=asc

Response 200 OK:
{
  "data": [ { ... }, { ... } ],
  "pagination": {
    "cursor": "eyJuZXh0IjoiMjAyNC0wMS0wMiJ9",
    "hasMore": true,
    "limit": 50,
    "totalCount": 2840
  }
}
```

**Cursor pagination design:** The cursor is a base64-encoded JSON containing the sort key value of the last returned item. This is stable under concurrent inserts (unlike offset pagination which skips rows on insert).

**Supported filters:** `status`, `jobGroup`, `jobType`, `priority`, `namespace`, `tags` (key=value), `nextExecutionTimeBefore`, `nextExecutionTimeAfter`

---

### 3.4 Update Job (Partial)

```
PATCH /api/v1/jobs/{jobId}
Headers: Idempotency-Key: <uuid>, If-Match: "3" (version for optimistic lock)

Request Body (JSON Merge Patch):
{
  "schedule": {
    "cronExpression": "0 3 * * *"
  },
  "executionConfig": {
    "timeoutSeconds": 7200
  }
}

Response 200 OK:
{
  "jobId": "3fa85f64-...",
  "version": 4,
  "nextExecutionTime": "2024-01-02T08:00:00Z",
  "updatedAt": "2024-01-01T16:00:00Z"
}

Response 409 Conflict (version mismatch):
{
  "type": "https://scheduler.internal/errors/version-conflict",
  "title": "Optimistic lock conflict",
  "detail": "Job was updated by another request. Current version is 4, provided version was 3.",
  "currentVersion": 4
}
```

---

### 3.5 Pause / Resume / Delete Job

```
POST /api/v1/jobs/{jobId}/pause
Body: { "reason": "Maintenance window" }
Response 200 OK: { "jobId": "...", "status": "PAUSED" }

POST /api/v1/jobs/{jobId}/resume
Response 200 OK: { "jobId": "...", "status": "ACTIVE", "nextExecutionTime": "..." }

DELETE /api/v1/jobs/{jobId}
Response 204 No Content
```

**Soft delete:** `DELETE` marks status as `DELETED`; the record is retained for audit. Physical deletion only via admin API after retention period.

---

## 4. Trigger APIs

### 4.1 Manual Trigger

```
POST /api/v1/jobs/{jobId}/trigger
Headers: Idempotency-Key: <uuid>

Request Body:
{
  "overrideParams": { "date": "2024-01-15", "format": "CSV" },
  "priority": "P0",
  "comment": "Manual rerun for Jan 15 data fix"
}

Response 202 Accepted:
{
  "executionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "jobId": "3fa85f64-...",
  "status": "QUEUED",
  "triggeredBy": "user:john.doe@company.com",
  "triggeredAt": "2024-01-16T10:00:00Z",
  "_links": {
    "execution": "/api/v1/executions/7c9e6679-..."
  }
}
```

**202 Accepted** (not 200/201) because execution is asynchronous — the response only confirms the job was enqueued.

---

## 5. Execution APIs

### 5.1 Get Execution Details

```
GET /api/v1/executions/{executionId}

Response 200 OK:
{
  "executionId": "7c9e6679-...",
  "jobId": "3fa85f64-...",
  "jobName": "daily-report-generator",
  "status": "COMPLETED",
  "triggerType": "MANUAL",
  "scheduledFor": null,
  "startedAt": "2024-01-16T10:00:05Z",
  "completedAt": "2024-01-16T10:02:30Z",
  "durationMs": 145000,
  "attemptNumber": 1,
  "workerId": "worker-az1-pod-3",
  "result": {
    "exitCode": 0,
    "outputSummary": "Generated report: sales_2024_01_15.pdf",
    "logUrl": "https://logs.internal/executions/7c9e6679-..."
  },
  "triggeredBy": "user:john.doe@company.com"
}
```

---

### 5.2 List Executions for a Job

```
GET /api/v1/jobs/{jobId}/executions?cursor=<opaque>&limit=20&status=FAILED&fromTime=2024-01-01T00:00:00Z&toTime=2024-01-31T23:59:59Z

Response 200 OK:
{
  "data": [ { ... } ],
  "pagination": { ... }
}
```

---

### 5.3 Cancel an Execution

```
POST /api/v1/executions/{executionId}/cancel
Body: { "reason": "Manual intervention" }

Response 200 OK (if QUEUED or EXECUTING):
{ "executionId": "...", "status": "CANCELLED" }

Response 409 Conflict (if already terminal):
{
  "type": "https://scheduler.internal/errors/terminal-state",
  "title": "Cannot cancel terminal execution",
  "detail": "Execution 7c9e6679 is already COMPLETED and cannot be cancelled."
}
```

---

### 5.4 Execution Log Streaming

```
GET /api/v1/executions/{executionId}/logs
Accept: text/event-stream (SSE for live streaming)
OR
Accept: application/json (returns log URL for completed executions)

Response (SSE while EXECUTING):
data: {"timestamp":"2024-01-16T10:00:07Z","level":"INFO","message":"Starting report generation"}
data: {"timestamp":"2024-01-16T10:00:10Z","level":"INFO","message":"Fetching sales data for 2024-01-15"}
...

Response (JSON for COMPLETED):
{
  "logUrl": "https://logs.internal/executions/7c9e6679-.../full",
  "presignedUrl": "https://s3.amazonaws.com/...",
  "expiresAt": "2024-01-17T10:00:00Z"
}
```

---

## 6. Worker APIs

### 6.1 List Workers

```
GET /api/v1/workers?status=ACTIVE&type=HTTP&zone=us-east-1a

Response 200 OK:
{
  "data": [
    {
      "workerId": "worker-az1-pod-3",
      "hostname": "scheduler-worker-7d9f8b-kxv2p",
      "type": "HTTP",
      "status": "ACTIVE",
      "zone": "us-east-1a",
      "capabilities": ["standard"],
      "currentLoad": 8,
      "maxConcurrency": 10,
      "utilizationPercent": 80,
      "lastHeartbeatAt": "2024-01-16T10:00:58Z"
    }
  ],
  "pagination": { ... }
}
```

---

## 7. Operational APIs

### 7.1 Health Check

```
GET /actuator/health

Response 200 OK:
{
  "status": "UP",
  "components": {
    "schedulerEngine": { "status": "UP", "leaderNode": "scheduler-0", "isLeader": true },
    "database": { "status": "UP" },
    "redis": { "status": "UP" },
    "kafka": { "status": "UP", "consumerLag": 145 }
  }
}
```

---

### 7.2 Metrics (Prometheus format)

```
GET /actuator/prometheus
```

---

### 7.3 DLQ Overview

```
GET /api/v1/admin/dlq?cursor=<opaque>&limit=50

Response 200 OK:
{
  "data": [
    {
      "dlqEntryId": "...",
      "jobId": "...",
      "executionId": "...",
      "failureReason": "HTTP 503 from target service",
      "attemptCount": 3,
      "enqueuedAt": "2024-01-16T09:00:00Z"
    }
  ]
}

POST /api/v1/admin/dlq/{dlqEntryId}/retry
Response 202 Accepted: { "newExecutionId": "..." }

DELETE /api/v1/admin/dlq/{dlqEntryId}
Response 204 No Content
```

---

## 8. Error Response Format (RFC 7807)

```json
{
  "type": "https://scheduler.internal/errors/job-not-found",
  "title": "Job Not Found",
  "status": 404,
  "detail": "No job found with ID 3fa85f64-5717-4562-b3fc-2c963f66afa6 in namespace production.",
  "instance": "/api/v1/jobs/3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "traceId": "7e3d2a1b0f9c4e5d",
  "timestamp": "2024-01-16T10:00:00Z"
}
```

### Standard Error Codes

| HTTP Status | Error Type | Cause |
|---|---|---|
| 400 | `validation-error` | Invalid cron expression, missing required fields |
| 401 | `unauthorized` | Missing or invalid JWT |
| 403 | `forbidden` | Insufficient permissions for namespace/operation |
| 404 | `job-not-found` / `execution-not-found` | Resource doesn't exist |
| 409 | `version-conflict` / `terminal-state` | Optimistic lock fail, invalid state transition |
| 429 | `rate-limit-exceeded` | Too many requests |
| 500 | `internal-error` | Unexpected server failure |
| 503 | `service-unavailable` | Scheduler undergoing failover |

---

## 9. Rate Limiting

| Endpoint Category | Limit | Window |
|---|---|---|
| Job registration (POST /jobs) | 100 req/min | Per namespace |
| Manual triggers (POST .../trigger) | 1000 req/min | Per namespace |
| Status reads (GET /executions, /jobs) | 10,000 req/min | Per namespace |
| Worker heartbeat (internal) | 10,000 req/min | Per worker pool |
| Admin DLQ operations | 100 req/min | Per user |

Rate limiting implemented at API Gateway using Redis token bucket. Headers returned:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87
X-RateLimit-Reset: 1705399260
Retry-After: 12 (on 429)
```

---

## 10. Backward Compatibility Strategy

- **Additive changes** (new optional fields, new enum values) — minor version bump, no client changes required
- **Breaking changes** (removing fields, changing field semantics, restructuring) — new major API version (`/api/v2/`)
- **Sunset timeline** — v1 maintained for 12 months after v2 GA; `Deprecation: true` and `Sunset: <date>` headers added on deprecated endpoints
- **Client libraries** — official SDKs (Java, Python, Go) versioned separately; internal callers use SDK, not raw REST

---

## Interview Discussion Points

**Q: Why cursor-based pagination and not offset?**
A: Execution history tables receive heavy insert traffic. Offset pagination (`LIMIT 50 OFFSET 1000`) rescans rows on every request. If new executions are inserted while a user pages through results, they either see duplicates or skip rows. Cursor pagination is stable — it uses the last-seen sort key to continue from exactly that position.

**Q: How does idempotency work if the server crashes after processing but before returning the response?**
A: The idempotency key and response are persisted transactionally in the same database write as the job creation. On retry with the same key, the server returns the stored response. The client retrying a 5xx response with the same key gets the same 201 Created — not a duplicate job.

**Q: What's the contract for exactly-once manual trigger?**
A: The API guarantees exactly-once enqueuing. Whether the job executes exactly-once is a separate guarantee provided by the distributed lock + fencing token mechanism in the execution layer. These are explicitly called out as separate SLAs in the API documentation.
