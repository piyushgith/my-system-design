# 01 — High-Level Architecture: Mini Search Engine

## Objective

Establish the overall architectural shape: why Elasticsearch serves as the search tier alongside PostgreSQL as source of truth, how the indexing pipeline flows, how queries are served, and why the surrounding services are structured as a modular monolith migrating toward microservices.

---

## 1. Architecture Decision: Elasticsearch + PostgreSQL Dual-Store

### Why Elasticsearch as Search Tier

Elasticsearch (built on Lucene) is purpose-built for full-text search:

| Capability | PostgreSQL (pg_trgm / tsvector) | Elasticsearch |
|------------|--------------------------------|---------------|
| Inverted index | Limited (GIN/GiST) | Native, highly optimized |
| Relevance scoring (BM25) | No native ranking | Built-in, tunable |
| Horizontal sharding | Complex (Citus) | Native |
| Fuzzy search | Poor | Native (Levenshtein, n-grams) |
| Faceted aggregations | SQL GROUP BY (slow at scale) | Native aggregation framework |
| Autocomplete | Not suitable | Completion suggester, prefix queries |
| Query DSL | SQL only | Rich JSON query DSL |
| Near-real-time indexing | Yes | Yes (1s refresh cycle) |
| Distributed scaling | Manual | Automatic shard routing |

**Decision:** Use PostgreSQL as the authoritative source of truth (ACID transactions, foreign keys, audit, referential integrity). Elasticsearch is a **derived, eventually consistent projection** optimized for read path.

### Why Not Elasticsearch Alone

- Elasticsearch is not ACID — no multi-document transactions
- No referential integrity enforcement
- Harder to run complex relational joins
- Document mutations (update-by-query) are expensive and non-transactional
- Point-in-time consistency for billing, inventory, user records requires a relational store

---

## 2. Architecture Style Decision: Modular Monolith → Microservices Migration Path

### Selected: Modular Monolith (MVP and V1)

**Justification:**
- Team size at MVP: 4–6 engineers; microservices operational overhead is premature
- Kafka already decouples the indexing pipeline from the query path (natural async boundary)
- ES cluster is externally managed — biggest ops burden is already external
- Modular monolith with well-defined internal module boundaries allows extraction later

**Internal Modules:**
1. `indexing-module` — consumes Kafka, writes to ES
2. `query-module` — translates API queries to ES DSL, returns results
3. `schema-module` — manages ES index mappings, aliases, reindex jobs
4. `ingestion-module` — accepts documents, validates, persists to PostgreSQL, publishes events

**Migration Trigger to Microservices:**
- Indexing throughput bottleneck requires independent scaling
- Query module needs language-specific optimization (e.g., Go for high QPS)
- Schema management requires separate deployment cadence (risky to co-deploy with hot query path)

### When NOT to Use This Architecture

- If documents don't require full-text search → use PostgreSQL only
- If search is not latency-sensitive → avoid Elasticsearch complexity, use pg_trgm
- If team cannot operate Elasticsearch → use managed Typesense or Algolia

---

## 3. System Components

| Component | Technology | Role |
|-----------|------------|------|
| API Gateway | Kong / AWS ALB | Rate limiting, auth, routing |
| Ingestion Service | Spring Boot | Document validation, PostgreSQL write, Kafka publish |
| Query Service | Spring Boot | Search API, ES DSL translation, response shaping |
| Indexing Consumer | Spring Boot + Kafka | Consume events, index to Elasticsearch |
| Schema Manager | Spring Boot | Mapping management, alias management, reindex orchestration |
| PostgreSQL | PostgreSQL 15 | Source of truth, indexing_jobs, schema_versions |
| Elasticsearch | ES 8.x | Search index, inverted index storage |
| Kafka | Confluent Kafka | Event streaming, indexing pipeline |
| Redis | Redis 7 | Query result cache, autocomplete prefix cache |
| Zookeeper / KRaft | KRaft (Kafka 3.x) | Kafka metadata coordination |

---

## 4. High-Level Architecture Diagram

```mermaid
graph TB
    subgraph Clients
        C1[Web App]
        C2[Mobile App]
        C3[Internal Service]
    end

    subgraph API_Layer["API Layer"]
        AG[API Gateway<br/>Kong / ALB]
    end

    subgraph Application_Layer["Application Services (Modular Monolith)"]
        IS[Ingestion Service<br/>POST /documents]
        QS[Query Service<br/>POST /_search]
        SM[Schema Manager<br/>PUT /indices/:name/mappings]
    end

    subgraph Data_Pipeline["Indexing Pipeline"]
        KF[Kafka<br/>document-events topic]
        IC[Indexing Consumer<br/>Kafka → ES]
        CDC[CDC Connector<br/>Debezium]
    end

    subgraph Storage_Layer["Storage"]
        PG[(PostgreSQL<br/>Source of Truth)]
        ES[(Elasticsearch<br/>Search Index)]
        RD[(Redis<br/>Query Cache)]
    end

    subgraph ES_Cluster["Elasticsearch Cluster"]
        CN[Coordinating Nodes x2]
        MN[Master Nodes x3]
        DN1[Data Node 1]
        DN2[Data Node 2]
        DN3[Data Node 3]
        DN4[Data Node 4]
    end

    C1 --> AG
    C2 --> AG
    C3 --> AG

    AG --> IS
    AG --> QS
    AG --> SM

    IS --> PG
    IS --> KF

    CDC --> PG
    CDC --> KF

    KF --> IC
    IC --> ES

    QS --> RD
    QS --> CN
    CN --> DN1
    CN --> DN2
    CN --> DN3
    CN --> DN4

    SM --> PG
    SM --> ES

    ES_Cluster --> MN
    ES_Cluster --> CN
    ES_Cluster --> DN1
    ES_Cluster --> DN2
    ES_Cluster --> DN3
    ES_Cluster --> DN4
```

---

## 5. Indexing Pipeline Architecture

### Flow: Document Write to Index Visibility

```mermaid
sequenceDiagram
    participant Client
    participant IS as Ingestion Service
    participant PG as PostgreSQL
    participant KF as Kafka
    participant IC as Indexing Consumer
    participant ES as Elasticsearch

    Client->>IS: POST /documents (document JSON)
    IS->>IS: Validate schema, assign doc_id
    IS->>PG: INSERT INTO documents (with status=PENDING_INDEX)
    IS->>KF: Publish document-events (doc_id, payload, operation=CREATE)
    IS-->>Client: 202 Accepted (doc_id)

    Note over KF,IC: Async pipeline (< 5s NRT target)

    IC->>KF: Poll document-events
    IC->>IC: Transform: validate, normalize fields, apply analyzer config
    IC->>ES: PUT /index/_doc/:doc_id (with retry logic)
    ES-->>IC: 200 OK
    IC->>PG: UPDATE documents SET status=INDEXED, indexed_at=NOW()
    IC->>KF: Publish indexing-results (success)
```

### Two Indexing Modes

**Mode 1: Event-Driven (NRT)**
- Triggered per document change
- Latency: 1–5 seconds
- Use case: user-visible content changes (price, availability)

**Mode 2: Batch Reindex**
- Full scan of PostgreSQL → bulk index to ES
- Latency: minutes to hours (depends on corpus size)
- Use case: schema changes, analyzer tuning, index migration

---

## 6. Query Path Architecture

```mermaid
sequenceDiagram
    participant Client
    participant QS as Query Service
    participant RD as Redis Cache
    participant CN as ES Coordinating Node
    participant DN as ES Data Nodes (shards)

    Client->>QS: POST /_search {query DSL}
    QS->>QS: Parse request, validate, build ES query
    QS->>RD: GET cache key (query hash)
    alt Cache Hit
        RD-->>QS: Cached result
        QS-->>Client: 200 (cached)
    else Cache Miss
        QS->>CN: POST /index/_search (ES query)
        CN->>CN: Parse query, determine shard routing
        CN->>DN: Scatter: query to all relevant shards
        DN-->>CN: Shard-level top-K results
        CN->>CN: Merge, re-rank (global top-K)
        CN-->>QS: Search response
        QS->>RD: SET cache (TTL based on query type)
        QS-->>Client: 200 (search results)
    end
```

---

## 7. Why Not Alternative Architectures

### Alternative A: Full PostgreSQL with pg_trgm + tsvector

- **Pros:** No operational overhead, ACID, simple stack
- **Cons:** Cannot sustain 10k QPS at 100ms p99 with 100M docs; no BM25; no scalable facets
- **Verdict:** Valid for MVP (Phase 0) but hits a wall at ~5M documents

### Alternative B: Typesense or Meilisearch

- **Pros:** Operationally simpler, built-in typo tolerance, REST-native
- **Cons:** Less mature at 100M+ doc scale; limited aggregation capabilities; no field-level security
- **Verdict:** Good for startups with < 10M docs and small teams

### Alternative C: Solr

- **Pros:** Battle-tested, mature Lucene integration
- **Cons:** Less developer-friendly API; Zookeeper dependency; weaker cloud-native story
- **Verdict:** Elasticsearch has largely superseded Solr in new systems

### Alternative D: OpenSearch (AWS)

- **Pros:** ES-compatible API, managed service, lower ops overhead
- **Cons:** Lags ES feature releases by months; some APIs diverge
- **Verdict:** Prefer OpenSearch only if committed to AWS managed service; otherwise use ES

---

## 8. Startup vs FAANG Differences

| Concern | Startup | FAANG |
|---------|---------|-------|
| ES cluster management | Managed (Elastic Cloud, OpenSearch) | Self-hosted on K8s or bare metal |
| Shard count | 1–3 primary shards | 50–100+ shards across tenants |
| Replication | 1 replica | 2+ replicas, cross-AZ |
| Indexing pipeline | Simple Kafka consumer | Multi-stage with enrichment, dedup |
| Query DSL surface | Simple keyword + filter | Custom scoring scripts, percolation |
| Team | 1 infra engineer | Dedicated search platform team |
| Cost optimization | Minimize nodes | Hot-warm-cold tiering, ILM |

---

## 9. Overengineering Risks

- Adding vector search (dense retrieval) before relevance baseline is established
- Building custom ranking ML pipeline before measuring BM25 quality
- Multi-index per tenant before understanding tenant count and isolation needs
- Adding coordinating nodes before data nodes are the actual bottleneck
- Building a custom Query DSL translator when ES's native DSL is sufficient

---

## 10. Interview Discussion Points

- **Why eventual consistency between PG and ES is acceptable:** Search is a discovery UX. Users tolerate a 5-second delay in seeing new documents. Inventory and pricing need stronger consistency — those come from PostgreSQL directly, not ES.
- **What happens if Kafka consumer falls behind?** Indexing lag grows. Search results become stale. Alert on lag > 30s. ES does not serve errors — it serves stale results. This is a graceful degradation.
- **How do you prevent ES from becoming the bottleneck on the query path?** Redis caches popular queries. Request cache in ES caches repeated shard queries. Coordinating nodes prevent data nodes from being overwhelmed. HPA scales the query service layer.
