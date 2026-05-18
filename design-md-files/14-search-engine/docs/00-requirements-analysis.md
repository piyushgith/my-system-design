# 00 — Requirements Analysis: Mini Search Engine (Elasticsearch-Backed)

## Objective

Define functional and non-functional requirements, establish scale parameters, and perform back-of-the-envelope capacity planning for a production-grade search platform supporting full-text indexing and querying. This document drives every architectural decision downstream.

---

## 1. Functional Requirements

### Core Capabilities

| # | Requirement | Priority |
|---|-------------|----------|
| FR-01 | Index documents (text fields, numeric, dates, nested objects) | P0 |
| FR-02 | Full-text keyword search with relevance ranking | P0 |
| FR-03 | Boolean queries (AND, OR, NOT, phrase matching) | P0 |
| FR-04 | Field-level filtering (exact match, range, term) | P0 |
| FR-05 | Faceted search and aggregations (bucket aggregations, metric aggregations) | P1 |
| FR-06 | Autocomplete / prefix suggestions | P1 |
| FR-07 | Fuzzy search (typo tolerance, edit distance) | P1 |
| FR-08 | Near-real-time indexing (changes visible within 1–5 seconds) | P0 |
| FR-09 | Bulk indexing (batch document ingestion) | P0 |
| FR-10 | Schema (mapping) management — create, update, version | P1 |
| FR-11 | Document delete and partial update propagation | P0 |
| FR-12 | Multi-tenant index isolation | P1 |
| FR-13 | Highlight matching terms in search results | P2 |
| FR-14 | Search query explain (debug scoring) | P2 |
| FR-15 | Synonym expansion at query time | P2 |

### Non-Core (V2+)

- Semantic / vector search (dense retrieval)
- Learning-to-Rank (ML reranking)
- Personalized results based on user behavior
- Spelling correction and query rewriting

---

## 2. Non-Functional Requirements

### Performance

| Metric | Target | Rationale |
|--------|--------|-----------|
| Search p50 latency | < 20ms | End-user interactive search |
| Search p99 latency | < 100ms | SLA for product search |
| Indexing throughput | 1,000 docs/sec sustained | E-commerce catalog updates |
| Indexing lag (NRT) | < 5 seconds | Near-real-time visibility |
| Autocomplete latency | < 30ms p99 | Typeahead UX requirement |
| Bulk reindex speed | > 50,000 docs/sec | Full re-index must complete in hours |

### Availability and Reliability

| Metric | Target |
|--------|--------|
| Search availability | 99.99% (< 1 hour downtime/year) |
| Indexing pipeline availability | 99.9% |
| Data durability | 99.9999% (6 nines) — no document loss |
| RPO | < 5 minutes |
| RTO | < 30 minutes |

### Scalability

- Horizontal scaling to 1B+ documents
- Linear throughput scale with additional Elasticsearch data nodes
- Support 10x traffic spikes (sale events, viral content)

### Consistency

- Eventual consistency between PostgreSQL (source of truth) and Elasticsearch (search index)
- Acceptable lag: < 5 seconds for normal operations; < 30 seconds under heavy load
- No silent data loss — all failures must be retried or dead-lettered

### Other

- Multi-tenant: strict data isolation per tenant
- GDPR-compliant: ability to delete individual user documents, audit search queries
- Zero-downtime schema evolution

---

## 3. Assumptions

- Documents are predominantly text-heavy (product descriptions, articles, user reviews)
- Average document size: 2–10 KB (JSON)
- Peak traffic coincides with business hours and promotional events
- PostgreSQL is the authoritative source; Elasticsearch is a derived, queryable projection
- Tenants share infrastructure but require data isolation (shared-nothing at index level)
- Search is read-dominant: 90% reads, 10% writes
- Clients are internal services (B2B) and end-user facing APIs

---

## 4. Constraints

- Elasticsearch version 8.x (with built-in security, RBAC, field-level security)
- Cannot use proprietary managed ES beyond AWS OpenSearch (cost constraint at mid-stage)
- Kafka as the event backbone — already in the organization's stack
- PostgreSQL as source of truth — schema changes must flow through migration tooling (Flyway)
- Budget cap: target commodity hardware (not GPU-heavy) for MVP and V1

---

## 5. Scale Estimation

### Data Volume

| Parameter | Value | Notes |
|-----------|-------|-------|
| Total documents | 100 million | Across all tenants |
| Average document size (raw JSON) | 5 KB | Text + metadata fields |
| Total raw data | 500 GB | 100M × 5 KB |
| ES index overhead (inverted index, term dict, stored fields, doc values) | ~2–3x raw | Typical ES amplification |
| Total ES storage | ~1.2 TB | 500 GB × 2.5 |
| Replica factor | 1 replica | 1 primary + 1 replica |
| Total ES disk | ~2.4 TB | 1.2 TB × 2 |
| Growth rate | 10M docs/month | ~50 GB/month raw |

### Request Volume

| Metric | Calculation | Result |
|--------|-------------|--------|
| Peak search QPS | Given | 10,000 QPS |
| Average search QPS | ~30% of peak | ~3,000 QPS |
| Indexing operations/sec | Given | 1,000 docs/sec |
| Autocomplete QPS | ~2x search (per keystroke) | ~20,000 QPS |
| Faceted aggregation QPS | ~20% of search | ~2,000 QPS |

### Elasticsearch Cluster Sizing

**Data Nodes:**

- ES rule of thumb: heap size ≤ 32 GB per node (compressed OOPs)
- Data per node: heap × 30 = ~960 GB (practical: ~500 GB usable with margin)
- For 2.4 TB total: minimum 5 data nodes (with headroom)
- Recommended: **8–10 data nodes** for production + headroom + rolling upgrades

**CPU:**
- At 10,000 search QPS, assume 2ms of CPU per query
- CPU time = 10,000 × 0.002 = 20 CPU-cores for search
- Add indexing overhead: ~10 CPU-cores
- Total: ~30 CPU-cores → 8 nodes × 8 vCPU = 64 vCPU (headroom included)

**Memory:**
- 8 data nodes × 32 GB heap = 256 GB heap total
- OS page cache: additional 32 GB per node for Lucene segment files

**Master Nodes:**
- Minimum 3 dedicated master nodes (odd number for quorum)
- 8–16 GB RAM, low storage requirement

**Coordinating Nodes:**
- 2–4 stateless nodes for query fan-out coordination
- Protects data nodes from heavy aggregation memory pressure

### Kafka Sizing

| Topic | Partitions | Rationale |
|-------|------------|-----------|
| document-events | 32 | 1,000 docs/sec × 5 KB = 5 MB/sec; 32 partitions @ ~156 KB/sec each |
| indexing-jobs | 16 | Work queue; processing-bound |
| indexing-results | 8 | Low volume status events |
| dlq-indexing | 4 | Dead letter; low volume |

### Network Bandwidth

- Indexing: 1,000 docs/sec × 5 KB = 5 MB/sec inbound to ES cluster
- Search responses: 10,000 QPS × 2 KB avg response = 20 MB/sec outbound
- Internal replication: 5 MB/sec × replica factor = 10 MB/sec internal
- **Peak: ~40 MB/sec aggregate** — well within 10 GbE network capacity

---

## 6. Read/Write Patterns

```
Reads (Search):
  - Keyword queries: 60%
  - Faceted/filtered queries: 25%
  - Autocomplete (prefix): 10%
  - Fuzzy/suggest: 5%

Writes (Indexing):
  - New document inserts: 50%
  - Field-level updates (partial): 30%
  - Document deletes: 10%
  - Bulk reindex operations: 10%
```

**Read/Write Ratio: ~10:1** (search-heavy workload)

---

## 7. Latency Budget

For a p99 < 100ms search SLA, the latency budget breakdown:

| Component | Budget |
|-----------|--------|
| API gateway / load balancer | 2ms |
| Auth / rate limiting | 5ms |
| Query service (parsing, DSL translation) | 5ms |
| Elasticsearch coordinating node routing | 3ms |
| ES shard-level query execution | 60ms |
| ES merging / scoring / top-K | 10ms |
| Response serialization + network | 10ms |
| **Total** | **95ms** |

Leaves 5ms buffer. This requires:
- All hot data in OS page cache (avoids disk I/O on fast path)
- Query cache hits for repeated queries
- No GC pauses during query window

---

## 8. Availability Targets

| Scenario | Target |
|----------|--------|
| Search API | 99.99% |
| Indexing pipeline | 99.9% |
| Autocomplete | 99.9% |
| Admin (schema management) | 99.5% |

Degradation tiers:
1. ES cluster degraded → fallback to cached results or PostgreSQL full-text (pg_trgm)
2. Kafka lag spikes → accept stale index, surface lag SLA breach to ops
3. Coordinating node failure → route directly to data nodes (temporary)

---

## 9. Interview Discussion Points

- **Why separate PostgreSQL and Elasticsearch?** PostgreSQL provides ACID guarantees, referential integrity, and is the authoritative source. Elasticsearch is optimized for full-text retrieval but is not ACID and does not support transactions. Separating them allows independent scaling and prevents search load from degrading transactional writes.
- **How accurate is the 2.5x storage amplification estimate?** It varies by field types. Text fields with analyzers, term vectors, and doc values amplify more (~3–4x). Numeric-only indices are closer to 1.5x. Always benchmark with your actual data shape.
- **Why 5ms latency for NRT?** Elasticsearch's `refresh_interval` defaults to 1 second (makes new documents visible). The 5 second NRT SLA accounts for Kafka lag, consumer processing, and ES refresh cycles. Reducing to 500ms refresh increases indexing I/O significantly.
- **How do you handle 10x traffic spikes?** Stateless query service scales horizontally via HPA. ES coordinating nodes buffer query fan-out. Redis cache absorbs repeated queries. Rate limiting at the API gateway prevents cascade.
