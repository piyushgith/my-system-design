# 04 — API Design: CI/CD Platform

---

## Objective

Define REST APIs for the control plane, real-time streaming endpoints, webhook ingestion, runner registration, and internal service contracts. Cover versioning, idempotency, pagination, and error handling standards.

---

## API Layers

| Layer | Protocol | Consumers |
|---|---|---|
| Control Plane REST API | HTTPS/JSON | Web UI, CLI, external integrations |
| Webhook Ingestion API | HTTPS/JSON | Git providers (GitHub, GitLab) |
| Log Streaming API | SSE over HTTPS | Browser (log tail), CLI |
| Runner Internal API | gRPC over mTLS | Runner agents (internal only) |
| Status WebSocket | WSS | Browser (real-time status) |

---

## Base URL Structure

```
Public API:       https://api.cicd.example.com/v1
Webhook Receiver: https://hooks.cicd.example.com/v1
Internal (runner): grpc://runner-api.cicd.internal:9090
```

---

## Authentication

| Client Type | Method |
|---|---|
| Web UI / CLI users | JWT (OAuth2 Authorization Code flow) |
| Machine tokens (CI bots, API) | Personal Access Token (PAT) — Bearer token |
| Git provider webhooks | HMAC-SHA256 signature in `X-Hub-Signature-256` header |
| Runners (managed) | Short-lived JWT issued by Scheduler per job |
| Runners (self-hosted) | Registration token exchanged for runner credentials |

---

## Control Plane REST API

### Pipeline Runs

**Trigger a manual run:**
```
POST /v1/repos/{repoId}/workflows/{workflowName}/dispatch

Request:
{
  "ref": "main",
  "inputs": {
    "environment": "staging",
    "version": "v2.1.0"
  }
}

Response 201:
{
  "runId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "QUEUED",
  "workflowName": "deploy",
  "branch": "main",
  "createdAt": "2024-01-15T10:30:00Z",
  "links": {
    "self": "/v1/runs/550e8400...",
    "logs": "/v1/runs/550e8400.../logs",
    "jobs": "/v1/runs/550e8400.../jobs"
  }
}
```

**List runs for a repository:**
```
GET /v1/repos/{repoId}/runs?status=FAILED&branch=main&limit=20&cursor=<token>

Response 200:
{
  "runs": [
    {
      "runId": "...",
      "status": "FAILED",
      "workflowName": "ci",
      "branch": "main",
      "commitSha": "abc123",
      "triggeredBy": "push",
      "createdAt": "...",
      "duration": 245
    }
  ],
  "nextCursor": "base64token",
  "hasMore": true
}
```

**Get run details:**
```
GET /v1/runs/{runId}

Response 200:
{
  "runId": "...",
  "status": "IN_PROGRESS",
  "jobs": [
    {
      "jobId": "...",
      "name": "build",
      "status": "SUCCEEDED",
      "duration": 120,
      "runnerId": "runner-042"
    },
    {
      "jobId": "...",
      "name": "test",
      "status": "RUNNING",
      "startedAt": "..."
    }
  ]
}
```

**Cancel a run:**
```
POST /v1/runs/{runId}/cancel

Response 200:
{ "status": "CANCELLED" }

Errors:
  409 Conflict: Run already in terminal state
```

**Re-run failed jobs:**
```
POST /v1/runs/{runId}/rerun

Request:
{ "failedJobsOnly": true }

Response 201:
{ "newRunId": "...", "status": "QUEUED" }
```

---

### Jobs

**Get job details:**
```
GET /v1/jobs/{jobId}

Response 200:
{
  "jobId": "...",
  "runId": "...",
  "name": "build",
  "status": "SUCCEEDED",
  "steps": [
    {
      "stepIndex": 0,
      "name": "Checkout",
      "status": "SUCCEEDED",
      "duration": 3
    },
    {
      "stepIndex": 1,
      "name": "Run tests",
      "status": "SUCCEEDED",
      "duration": 95
    }
  ],
  "runnerId": "runner-042",
  "logUrl": "/v1/jobs/.../logs"
}
```

---

### Logs

**Get job logs (completed job):**
```
GET /v1/jobs/{jobId}/logs?format=text&startLine=0&endLine=500

Response 200 (text/plain):
1: + docker build -t myapp:latest .
2: [+] Building 45.3s (12/12)
...

Response 200 (application/json):
{
  "lines": [
    { "lineNum": 1, "timestamp": "2024-01-15T10:30:01.123Z", "text": "+ docker build..." },
    ...
  ],
  "totalLines": 1234,
  "hasMore": true,
  "nextStartLine": 501
}
```

**Stream live logs (running job):**
```
GET /v1/jobs/{jobId}/logs/stream
Accept: text/event-stream

Response:
HTTP/1.1 200 OK
Content-Type: text/event-stream

data: {"lineNum": 1, "text": "+ npm install", "timestamp": "..."}

data: {"lineNum": 2, "text": "added 1023 packages", "timestamp": "..."}

: heartbeat

data: {"event": "job_complete", "status": "SUCCEEDED"}
```

SSE connection drops when job completes. Browser reconnects for subsequent jobs.

---

### Secrets

**Create/update secret:**
```
PUT /v1/orgs/{orgId}/secrets/{name}

Request:
{
  "value": "super-secret-value"
}

Response 200:
{
  "name": "AWS_SECRET_KEY",
  "createdAt": "...",
  "updatedAt": "...",
  "lastAccessedAt": null
}

Note: value is NEVER returned. Write-only endpoint.
```

**List secrets (metadata only):**
```
GET /v1/orgs/{orgId}/secrets

Response 200:
{
  "secrets": [
    { "name": "AWS_SECRET_KEY", "updatedAt": "...", "repoScoped": false },
    { "name": "DB_PASSWORD", "updatedAt": "...", "repoScoped": true, "repoId": "..." }
  ]
}
```

**Delete secret:**
```
DELETE /v1/orgs/{orgId}/secrets/{name}
Response 204
```

---

### Artifacts

**List artifacts for a run:**
```
GET /v1/runs/{runId}/artifacts

Response 200:
{
  "artifacts": [
    {
      "artifactId": "...",
      "name": "test-results",
      "sizeBytes": 2048576,
      "jobName": "test",
      "uploadedAt": "...",
      "expiresAt": "..."
    }
  ]
}
```

**Download artifact:**
```
GET /v1/artifacts/{artifactId}/download
Response 302 → S3 presigned URL (valid for 15 minutes)
```

---

### Runner Management (Self-Hosted)

**Register a runner:**
```
POST /v1/orgs/{orgId}/runners/register

Request:
{
  "name": "my-runner",
  "labels": ["self-hosted", "linux", "gpu"],
  "token": "<registration-token>"  // one-time token from UI
}

Response 201:
{
  "runnerId": "...",
  "credentials": {
    "clientId": "...",
    "clientSecret": "...",  // store securely — shown only once
  }
}
```

---

## Webhook Ingestion API

```
POST /v1/webhooks/github
Headers:
  X-GitHub-Event: push
  X-Hub-Signature-256: sha256=<hmac>
  X-GitHub-Delivery: <unique-delivery-id>

Body: GitHub push event payload

Response 200: { "received": true }
Response 400: { "error": "invalid signature" }
Response 409: { "error": "duplicate delivery" }
```

**Idempotency:** `X-GitHub-Delivery` UUID deduplicated — if same delivery ID received twice, second returns 200 immediately without reprocessing.

**Must respond within 10 seconds** (Git providers timeout on webhook delivery). Processing is async — platform responds immediately after queueing.

---

## Runner Internal gRPC API

```protobuf
service RunnerService {
  rpc PollJob(PollRequest) returns (JobAssignment);
  rpc ReportStatus(StatusReport) returns (StatusAck);
  rpc Heartbeat(HeartbeatRequest) returns (HeartbeatResponse);
  rpc GetJobToken(GetTokenRequest) returns (JobToken);
}

message PollRequest {
  string runner_id = 1;
  repeated string labels = 2;
}

message JobAssignment {
  string job_id = 1;
  string run_id = 2;
  string repo_clone_url = 3;
  string commit_sha = 4;
  string workflow_yaml = 5;       // base64 encoded
  repeated string secret_names = 6;
  string job_token = 7;           // short-lived token for secret fetch
  int32 timeout_minutes = 8;
}

message StatusReport {
  string job_id = 1;
  string status = 2;              // RUNNING, SUCCEEDED, FAILED, etc.
  repeated StepStatus steps = 3;
  google.protobuf.Timestamp completed_at = 4;
  int32 exit_code = 5;
}
```

**Why gRPC for runner API?**
- Runners are internal, frequent polling — binary protocol reduces overhead
- Bi-directional streaming potential (push job to runner vs poll)
- mTLS authentication with runner certificates
- Strong contract via proto schema

---

## Versioning Strategy

- URL versioning: `/v1/`, `/v2/` per major breaking change
- Minor additions (new optional fields, new endpoints) don't bump version
- Deprecated fields marked with `x-deprecated: true` in OpenAPI spec, removed in next major version
- `/v1` supported for minimum 12 months after `/v2` GA
- Breaking changes: different response schema, removed fields, changed semantics

---

## Pagination Strategy

All list endpoints use **cursor-based pagination**:

```json
{
  "items": [...],
  "nextCursor": "eyJpZCI6IjEyMyIsInRzIjoiMjAyNCJ9",
  "hasMore": true,
  "totalCount": 15234  // only if explicitly requested via ?count=true (expensive)
}
```

Cursor encodes: last-seen item ID + sort field value. Opaque to client (base64 encoded). Stable across concurrent writes (new items don't shift existing pages).

**Why not offset pagination?** `OFFSET 1000` on PostgreSQL requires scanning 1000 rows. At 1M runs, `OFFSET 50000` is unacceptably slow. Cursor pagination is O(log n) (index seek).

---

## Idempotency

**POST endpoints that create resources:**
```
Idempotency-Key: <client-generated UUID>

If same Idempotency-Key received within 24h → return original response (no duplicate creation)
```

Applied to: `/dispatch`, runner `/register`, artifact upload initiation.

**Webhook deduplication:** `X-GitHub-Delivery` UUID stored in DB with 24h TTL.

---

## Error Handling Standards

```json
{
  "error": {
    "code": "RUN_NOT_FOUND",
    "message": "Run with ID '550e...' not found",
    "details": {
      "runId": "550e8400-e29b-41d4-a716-446655440000"
    },
    "requestId": "req_abc123",
    "timestamp": "2024-01-15T10:30:00Z"
  }
}
```

**HTTP Status Codes:**

| Code | Scenario |
|---|---|
| 200 | Success |
| 201 | Resource created |
| 204 | Success, no body (delete) |
| 400 | Invalid request (invalid YAML, bad params) |
| 401 | Not authenticated |
| 403 | Authenticated but not authorized |
| 404 | Resource not found |
| 409 | Conflict (duplicate trigger, cancel terminal run) |
| 422 | Semantically invalid (pipeline references undefined secret) |
| 429 | Rate limit exceeded |
| 503 | Service unavailable (runner pool exhausted) |

**Error codes are machine-readable strings** (not just HTTP codes) — clients can handle `RUN_ALREADY_TERMINAL` differently from `UNAUTHORIZED`.

---

## Rate Limiting

| Endpoint Category | Limit |
|---|---|
| Webhook delivery | 1000/min per repository |
| API (authenticated) | 1000/min per token |
| Manual pipeline trigger | 100/min per org |
| Log streaming connections | 100 concurrent per org |
| Secret reads | 500/min per org (via API) |

Rate limit headers:
```
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 750
X-RateLimit-Reset: 1705312200
```

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| SSE over WebSocket for logs | Simpler; HTTP-native; works through CDN/proxies | One-way only; no log search/filter from client |
| Cursor pagination over offset | Performance at scale | Harder to implement server-side; opaque cursor |
| gRPC for runner API | Efficiency + type safety for internal API | Less debuggable than REST; gRPC tooling required |
| Presigned URLs for artifacts | Direct client→S3 bandwidth; no server proxy | Time-limited (15 min); must handle expiry |
| YAML-in-repo vs API-defined | Git source of truth; version controlled | Users can't define pipelines without git access |

---

## Interview Discussion Points

- **How do you handle a long-running log stream connection being dropped?** SSE has `Last-Event-ID` header — client reconnects with last received line number. Server resumes from that line. Log service buffers last 1000 lines in Redis for fast reconnect
- **What is the security risk of presigned URLs?** URL gives anyone with the link access to the artifact for 15 minutes. Mitigation: short TTL, HTTPS only, can add IP restriction on S3 bucket policy. Risk is low — artifacts are typically public build outputs
- **Why return 200 for webhook even if processing fails?** Git provider retries on non-2xx or timeout. We want to ACK receipt and process async. If we return error codes, Git provider may retry rapidly and flood the queue. Better: accept all valid-signature webhooks, handle processing failures internally
- **How do you prevent API abuse (mass pipeline triggers)?** Rate limiting per org at API Gateway. Concurrency limit enforced in Scheduler (org cannot exceed N concurrent jobs regardless of how many triggers arrive). Additional: per-branch concurrency limit (only 1 run per branch at a time)
