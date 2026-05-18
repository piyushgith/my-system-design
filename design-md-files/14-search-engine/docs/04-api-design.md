# 04 — API Design: Mini Search Engine

## Objective

Design the REST API surface for the search platform: document ingestion, full-text search, autocomplete, faceted search, and schema management. Define query DSL, pagination strategy, idempotency, versioning, and error handling standards.

---

## 1. API Design Principles

- **Resource-oriented REST** for document and schema management (CRUD semantics)
- **Action-oriented endpoints** for search operations (POST /_search, not GET — query complexity exceeds URL limits)
- **Versioning via URL prefix** (`/api/v1/`) — explicit, cache-friendly, operationally simple
- **Tenant isolation via header** (`X-Tenant-ID`) — not in URL path (reduces coupling, allows shared routing)
- **Idempotency for writes** — PUT document by ID is idempotent; POST bulk accepts idempotency key
- **Cursor-based pagination** for search results (`search_after`) — offset pagination fails at depth

---

## 2. API Versioning Strategy

| Strategy | Decision | Rationale |
|----------|----------|-----------|
| URL prefix (`/v1/`, `/v2/`) | **Selected** | Explicit, cache-friendly, easily load-balanced |
| Header versioning (`Accept: application/vnd.search.v1+json`) | Rejected | Complicates caching and observability |
| Query parameter (`?version=1`) | Rejected | Pollutes query string; breaks caching |

**Evolution rules:**
- Non-breaking changes (new optional fields) — no version bump
- Breaking changes (field removal, type change, behavior change) — new version; old version maintained for 6 months minimum
- Deprecation headers: `Deprecation: true`, `Sunset: 2026-12-01`

---

## 3. Authentication and Headers

All requests require:

```
Authorization: Bearer <JWT>
X-Tenant-ID: <tenant-uuid>
X-Request-ID: <client-generated-uuid>  // for idempotency and tracing
Content-Type: application/json
```

---

## 4. Document Ingestion API

### 4.1 Index Single Document (Upsert)

```
PUT /api/v1/indices/{index_name}/documents/{document_id}

Request:
{
  "fields": {
    "title": "iPhone 15 Pro Max",
    "description": "Latest Apple flagship smartphone",
    "price": 1199.99,
    "category": "electronics",
    "brand": "Apple",
    "in_stock": true,
    "created_at": "2024-01-15T10:00:00Z",
    "tags": ["smartphone", "5G", "iOS"]
  }
}

Response 202 Accepted:
{
  "document_id": "doc-uuid",
  "index_name": "products",
  "version": 3,
  "status": "PENDING_INDEX",
  "estimated_index_time_ms": 3000
}
```

**Idempotency:** PUT by document_id is idempotent. Re-submitting same document_id with same payload produces same result. Duplicate detection via version comparison.

### 4.2 Partial Document Update

```
PATCH /api/v1/indices/{index_name}/documents/{document_id}

Request:
{
  "fields": {
    "price": 999.99,
    "in_stock": false
  },
  "version": 3  // optimistic lock — rejected if document version != 3
}

Response 202 Accepted:
{
  "document_id": "doc-uuid",
  "version": 4,
  "status": "PENDING_INDEX"
}
```

**Design Note:** Partial updates are supported at the API level but are implemented as read-modify-write in ES (update-by-script or doc merge). This has concurrency implications — clients must use optimistic locking via version field.

### 4.3 Delete Document

```
DELETE /api/v1/indices/{index_name}/documents/{document_id}
X-Delete-Mode: soft | hard   (default: soft)

Response 202 Accepted:
{
  "document_id": "doc-uuid",
  "status": "PENDING_DELETE"
}
```

Soft delete: marks `status=DELETED` in PostgreSQL, propagates delete to ES. Hard delete: removes from both stores (GDPR use case).

### 4.4 Bulk Indexing

```
POST /api/v1/indices/{index_name}/documents/_bulk
X-Idempotency-Key: <client-uuid>

Request:
{
  "operations": [
    { "action": "index", "document_id": "d1", "fields": { ... } },
    { "action": "index", "document_id": "d2", "fields": { ... } },
    { "action": "delete", "document_id": "d3" }
  ]
}

Response 202 Accepted:
{
  "batch_id": "batch-uuid",
  "total": 3,
  "status": "ACCEPTED",
  "estimated_completion_ms": 10000
}
```

**Idempotency-Key:** If the same key is submitted twice, the second request returns the result of the first (deduplication window: 24 hours).

**Bulk size limits:** Maximum 1,000 documents or 5 MB per request. Larger batches must be split by client.

---

## 5. Search API

### 5.1 Full-Text Search

```
POST /api/v1/indices/{index_name}/_search

Request:
{
  "query": {
    "match": {
      "field": "_all",
      "value": "apple smartphone",
      "operator": "AND",
      "fuzziness": "AUTO"
    }
  },
  "filters": [
    { "field": "category", "operator": "EQ", "value": "electronics" },
    { "field": "price", "operator": "RANGE", "gte": 500, "lte": 2000 },
    { "field": "in_stock", "operator": "EQ", "value": true }
  ],
  "sort": [
    { "field": "_score", "order": "DESC" },
    { "field": "price", "order": "ASC" }
  ],
  "page_size": 20,
  "search_after": ["0.8423", "599.99"],  // cursor for next page
  "highlight": {
    "fields": ["title", "description"],
    "pre_tag": "<em>",
    "post_tag": "</em>"
  },
  "fields": ["title", "price", "category", "brand"]  // projected fields
}

Response 200 OK:
{
  "query_id": "q-uuid",
  "total_hits": 4523,
  "took_ms": 12,
  "from_cache": false,
  "hits": [
    {
      "document_id": "d-uuid",
      "score": 0.8423,
      "fields": {
        "title": "iPhone 15 Pro Max",
        "price": 1199.99,
        "category": "electronics",
        "brand": "Apple"
      },
      "highlights": {
        "title": ["<em>iPhone</em> 15 Pro Max"]
      },
      "sort_values": ["0.8423", "1199.99"]  // used as next search_after cursor
    }
  ],
  "next_cursor": ["0.5231", "599.99"]  // null if no more results
}
```

### 5.2 Multi-Field Search

```
POST /api/v1/indices/{index_name}/_search

Request:
{
  "query": {
    "multi_match": {
      "value": "apple smartphone",
      "fields": [
        { "field": "title", "boost": 3.0 },
        { "field": "description", "boost": 1.0 },
        { "field": "brand", "boost": 2.0 }
      ],
      "type": "best_fields"  // or "cross_fields", "most_fields", "phrase"
    }
  }
}
```

### 5.3 Pagination Strategy: search_after

**Why not offset pagination?**

| Concern | Offset Pagination | search_after Cursor |
|---------|------------------|---------------------|
| Deep page performance | O(offset + page_size) — catastrophic at depth | O(page_size) — constant |
| Consistency | Results shift if new docs indexed between pages | Point-in-time snapshot (ES PIT API) |
| ES heap usage | High (must fetch top N then discard) | Low (stateless cursor) |
| Implementation | Simple | Requires sort values as cursor |

**search_after rules:**
- Requires a stable sort key (include `_id` as tiebreaker)
- Use ES Point-in-Time (PIT) API for consistent pagination across index refreshes
- PIT IDs expire after 5 minutes of inactivity — clients must page continuously

---

## 6. Faceted Search and Aggregations API

```
POST /api/v1/indices/{index_name}/_search

Request:
{
  "query": { "match": { "field": "_all", "value": "laptop" } },
  "aggregations": {
    "by_category": {
      "type": "terms",
      "field": "category",
      "size": 10
    },
    "by_brand": {
      "type": "terms",
      "field": "brand",
      "size": 20
    },
    "price_ranges": {
      "type": "range",
      "field": "price",
      "ranges": [
        { "to": 500 },
        { "from": 500, "to": 1000 },
        { "from": 1000, "to": 2000 },
        { "from": 2000 }
      ]
    },
    "avg_price": {
      "type": "avg",
      "field": "price"
    }
  }
}

Response:
{
  "hits": [ ... ],
  "aggregations": {
    "by_category": {
      "buckets": [
        { "key": "electronics", "count": 1523 },
        { "key": "accessories", "count": 342 }
      ]
    },
    "price_ranges": {
      "buckets": [
        { "key": "< 500", "count": 89 },
        { "key": "500-1000", "count": 234 }
      ]
    },
    "avg_price": { "value": 849.99 }
  }
}
```

**Design Note:** Aggregations run alongside search (one ES request). Avoid nested aggregations beyond 2 levels — exponential memory growth on data nodes.

---

## 7. Autocomplete API

```
GET /api/v1/indices/{index_name}/_suggest?q=appl&size=10

Response 200 OK:
{
  "query": "appl",
  "suggestions": [
    { "text": "apple iphone", "weight": 9523 },
    { "text": "apple macbook", "weight": 7821 },
    { "text": "apple watch", "weight": 6340 },
    { "text": "apple airpods", "weight": 4210 }
  ],
  "from_cache": true
}
```

**Implementation:** Uses ES Completion Suggester (optimized FST in-memory structure) or edge-n-gram index for prefix matching. Redis caches results for popular prefixes (top 10K prefixes by frequency).

**Why GET for autocomplete?** Low latency, cache-friendly (CDN, browser, Redis all key on URL). Query string is short (user typing prefix — usually < 30 chars). Unlike full search, no complex body needed.

---

## 8. Fuzzy Search

Fuzzy search is a parameter on the match query, not a separate endpoint:

```
POST /api/v1/indices/{index_name}/_search

{
  "query": {
    "match": {
      "field": "title",
      "value": "iphon",           // typo
      "fuzziness": "AUTO",        // 0 for len<3, 1 for len 3-5, 2 for len >5
      "prefix_length": 2,         // first N chars must match exactly (performance optimization)
      "max_expansions": 50        // limit on number of fuzzy variants to consider
    }
  }
}
```

**Fuzziness AUTO:** Industry standard. Avoids hand-tuning edit distance per query.

---

## 9. Schema Management API

### 9.1 Create Index

```
POST /api/v1/indices

Request:
{
  "index_name": "products",
  "shard_count": 5,
  "replica_count": 1,
  "mappings": {
    "fields": [
      { "name": "title", "type": "text", "analyzer": "english", "boost": 3.0 },
      { "name": "description", "type": "text", "analyzer": "english" },
      { "name": "price", "type": "float", "doc_values": true },
      { "name": "category", "type": "keyword" },
      { "name": "brand", "type": "keyword" },
      { "name": "in_stock", "type": "boolean" },
      { "name": "created_at", "type": "date" },
      { "name": "title_suggest", "type": "completion" }
    ],
    "analyzers": [
      {
        "name": "autocomplete_analyzer",
        "tokenizer": "edge_ngram",
        "token_filters": ["lowercase"],
        "min_gram": 2,
        "max_gram": 10
      }
    ]
  }
}

Response 201 Created:
{
  "index_id": "idx-uuid",
  "index_name": "products",
  "physical_index_name": "products_v1",
  "alias_name": "products",
  "status": "PROVISIONING",
  "mapping_version": 1
}
```

### 9.2 Update Mapping (Additive Only)

```
PUT /api/v1/indices/{index_name}/mappings

Request:
{
  "add_fields": [
    { "name": "rating", "type": "float", "doc_values": true }
  ]
}

Response 200 OK:
{
  "mapping_version": 2,
  "breaking_change": false,
  "reindex_required": false
}
```

### 9.3 Trigger Reindex (for Breaking Changes)

```
POST /api/v1/indices/{index_name}/_reindex

Request:
{
  "new_mappings": {
    "fields": [ ... updated field definitions ... ]
  },
  "strategy": "ZERO_DOWNTIME",  // creates new index, backfills, switches alias
  "source_batch_size": 1000,
  "throttle_ms": 100            // throttle between batches to avoid overloading ES
}

Response 202 Accepted:
{
  "reindex_job_id": "rj-uuid",
  "status": "STARTED",
  "estimated_completion_minutes": 45
}
```

---

## 10. Error Handling Standards

### Error Response Format

```json
{
  "error": {
    "code": "DOCUMENT_NOT_FOUND",
    "message": "Document d-uuid not found in index products",
    "request_id": "req-uuid",
    "timestamp": "2024-01-15T10:00:00Z",
    "details": {
      "document_id": "d-uuid",
      "index_name": "products"
    }
  }
}
```

### Standard Error Codes

| HTTP Status | Error Code | Trigger |
|-------------|------------|---------|
| 400 | `INVALID_QUERY` | Malformed query DSL |
| 400 | `SCHEMA_VALIDATION_FAILED` | Document fails field validation |
| 401 | `UNAUTHORIZED` | Missing or invalid JWT |
| 403 | `ACCESS_DENIED` | Tenant isolation violation |
| 404 | `DOCUMENT_NOT_FOUND` | Document ID not found |
| 404 | `INDEX_NOT_FOUND` | Index does not exist |
| 409 | `VERSION_CONFLICT` | Optimistic lock version mismatch |
| 409 | `INDEX_ALREADY_EXISTS` | Duplicate index creation |
| 422 | `BREAKING_SCHEMA_CHANGE` | Attempted in-place field type change |
| 429 | `RATE_LIMIT_EXCEEDED` | QPS limit hit |
| 500 | `SEARCH_BACKEND_ERROR` | ES cluster error |
| 503 | `SERVICE_UNAVAILABLE` | ES cluster unavailable; degraded mode |

### Retry Guidance (Response Headers)

```
Retry-After: 2          // on 429
X-Rate-Limit-Limit: 1000
X-Rate-Limit-Remaining: 0
X-Rate-Limit-Reset: 1705312800
```

---

## 11. Idempotency Strategy

| Operation | Idempotent? | Mechanism |
|-----------|------------|-----------|
| PUT /documents/:id | Yes | Same ID = upsert; version comparison |
| PATCH /documents/:id | Conditional | Requires `version` field (optimistic lock) |
| DELETE /documents/:id | Yes | Deleting already-deleted document is no-op |
| POST /documents/_bulk | Via key | X-Idempotency-Key header; 24hr dedup window |
| POST /_search | N/A | Read-only |
| POST /_reindex | Via idempotency key | Must not trigger duplicate reindex jobs |

---

## 12. OpenAPI / Swagger Planning

- All endpoints documented in OpenAPI 3.0 spec
- Hosted at `/api/docs` (Swagger UI)
- Machine-readable at `/api/openapi.json`
- Schema validation enforced server-side via Spring Validation + Jackson
- Request/response examples for all endpoints
- Versioned: separate spec per API version

---

## 13. Interview Discussion Points

- **Why POST for search, not GET?** Query DSL can be multi-KB in complex cases (nested aggregations, multi-field boosts, filters). URL length limits (~2048 chars) make GET infeasible. POST is also not cached by default — which is correct for parameterized queries. We add Redis caching explicitly.
- **Why search_after instead of offset?** At page 100 with page_size 20, ES must score and sort 2,000 documents to discard the first 1,980. With search_after, ES uses the sort values as a filter, making each page O(page_size). At deep pagination, this is 100x more efficient.
- **How do you version a Query DSL without breaking existing clients?** The query DSL is versioned with the API. New query operators are additive — clients sending old DSL continue to work. Removed operators are deprecated with warning headers before removal.
- **How do you handle bulk failures?** ES bulk API returns per-document success/failure. Indexing Consumer parses results, retries individual failed documents (not the whole batch), promotes to DLQ after max retries.
