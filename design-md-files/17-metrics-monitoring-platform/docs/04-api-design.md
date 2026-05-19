# 04 — API Design

## Objective

Define the complete API surface for the Metrics & Monitoring Platform — covering all ingest paths (metrics push via RemoteWrite, log ingestion, trace ingestion via OTLP), all query paths (PromQL-style metrics, log search, trace search), and all configuration paths (alert rules, dashboards, data sources). Establish versioning strategy, idempotency guarantees, rate limiting, error standards, and backward compatibility contracts.

---

## API Surface Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                        API Gateway / Ingress                     │
│                (Rate Limiting, Auth, TLS Termination)            │
└────────┬──────────────┬──────────────┬──────────────────────────┘
         │              │              │
    ┌────▼────┐   ┌─────▼─────┐  ┌────▼──────┐
    │ Ingest  │   │  Query    │  │  Config   │
    │  APIs   │   │  APIs     │  │  APIs     │
    │(REST+   │   │(REST+     │  │(REST)     │
    │ gRPC)   │   │ gRPC)     │  │           │
    └─────────┘   └───────────┘  └───────────┘
```

---

## 1. Versioning Strategy

### URI Path Versioning

All REST APIs are versioned at the URI path level:

```
/api/v1/...    ← stable, production, no breaking changes
/api/v2/...    ← next generation, parallel support
/api/beta/...  ← experimental, may break at any time
```

**Rationale:**
- URI path versioning is immediately visible in logs, reverse proxy configs, and CDN rules — critical for a monitoring platform where API traffic itself must be observable
- Header-based versioning (e.g., `Accept: application/vnd.metrics.v2+json`) is operationally invisible without log enrichment

### Versioning Contract Rules

| Rule | Description |
|------|-------------|
| No breaking changes within a version | Additive changes only (new fields, new optional params) |
| Deprecation period | Minimum 6 months concurrent support |
| Removal notice | Written to response headers: `Deprecation: date="..."`, `Sunset: date="..."` |
| Version sunset | Announced 90 days ahead via API response headers and changelog |

### gRPC Versioning

gRPC services are versioned via package namespacing in protobuf definitions:

```
package metrics.ingest.v1;
package metrics.query.v1;
package logs.ingest.v1;
package traces.ingest.v1;
```

gRPC field additions are backward compatible; field removals require a new package version.

---

## 2. Metrics Ingest APIs

### 2a. Remote Write (Push — Prometheus-compatible)

**Protocol:** HTTP/1.1 and HTTP/2  
**Endpoint:** `POST /api/v1/write`  
**Content-Type:** `application/x-protobuf` (Snappy-compressed)  
**Alternative:** `application/x-protobuf+snappy` with `X-Prometheus-Remote-Write-Version: 0.1.0`

This endpoint implements Prometheus RemoteWrite specification. The request body is a `WriteRequest` protobuf containing `TimeSeries` objects, each with labels and samples.

**Request Structure (conceptual):**
- A `WriteRequest` contains a list of `TimeSeries`
- Each `TimeSeries` has a set of `Label` key-value pairs and a list of `Sample` (timestamp, value) pairs
- Timestamps are Unix milliseconds (int64)

**Idempotency Design:**

Metric ingestion is naturally idempotent for the same `(labels, timestamp)` tuple. The TSDB storage engine applies last-write-wins semantics for duplicate samples at identical timestamps. This is acceptable because:
- Retried writes from agents carry the same samples
- The window for retries (typically < 5 minutes) falls within the TSDB's out-of-order sample acceptance window

No explicit idempotency key is required for time-series data because the `(series fingerprint, timestamp)` combination forms the natural deduplication key.

**Remote Write v2 (newer spec additions):**
- Supports `metadata` alongside samples (unit, type, help text)
- Supports exemplars (trace-to-metrics correlation)
- Supports native histograms (exponential bucketing)
- Version negotiated via `X-Prometheus-Remote-Write-Version` header

**Response Codes:**

| Code | Meaning | Client Behavior |
|------|---------|-----------------|
| 204 No Content | All samples accepted | No action |
| 400 Bad Request | Malformed request, non-retryable | Drop batch, log error |
| 429 Too Many Requests | Rate limit exceeded | Backoff, retry with `Retry-After` |
| 500 Internal Server Error | Storage failure, retryable | Exponential backoff |
| 503 Service Unavailable | Overloaded | Exponential backoff |

**Rate Limiting:**
- Per tenant (identified by `X-Scope-OrgID` header in multi-tenant deployments)
- Limits enforced: samples/second, series/minute, active series count
- Burst allowed up to 2x sustained rate for 30 seconds
- `Retry-After` header always included on 429 responses

---

### 2b. Metrics Scrape (Pull — Prometheus model)

The platform's scrape manager acts as a Prometheus-compatible scraper.

**Internal scrape flow:** The scraper is not an external API but a configurable pull engine. Target discovery is configured via:

`GET/POST/PUT/DELETE /api/v1/scrape-configs`

**Scrape Configuration Object:**
- `job_name`: logical name for the scrape job
- `scrape_interval`: how often to scrape (e.g., 15s, 60s)
- `scrape_timeout`: max wait per scrape
- `targets`: static list or service discovery config (Kubernetes, Consul, EC2)
- `metrics_path`: path on target to scrape (default `/metrics`)
- `scheme`: http or https
- `basic_auth` / `bearer_token` / `tls_config`: authentication
- `relabel_configs`: metric label rewriting rules

**Scrape Result Flow:**
1. Scraper fetches `http://{target}:{port}/metrics` (Prometheus exposition format: text/plain or OpenMetrics)
2. Response is parsed into `TimeSeries` objects
3. Relabeling rules applied
4. Written to internal Kafka topic `metrics.raw`

---

### 2c. gRPC Metrics Ingest

For high-throughput internal services, a gRPC streaming endpoint is provided:

**Service:** `MetricsIngestService`  
**RPC:** `WriteMetrics(stream WriteRequest) returns (WriteResponse)`

Bidirectional streaming allows the client to stream batches and receive acknowledgments per batch. This is preferred over repeated HTTP calls for co-located services because:
- Multiplexed connections over a single TCP connection
- Lower per-RPC overhead
- Built-in flow control via HTTP/2 window sizes

---

## 3. Log Ingest API

### 3a. HTTP Log Ingest

**Endpoint:** `POST /api/v1/logs/ingest`  
**Content-Type:** `application/json` or `application/x-ndjson` (newline-delimited JSON)  
**Alternative Encoding:** Protocol Buffers for high-throughput agents

**Log Entry Schema:**
- `timestamp`: RFC3339 or Unix nanoseconds
- `severity` / `level`: string (INFO, WARN, ERROR, FATAL) or numeric (syslog levels)
- `body`: the log message string
- `resource`: key-value map — identifies the source (service name, version, host, region)
- `attributes`: key-value map — per-log-line metadata (trace_id, span_id, user_id, request_id)
- `trace_id`: optional, for log-to-trace correlation (W3C TraceContext format)
- `span_id`: optional

**Batching:** Clients MUST batch logs. Minimum recommended batch size: 100 entries. Maximum batch size: 5 MB uncompressed. Compression (gzip, zstd) strongly recommended.

**Idempotency for Log Ingest:**

Logs are not naturally idempotent because duplicate log lines with the same timestamp from the same source are valid (rapid events within a millisecond). Therefore:

- Clients may optionally include `X-Idempotency-Key` (UUID) on the batch request
- The ingest service maintains a 60-second deduplication window in Redis using a Bloom filter keyed on `(idempotency_key)`
- If the key was seen within the window, returns 200 OK without reprocessing
- Beyond the window, duplicates are not guaranteed to be deduplicated — clients should not rely on this for correctness, only for retry safety

**Rate Limiting:**
- Per source/tenant: configurable bytes/second and entries/second limits
- Default: 10 MB/s and 100,000 entries/s per tenant
- Enforcement at the API gateway (token bucket algorithm)
- Excess requests receive `429` with `Retry-After`

**Loki-compatible API:**

For compatibility with existing Grafana Loki agents (Promtail, Alloy):
- `POST /loki/api/v1/push` — accepts Loki push format (Snappy-compressed protobuf or JSON)
- `GET /loki/api/v1/query` — instant log query
- `GET /loki/api/v1/query_range` — range log query with LogQL

---

### 3b. gRPC Log Ingest (OTLP Logs)

**Protocol:** OpenTelemetry Protocol (OTLP)  
**Endpoint:** `POST /v1/logs` (HTTP) or gRPC `opentelemetry.proto.collector.logs.v1.LogsService/Export`

OTLP Logs format:
- `ResourceLogs`: grouped by resource (a service instance)
- `ScopeLogs`: grouped by instrumentation scope (library)
- `LogRecord`: individual log entry with timestamp, severity, body, attributes, trace context

This is the preferred path for services using OpenTelemetry SDKs. OTLP receivers are co-located with the collector tier.

---

## 4. Trace Ingest API (OTLP)

### OpenTelemetry Protocol (OTLP) — Design Deep Dive

OTLP is the native protocol of OpenTelemetry. The platform implements a full OTLP receiver.

**Two transport options:**

| Transport | Endpoint | Use Case |
|-----------|----------|----------|
| gRPC | `:4317` | High-throughput services, co-located agents |
| HTTP/Protobuf | `POST /v1/traces` on `:4318` | Simpler clients, browser SDKs |
| HTTP/JSON | `POST /v1/traces` with JSON body | Debugging, simple scripts |

**OTLP Traces Structure:**
- `ResourceSpans`: grouped by resource (service instance attributes)
  - `resource.attributes`: `service.name`, `service.version`, `deployment.environment`, `host.name`
- `ScopeSpans`: grouped by instrumentation library
- `Span`: the atomic unit
  - `trace_id` (16 bytes), `span_id` (8 bytes), `parent_span_id`
  - `name`: operation name (e.g., `HTTP GET /api/users/:id`)
  - `kind`: SERVER, CLIENT, PRODUCER, CONSUMER, INTERNAL
  - `start_time_unix_nano`, `end_time_unix_nano`
  - `attributes`: key-value metadata
  - `events`: timestamped log-like entries within the span
  - `links`: references to other spans (fan-in patterns)
  - `status`: OK, ERROR, UNSET
  - `dropped_attributes_count`, `dropped_events_count`: signals client-side throttling

**OTLP Receiver Pipeline:**
1. gRPC/HTTP receiver accepts `ExportTraceServiceRequest`
2. Collector pipeline: receiver → processor (batch, memory_limiter, resource_detection, tail_sampling) → exporter
3. Tail-based sampling decision made before forwarding to Kafka
4. Kafka topic: `traces.raw` with trace_id-based partitioning

**Tail Sampling Design:**

Head-based sampling (client-side) is simple but loses interesting traces (errors, slow requests). Tail sampling buffers all spans for a trace ID for a configurable decision window (typically 30 seconds) and then applies rules:

- Always sample traces with ERROR status spans
- Always sample traces exceeding latency threshold (P99 + 2σ)
- Sample traces touching specific services at higher rate
- Apply overall rate limit (e.g., keep 10% of "boring" traces)

Tail sampling requires all spans of a single trace to route to the same collector shard — accomplished by Kafka partitioning on `trace_id` hash.

**OTLP Error Handling:**

| gRPC Status | Meaning | Client Action |
|-------------|---------|---------------|
| OK (0) | All spans accepted | None |
| INVALID_ARGUMENT (3) | Malformed spans | Drop, do not retry |
| RESOURCE_EXHAUSTED (8) | Rate limited | Retry with backoff |
| UNAVAILABLE (14) | Collector overloaded | Retry with backoff |
| DATA_LOSS (15) | Partial acceptance | Retry rejected spans only |

The `ExportTraceServiceResponse` includes a `partial_success` field indicating how many items were rejected and a human-readable error message.

---

## 5. Query APIs

### 5a. Metrics Query API (PromQL-compatible)

**Instant Query:**
```
GET /api/v1/query
Parameters:
  query: PromQL expression string
  time:  Unix timestamp or RFC3339 (evaluation time, default: now)
  timeout: max evaluation duration (default: 30s)
  step: (not used for instant query)
```

**Range Query:**
```
GET /api/v1/query_range
Parameters:
  query: PromQL expression string
  start: Unix timestamp or RFC3339
  end:   Unix timestamp or RFC3339
  step:  duration string (e.g., 15s, 1m, 5m) — resolution
  timeout: max evaluation duration
```

**Metadata Queries:**
```
GET /api/v1/labels                   → list all label names
GET /api/v1/label/{name}/values      → list values for a label name
GET /api/v1/series                   → list all matching series for a label set
GET /api/v1/metadata                 → metric name → type/help/unit mapping
```

**Response Envelope:**
```json
{
  "status": "success" | "error",
  "data": {
    "resultType": "matrix" | "vector" | "scalar" | "string",
    "result": [...],
    "stats": {
      "timings": {...},
      "samples": { "totalQueryableSamples": 0, "peakSamples": 0 }
    }
  },
  "warnings": ["..."],
  "error": "...",
  "errorType": "..."
}
```

**Query Hints / Optimization:**

Large range queries over many series can be extremely expensive. Mitigations:
- `max_samples` limit per query (configurable per tenant)
- Step alignment enforcement (step must divide evenly into range for cache efficiency)
- Query splitting: frontend splits a 30-day query into 24-hour chunks evaluated in parallel
- Query sharding: frontend splits a query across N TSDB shards and merges results

---

### 5b. Log Query API

**Instant Query:**
```
GET /api/v1/logs/query
Parameters:
  query:    LogQL or Lucene query string
  limit:    max log lines (default: 100, max: 5000)
  time:     evaluation timestamp
  direction: forward | backward
```

**Range Query with Pagination:**
```
GET /api/v1/logs/query_range
Parameters:
  query:     query string
  start:     start timestamp
  end:       end timestamp
  limit:     max results per page (default: 100, max: 5000)
  step:      for metric queries derived from logs (rate, count_over_time)
  direction: forward | backward
```

**Cursor-Based Pagination Design:**

Log queries MUST use cursor-based (keyset) pagination rather than offset pagination:

- **Why not offset?** Log indices can have millions of entries. `OFFSET 10000` requires scanning and discarding 10,000 documents — O(n) cost. Also, new log lines arriving between page requests shift offsets, causing duplicates or gaps.
- **Cursor approach:** Response includes a `next_cursor` token (opaque, base64-encoded timestamp + document ID of the last result). The next request passes `?cursor=<token>` to continue from exactly where the previous page ended.
- **Cursor expiry:** Cursors are valid for 10 minutes. Expired cursors return `410 Gone`, prompting the client to restart from the beginning.

**Pagination Response Shape:**
```json
{
  "status": "success",
  "data": {
    "resultType": "streams",
    "result": [...],
    "stats": { "ingester": {...}, "store": {...} }
  },
  "next_cursor": "eyJ0cyI6MTY...",
  "has_more": true
}
```

**Full-Text Search:**
```
POST /api/v1/logs/search
Body:
  query:     full-text or structured query
  filters:   array of key-value filters (AND semantics)
  time_range: { start, end }
  limit:     integer
  cursor:    optional continuation token
  sort:      [{ field: "timestamp", order: "desc" }]
```

---

### 5c. Trace Query API

**Get Trace by ID:**
```
GET /api/v1/traces/{traceId}
Response: complete trace with all spans, sorted by start time
```

**Search Traces:**
```
GET /api/v1/traces
Parameters:
  service:      service name filter
  operation:    operation name filter
  tags:         comma-separated key=value pairs
  start:        start timestamp
  end:          end timestamp
  min_duration: minimum trace duration (e.g., 100ms, 5s)
  max_duration: maximum trace duration
  limit:        max traces (default: 20, max: 100)
  cursor:       pagination cursor
```

**Trace Dependencies:**
```
GET /api/v1/dependencies
Parameters:
  end_time: timestamp
  lookback: duration (e.g., 1h, 24h)
Response: service dependency graph (nodes + edges with call counts/error rates)
```

**Exemplar Lookup (metrics-to-trace):**

When a metric has exemplars (from Prometheus native histogram or manual exemplar injection), the query API returns trace IDs alongside metric values. These trace IDs can be passed directly to `GET /api/v1/traces/{traceId}`.

```
GET /api/v1/query_exemplars
Parameters:
  query:  PromQL selector
  start:  start time
  end:    end time
Response: list of exemplars, each with labels, value, timestamp, and trace_id
```

---

## 6. Alert Rule CRUD

**Endpoints:**
```
GET    /api/v1/rules                     → list all rule groups
POST   /api/v1/rules                     → create rule group
GET    /api/v1/rules/{namespace}/{group} → get specific rule group
PUT    /api/v1/rules/{namespace}/{group} → replace rule group (full update)
PATCH  /api/v1/rules/{namespace}/{group} → partial update
DELETE /api/v1/rules/{namespace}/{group} → delete rule group
```

**Rule Group Object:**
- `namespace`: logical grouping (e.g., team name, application)
- `name`: rule group name
- `interval`: evaluation interval (overrides default)
- `rules`: array of `AlertingRule` or `RecordingRule` objects
  - `AlertingRule`: expr (PromQL), for (pending duration), labels (routing), annotations (message templates)
  - `RecordingRule`: record (new metric name), expr (PromQL), labels

**Validation on Write:**
- PromQL expression is parsed and validated at write time (not deferred)
- Circular recording rule dependencies are rejected
- Label names are validated against Prometheus label naming rules
- Template syntax in annotations is validated

**Idempotency:**
- `PUT` is idempotent: identical content yields same result
- `POST` with a `name` that already exists returns `409 Conflict`
- Client should use `PUT` for upsert semantics

---

## 7. Dashboard CRUD

**Endpoints:**
```
GET    /api/v1/dashboards                      → list dashboards (with pagination)
POST   /api/v1/dashboards                      → create dashboard
GET    /api/v1/dashboards/{uid}                → get dashboard by UID
PUT    /api/v1/dashboards/{uid}                → update dashboard
DELETE /api/v1/dashboards/{uid}                → delete dashboard
GET    /api/v1/dashboards/{uid}/versions       → list versions (audit trail)
GET    /api/v1/dashboards/{uid}/versions/{v}   → get specific version
POST   /api/v1/dashboards/{uid}/restore/{v}    → restore to version
POST   /api/v1/dashboards/import               → import from JSON export
GET    /api/v1/dashboards/export/{uid}         → export as JSON
```

**Dashboard Object:**
- `uid`: stable identifier (slug-like, human-readable, URL-safe)
- `title`: display name
- `folder`: organizational folder
- `tags`: array of strings for filtering
- `version`: integer, monotonically increasing on each save
- `panels`: array of visualization configs (time series, gauge, table, logs panel, trace panel)
- `variables`: template variables for dynamic dashboards
- `time`: default time range
- `refresh`: auto-refresh interval
- `annotations`: overlaid annotation queries

**Optimistic Concurrency:**

Concurrent dashboard saves are handled via optimistic locking. On `PUT`, the request MUST include `"version": <current_version>`. If the stored version differs, the server returns `409 Conflict` with the current version, prompting the client to re-fetch and re-apply changes.

**Dashboard as Code:**
- The API accepts and returns pure JSON, enabling GitOps workflows
- `POST /api/v1/dashboards/sync` accepts a batch of dashboards with `overwrite: true` for CI/CD pipeline integration

---

## 8. Data Source Management

**Endpoints:**
```
GET    /api/v1/datasources             → list data sources
POST   /api/v1/datasources             → add data source
GET    /api/v1/datasources/{id}        → get data source
PUT    /api/v1/datasources/{id}        → update data source
DELETE /api/v1/datasources/{id}        → remove data source
POST   /api/v1/datasources/{id}/health → test connectivity
POST   /api/v1/datasources/{id}/query  → proxy query to data source
```

**Data Source Types:** Prometheus, Loki, Jaeger, Tempo, Elasticsearch, ClickHouse, PostgreSQL (custom datasource plugins)

**Credentials Handling:**
- Credentials (API keys, passwords) are stored encrypted at rest (AES-256-GCM)
- `GET` responses return credential fields as `[redacted]`
- Credentials are never returned in API responses — this is a hard rule
- Credential updates require explicit fields to be set; omitted credential fields are preserved

---

## 9. Error Standard

All error responses follow a consistent envelope:

```json
{
  "status": "error",
  "errorType": "bad_data" | "execution" | "timeout" | "canceled" | "not_found" | "conflict" | "rate_limited" | "internal",
  "error": "human-readable description",
  "details": {
    "field": "specific field that caused error",
    "reason": "more specific machine-readable code",
    "retry_after_seconds": 30
  }
}
```

**Error Type Taxonomy:**

| errorType | HTTP Status | Retryable? | Meaning |
|-----------|-------------|------------|---------|
| `bad_data` | 400 | No | Invalid query, malformed input |
| `not_found` | 404 | No | Resource does not exist |
| `conflict` | 409 | No | Version conflict, duplicate resource |
| `rate_limited` | 429 | Yes | Too many requests |
| `execution` | 422 | No | Query valid but execution failed |
| `timeout` | 503 | Yes (with reduced range) | Query exceeded time limit |
| `canceled` | 499 | No | Client canceled request |
| `internal` | 500 | Yes (with backoff) | Unexpected server error |

---

## 10. Backward Compatibility Strategy

### What Constitutes a Breaking Change

| Change | Breaking? |
|--------|-----------|
| Removing a field from response | Yes |
| Renaming a field | Yes |
| Changing field type | Yes |
| Making an optional param required | Yes |
| Adding a new optional field to response | No |
| Adding a new optional query parameter | No |
| Adding a new endpoint | No |
| Changing default values of optional params | Soft break — document it |
| Narrowing accepted value ranges | Yes |

### Compatibility Mechanisms

- **Additive-only changes** within a version: all new fields are optional and nullable
- **Field deprecation**: deprecated fields marked in API documentation and included in response headers as `Warning: 299 - "Field 'foo' deprecated, use 'bar' instead. Sunset: 2026-12-01"`
- **Feature detection**: clients can query `GET /api/v1/status/buildinfo` to discover supported feature flags before using newer API features
- **PromQL backward compatibility**: the platform maintains a curated list of PromQL functions; new functions are additive, no function is removed without a 2-version deprecation cycle

---

## Tradeoffs

| Decision | Chosen Approach | Alternative | Rationale |
|----------|----------------|-------------|-----------|
| Metrics ingest protocol | Prometheus RemoteWrite (protobuf) | OpenMetrics text format | Binary encoding is 5-10x smaller; lower ingest CPU |
| Pagination for logs | Cursor-based | Offset-based | Offset becomes O(n) at millions of documents; cursors are O(1) |
| Error format | Custom JSON envelope | RFC 7807 Problem Details | Prometheus ecosystem compatibility; RFC 7807 could be adopted in v2 |
| gRPC vs REST for ingest | Both (REST primary, gRPC optional) | gRPC only | REST is universally accessible; gRPC offers streaming for co-located high-throughput services |
| Dashboard versioning | Integer version + optimistic locking | Git-style hash | Integer versions are human-readable and sort trivially; OCC prevents lost updates |
| OTLP transport | HTTP + gRPC both supported | gRPC only | HTTP support required for environments where gRPC is blocked by proxies |

---

## Risks

- **PromQL injection risk:** PromQL expressions in alert rules and queries must be sanitized; a malicious query like `sum(count_over_time({__name__=~".+"}[1y]))` can exhaust storage I/O — mitigated by per-query cost limits
- **Log body size abuse:** Without size limits on `body` field, a single log entry could be gigabytes — enforce per-entry body size limit (64 KB default)
- **Cursor abuse:** Clients holding cursors open indefinitely — cursor expiry and server-side state cleanup (Redis TTL) is mandatory
- **Dashboard import injection:** Importing a dashboard with crafted panel queries could bypass per-tenant rate limits — validate all embedded queries against tenant limits on import
- **Credential exposure in logs:** Data source credentials must be scrubbed from all debug logs, request logs, and distributed traces — audit log scrubbing rules must cover credential field names

---

## Interview Discussion Points

1. **Why RemoteWrite over a pure pull model for some use cases?** Push enables ephemeral workloads (short-lived batch jobs) and firewalled environments where the collector cannot reach the target. The Prometheus pull model requires the scraper to have network access to every target.

2. **How do you handle out-of-order samples in RemoteWrite?** TSDB accepts out-of-order samples within a configurable window (default: 1 hour). Samples older than the out-of-order window are rejected with a non-retryable 400. Agents are expected to buffer and retry only within this window.

3. **Why cursor-based pagination vs. Elasticsearch's `search_after`?** `search_after` is Elasticsearch's implementation of keyset pagination. The platform cursor token encodes the exact `search_after` parameters (last timestamp + `_id`), making it a stable opaque cursor for clients while using the most efficient Elasticsearch pagination internally.

4. **How do you enforce PromQL query cost limits without executing the query?** The PromQL expression is parsed into an AST. The AST can be analyzed statically to estimate cost: number of selected series (via label matchers), range duration, aggregation depth. Queries with estimated cost above a threshold are rejected before execution.

5. **What is the difference between OTLP/gRPC and OTLP/HTTP from a production operations standpoint?** gRPC uses HTTP/2 multiplexing — one connection carries many RPCs. HTTP uses one connection per request (HTTP/1.1) or multiplexing (HTTP/2). Many enterprise load balancers and firewalls do not support HTTP/2 trailers used by gRPC — OTLP/HTTP avoids this. gRPC also has slightly more complex TLS setup for mutual TLS. OTLP/HTTP is the safer default for heterogeneous environments.

6. **How would you design the API for multi-tenancy in a SaaS monitoring platform?** Tenant isolation via `X-Scope-OrgID` header (Grafana Mimir convention) or JWT claims. All storage, query, and rate limit operations are scoped to the tenant ID extracted from auth context. Tenants cannot see each other's metrics, logs, or traces. Tenant quotas (active series, ingestion rate, retention) are enforced independently.
