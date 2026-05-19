# 15 — Implementation Roadmap: Mini Search Engine

---

## Objective

Define a phased implementation plan from a PostgreSQL full-text search MVP to a production-grade Elasticsearch-backed search platform supporting 100M documents, 10K QPS, multi-tenancy, and near-real-time indexing. Each phase includes architecture evolution, infrastructure evolution, team scaling, and risk profile.

---

## Phase 0: MVP (Weeks 1–4) — 1–2 Engineers

### Goal

A working search API backed by PostgreSQL full-text search. No Elasticsearch, no Kafka, no separate indexing pipeline. Demonstrates the search interface and domain model before committing to infrastructure complexity.

### Features

- Document ingestion: `POST /api/v1/documents` — store document in PostgreSQL
- Full-text search: `GET /api/v1/search?q={query}` — PostgreSQL `tsvector` + `ts_rank`
- Basic field filtering: `tenant_id`, `document_type`, `created_after`
- Pagination: offset-based, page size ≤ 100
- Document delete: soft delete via `deleted_at` column
- No autocomplete, no fuzzy search, no faceted aggregations

### Architecture

```
Client → Spring Boot (single instance)
              ↓
         PostgreSQL (single node)
         - documents table (id, tenant_id, content, search_vector tsvector, created_at)
         - GIN index on search_vector
         - pg_trgm for fuzzy fallback
```

### PostgreSQL Full-Text Setup

```
-- Generated column, auto-updated on INSERT/UPDATE
ALTER TABLE documents ADD COLUMN search_vector tsvector
  GENERATED ALWAYS AS (to_tsvector('english', content)) STORED;

CREATE INDEX documents_search_vector_gin ON documents USING GIN(search_vector);
```

### Infrastructure

- Single EC2 t3.medium or Docker Compose locally
- PostgreSQL RDS t3.micro (or local)
- No Redis, no Kafka, no Elasticsearch

### Deliverables

- `POST /api/v1/documents` — ingest document
- `GET /api/v1/search?q=&tenant_id=&page=&size=` — search
- `DELETE /api/v1/documents/{id}` — soft delete
- `GET /api/v1/documents/{id}` — fetch by ID
- Flyway migration with documents table + GIN index
- Docker Compose: `api + postgres`
- Postman collection

### Risks

- PostgreSQL full-text lacks BM25 — relevance quality is inferior to Elasticsearch
- GIN index scan is single-threaded — full-text search not parallelizable at this stage
- No autocomplete support — typeahead requires a different approach
- Scale ceiling: ~1M documents, ~100 QPS before PostgreSQL becomes bottleneck

### When to Exit Phase 0

Exit when: (1) Search QPS consistently > 50, OR (2) Document count > 500K, OR (3) Users report poor relevance quality.

### Complexity: Low

---

## Phase 1: V1 — Elasticsearch Integration (Weeks 5–12) — 2–3 Engineers

### Goal

Introduce Elasticsearch as the search tier. Migrate search queries from PostgreSQL to Elasticsearch. Maintain PostgreSQL as the source of truth. Implement near-real-time indexing via CDC → Kafka → Elasticsearch consumer. Handle 10M documents at 1K QPS.

### Features Added

- Elasticsearch cluster integrated as search read model
- CDC pipeline: Debezium reads PostgreSQL WAL → Kafka → Indexing Consumer → Elasticsearch
- BM25 relevance ranking (Elasticsearch default)
- Faceted aggregations: `GET /api/v1/search?facets=category,brand`
- Autocomplete: `GET /api/v1/search/suggest?prefix={text}` (Elasticsearch Completion Suggester)
- Fuzzy search: `fuzziness: AUTO` with `prefix_length: 2`
- Field-level boosting (title field boosted 3x over body)
- Index alias management: `documents_v1` with `documents` alias
- Pagination: cursor-based (search_after) replacing offset-based
- GDPR document deletion: delete from both PostgreSQL and Elasticsearch (soft delete → hard delete after 30 days via scheduled job)
- Indexing pipeline monitoring: consumer lag metric, DLQ size alert

### Architecture

```
Client → Spring Boot API (2 instances, load-balanced)
              ↓           ↓
    Query Module     Ingestion Module
         ↓                ↓
  Elasticsearch    PostgreSQL (Primary)
  (3 data nodes,       ↓
   1 master,       Debezium (CDC)
   1 coordinating)     ↓
                   Kafka (document-events topic)
                       ↓
                Indexing Consumer
                (Spring Boot, 3 pods)
                       ↓
                Elasticsearch (write path)

Redis:
  - Autocomplete prefix cache (60s TTL)
  - Search result cache for repeated queries (30s TTL)
```

### Elasticsearch Index Mapping (V1)

```
Index: documents_v1 (alias: documents)
Shards: 5 primary, 1 replica
Fields:
  - id: keyword
  - tenant_id: keyword (used in all query filters)
  - title: text (analyzer: english, boost: 3.0)
  - body: text (analyzer: english)
  - category: keyword (for facets)
  - tags: keyword[] (for facets)
  - created_at: date
  - suggest: completion (for autocomplete)
```

### Infrastructure

- EKS or EC2: 2 API pods + 3 Indexing Consumer pods
- Elasticsearch: 3 data nodes (16 GB heap, 32 GB RAM each), 3 master nodes (8 GB)
- Kafka: MSK 2-broker cluster, 4 partitions for `document-events`
- Debezium: 1 instance (Kafka Connect cluster, 1 node)
- RDS PostgreSQL Multi-AZ (db.t3.medium)
- Redis ElastiCache (cache.r6g.large)
- Prometheus + Grafana: indexing lag, search p99, ES cluster health

### New Modules

- `indexing-module`: Kafka consumer, ES bulk write, retry on 429, DLQ for poison pills
- `query-module`: query parsing → ES DSL translation, result mapping, highlight extraction
- `schema-module`: index template management, alias lifecycle, reindex job runner
- `suggest-module`: autocomplete with Redis cache layer

### Deliverables

- Full search API per [04-api-design.md](04-api-design.md)
- Debezium connector configuration (stored as code, deployed via Kafka Connect REST API)
- ES index template (ILM policy, shard count, field mappings)
- Flyway migrations for all Phase 1 tables
- Grafana dashboard: indexing lag p99, search latency p50/p99, ES cluster health
- Alerting: indexing lag > 30s, DLQ size > 0, ES cluster status yellow/red
- Load test results: 10M documents indexed, 1K search QPS verified at < 100ms p99

### Architecture Evolution from MVP

- Elasticsearch introduced as search read model
- CDC pipeline via Debezium replaces synchronous dual-write
- Redis cache layer added for autocomplete and hot queries
- Cursor-based pagination (search_after) replaces offset-based
- Index alias for zero-downtime mapping migrations

### Risks

- Debezium WAL slot lag: if indexing consumer is slow, PostgreSQL cannot reclaim WAL disk space
- ES cluster yellow state: replica unassigned during node failure — reads continue from primary, but resilience reduced
- CDC schema evolution: adding a PostgreSQL column requires coordinated Debezium + ES mapping update
- Initial data migration: bootstrapping Elasticsearch from existing PostgreSQL data requires one-time full load job

### Complexity: Medium-High

---

## Phase 2: V2 — Scale and Multi-Tenancy (Months 4–9) — 4–6 Engineers

### Goal

Handle 100M documents, 10K QPS, full multi-tenancy with index isolation, KEDA-driven indexing autoscaling, vector search readiness, and analytics on search query patterns. Target SLA: < 20ms p50, < 100ms p99.

### Features Added

- Multi-tenant index strategy: index-per-tier (small tenants shared, large tenants dedicated)
- Elasticsearch field-level security: restrict tenant data access at the cluster level
- Semantic search foundation: dense vector field (`embedding`) added to index mapping; cosine similarity scoring for hybrid search
- Query analytics: all search queries logged to Kafka → ClickHouse → trending analysis
- Trending autocomplete: Redis sorted set of trending prefixes (last 1-hour window)
- Personalized autocomplete: per-user recent search history in Redis
- KEDA autoscaling for indexing consumer pods based on Kafka consumer lag
- Synonym management API: CRUD for synonym rules, applied at query time via synonym_graph filter
- Search result explain API: `GET /api/v1/search/{queryId}/explain` for debugging relevance
- Bulk document import API: async CSV/JSON ingestion via S3 upload → Kafka topic → indexing consumer
- Index lifecycle management (ILM): hot/warm/cold tiers for document age-based storage optimization
- GDPR compliance: document erasure across PostgreSQL + Elasticsearch within 24 hours of request

### Architecture

```
Client → API Gateway (Spring Boot, 5+ pods)
              ↓
    ┌─────────────────┐
    │  Query Module   │  ← Redis cache (hot queries, autocomplete)
    └────────┬────────┘
             ↓
  Elasticsearch Cluster (10 data nodes)
  ├── Hot tier: 4 nodes (NVMe SSD, 32 GB heap)
  ├── Warm tier: 4 nodes (SSD, 16 GB heap)
  └── Cold tier: 2 nodes (HDD, 8 GB heap)
  Coordinating: 3 nodes (no data, handle query fan-out)
  Master: 3 dedicated nodes

Write Path:
  PostgreSQL (Primary + 1 Read Replica)
       ↓
  Debezium CDC
       ↓
  Kafka (document-events: 32 partitions)
       ↓
  Indexing Consumer Pods (KEDA, 5–50 pods)
       ↓
  Elasticsearch (bulk index)

Analytics Path:
  Search Query Events → Kafka (search-events topic)
       ↓
  ClickHouse (trending queries, CTR metrics)
       ↓
  Redis sorted set (trending_autocomplete)
```

### Infrastructure Evolution

- Elasticsearch: 10 data nodes in hot/warm/cold tiers + 3 coordinating + 3 master
- Kafka MSK: 4–6 brokers, 32 partitions for `document-events`
- KEDA: Kafka consumer lag → indexing pod count (5–50 pods)
- ClickHouse: 3 nodes for search analytics (query volume, CTR, trending)
- Redis Cluster: 3 shards for cache + autocomplete sorted sets
- S3: bulk import staging, cold index snapshots, GDPR erasure audit log
- OpenTelemetry → Jaeger: distributed tracing across API → ES → Kafka

### Multi-Tenant Index Strategy

| Tenant Type | Strategy | Shard Count | Isolation Level |
|---|---|---|---|
| Small (< 100K docs) | Shared index (`documents_shared`) | 5 (shared with all small tenants) | Tenant_id filter only |
| Medium (100K–10M docs) | Tier index (`documents_tier_b`) | 5 (shared with same tier) | Tenant_id filter + field security |
| Large (> 10M docs) | Dedicated index (`documents_{tenantId}`) | 10+ | Full index isolation |

### New Modules / Services

- `analytics-module`: search event logging, trending computation pipeline
- `synonym-service`: extracted for independent deployment cadence (synonym changes don't require app restart)
- `vector-module`: document embedding generation (hook to embedding service), dense vector indexing
- `bulk-import-module`: S3-triggered batch indexing with progress tracking

### Architecture Tradeoffs at V2

| Decision | Why | Tradeoff |
|---|---|---|
| Hot/warm/cold ILM | Reduces storage cost by 60% | Warm/cold queries are slower; query routing must account for tier |
| KEDA-driven indexing scale | Handles burst ingestion (product catalog updates, marketing events) | Requires Kafka metrics exposure to Prometheus + KEDA operator |
| ClickHouse for search analytics | 10K search queries/sec → ClickHouse aggregation is trivial | Additional operational component; Kafka → ClickHouse pipeline |
| Index-per-tier multi-tenancy | Balance isolation vs shard overhead | Noisy neighbor within shared tier; mitigation via shard-level routing |

### Team Scaling

- 2 Backend engineers: query module, ranking, synonym management
- 1 Backend engineer: multi-tenancy, GDPR compliance, bulk import
- 1 Platform/SRE engineer: Elasticsearch cluster, KEDA, Kafka operations
- 1 Data engineer: ClickHouse analytics pipeline, trending computation

### Complexity: High

---

## Phase 3: V3 — Enterprise Scale (Months 10–18) — 8–12 Engineers

### Goal

Multi-region search with cross-region replication, Learning-to-Rank for relevance quality, vector search for semantic similarity, and self-service tenant onboarding. Target: 1B+ documents, 50K QPS globally.

### Features Added

- Multi-region Elasticsearch: primary cluster (US) + read-only replica cluster (EU) via Cross-Cluster Replication (CCR)
- Global search routing: Route 53 + nearest-region API routing; EU clients query EU cluster
- Learning to Rank (LTR): LightGBM model trained on click data, served via ES LTR plugin
- Semantic (vector) search: document embeddings via fine-tuned sentence-transformers; dense vector stored in ES `knn_vector` field
- Hybrid search: BM25 score × 0.6 + cosine similarity score × 0.4 (tunable per use case)
- Spelling correction: query term correction via ES `term_suggest` before executing main query
- Multi-language support: per-language analyzers (French, German, Spanish, Japanese via Kuromoji)
- Real-time index health dashboard: per-tenant indexing lag, shard health, search error rate
- Self-service tenant onboarding: provisioning API creates index, sets ILM policy, configures Kafka consumer routing
- Disaster recovery: ES snapshot to S3 every 6 hours; restore tested monthly; RPO < 6 hours, RTO < 30 minutes

### Architecture

```
Global Traffic
    ↓
Route 53 (latency-based routing)
    ↓
US Cluster              EU Cluster
API Gateway (10+ pods)  API Gateway (5+ pods)
    ↓                       ↓
ES Cluster US           ES Cluster EU
(8 data + 4 coord)      (4 data, read replica via CCR)
    ↓
Kafka (US Primary) → MirrorMaker2 → Kafka (EU)
    ↓
ClickHouse (US)     ClickHouse (EU, replica)

Shared Global:
  S3 (snapshots, GDPR audit, bulk import)
  Redis (US + EU, autocomplete cache, LTR feature cache)
  PostgreSQL (US Primary + EU read replica)
```

### Infrastructure Evolution

- Elasticsearch Cross-Cluster Replication (CCR) from US primary to EU follower clusters
- AWS MSK replication via MirrorMaker2 (US → EU `document-events` topic)
- GPU-enabled embedding service (sentence-transformers inference): separate deployment from main API
- Kubernetes Karpenter: node-level autoscaling for indexing worker spikes
- Istio service mesh: mTLS between all services, traffic policy per tenant priority

### Services Extracted from Monolith by V3

| Service | Reason for Extraction |
|---|---|
| `query-service` | Independent scaling; future: Go rewrite for lower p99 at extreme QPS |
| `indexing-service` | Different deployment cadence; high pod count via KEDA |
| `embedding-service` | GPU dependency; separate resource class from CPU services |
| `schema-service` | Risky to co-deploy with hot query path; change management |
| `analytics-service` | ClickHouse pipeline + trending computation; independent team |

### Team Scaling

- 2 Backend teams: Search Quality (ranking, LTR, synonyms) + Platform (indexing pipeline, multi-region)
- 1 ML team: LTR model training, embedding fine-tuning, A/B relevance testing
- 1 SRE team: ES cluster management, cross-region replication, disaster recovery
- Total: 10–12 engineers

### Complexity: Very High

---

## Implementation Principles Across All Phases

| Principle | Application |
|---|---|
| PostgreSQL is source of truth | Never trust Elasticsearch for data integrity; always re-derive from PostgreSQL on doubt |
| Alias-based index management | Never query a concrete index name; always use aliases. Enables zero-downtime reindex and schema evolution |
| DLQ from day 1 | Every indexing pipeline stage has a dead-letter queue; DLQ size is alerted on; DLQ replay is documented |
| Observability from MVP | Indexing lag metric emitted from day 1; search p99 tracked before Elasticsearch introduced |
| Idempotent indexers | Kafka consumer reprocessing must produce the same ES document. ES `_id` = PostgreSQL document ID — upserts are safe |
| Fail loudly on consistency drift | Periodic reconciliation job compares PostgreSQL doc count vs ES doc count; alert on > 0.1% drift |
| Schema migration first | ES index template changes checked in before deployment; alias switch is the last step, not the first |

---

## Interview Discussion Points

- **What do you build first?** PostgreSQL full-text search + the API contract. Start with `tsvector` — it proves the domain model is correct without committing to Elasticsearch operational complexity. Migrate to Elasticsearch when relevance quality or scale demands it.
- **When do you add Elasticsearch?** When any of these are true: (1) PostgreSQL full-text query p99 > 500ms, (2) need autocomplete or faceted aggregations, (3) relevance complaints from users. Before that, the operational cost of running an ES cluster outweighs the benefit.
- **How do you bootstrap Elasticsearch from existing PostgreSQL data?** One-time full scan of PostgreSQL → Kafka (large batch publish) → indexing consumer → ES. Use `refresh_interval: -1` during bootstrap for maximum throughput. After bootstrap complete, enable CDC to catch incremental changes.
- **What's the biggest operational risk in V1?** Debezium WAL slot lag. If the indexing consumer falls behind, PostgreSQL cannot reclaim WAL segments. WAL disk usage grows unbounded. Alert when replication slot lag > 10K WAL segments. Remediation: increase consumer throughput or drop and recreate the slot (accepting a gap, filled by full reindex).
- **At what scale does this architecture break?** Single-region Elasticsearch breaks at ~500K sustained QPS (coordinating node CPU + network saturation). Fix: add coordinating nodes, split hot indices, implement request caching aggressively. Multi-region breaks when CCR replication lag causes EU clients to search stale data during US write spikes — acceptable for eventual consistency workloads; not acceptable for user-generated content that must be immediately searchable by the same user.
