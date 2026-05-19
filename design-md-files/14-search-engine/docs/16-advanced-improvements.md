# 16 — Advanced Improvements & Architecture Critique: Mini Search Engine

---

## Objective

Honestly critique the designed architecture, identify weaknesses, scaling limits, tech debt risks, and what a FAANG interviewer would challenge. Propose concrete advanced improvements for the next evolution.

---

## Architecture Self-Critique

### Weakness 1: CDC Pipeline Is a Single Point of Failure for Indexing

**The problem**: The entire indexing pipeline depends on one Debezium connector instance reading from one PostgreSQL WAL slot. If Debezium crashes and the WAL slot falls behind by > 10GB (default max WAL size), PostgreSQL may terminate the replication slot. When the connector recovers, it starts from a blank state — requiring a full reindex of all documents.

**What a FAANG interviewer will ask**: "What happens if Debezium is down for 4 hours?"

**Production consequence**: At 1,000 docs/sec write rate, 4 hours of Debezium downtime = 14.4M documents not indexed. The WAL slot accumulates 4 hours of changes. If WAL retention is configured to 6 hours (as it should be), recovery is possible. If not, full reindex required (100M docs × ~2s/1K docs = ~55 hours at 50K docs/sec bulk speed — unacceptable).

**Better approach**:
- Configure PostgreSQL `max_slot_wal_keep_size` to 100 GB minimum. Alert when WAL slot lag > 20 GB.
- Run Debezium in Kafka Connect distributed mode (3-node cluster): if one Debezium instance fails, another takes over within 30 seconds — no WAL gap
- Dual-write fallback: application writes to PostgreSQL AND publishes to Kafka directly for critical document types. Debezium is the primary but not the only source
- Implement a daily consistency check: compare PostgreSQL document count with Elasticsearch document count per tenant; discrepancies trigger reconciliation

---

### Weakness 2: Elasticsearch Shard Count Is Fixed at Index Creation

**The problem**: The number of primary shards in an Elasticsearch index cannot be changed after creation (only replicas can be added/removed). If you start with 5 shards and traffic grows 10x, you can't add more shards to the existing index — you must reindex to a new index with more shards.

**Why this is a design-time decision with long-term consequences**: Shard over-provisioning wastes resources (each shard has fixed JVM overhead, ~50 MB minimum). Under-provisioning creates hot shards at scale.

**What a FAANG interviewer will challenge**: "You started with 5 shards at 10M documents. You're now at 500M documents. What do you do?"

**Better approach**:
- Use Elasticsearch's `_split` API: doubles shard count (5 → 10 → 20) without full reindex. Requires original shard count to be a multiple of the target
- Design shard count as `target_document_count / 30GB_per_shard`. For 100M docs at 5KB avg = 500 GB raw; ES amplification 2.5x = 1.25 TB total; at 30GB per shard (leaving margin) = 42 shards minimum. Start with 50 shards for headroom
- The URL Shortener lesson applies: **think about the 2-year data volume, not the day-1 volume**, when choosing shard count
- Use ILM (Index Lifecycle Management): time-based indices (monthly rollover). Each new index starts fresh with recalculated shard count based on current growth rate

---

### Weakness 3: Relevance Quality Degrades Without Continuous Tuning

**The problem**: BM25 relevance is a general-purpose ranking function. It has no knowledge of: (1) which results users actually click on, (2) which products are currently trending, (3) domain-specific relevance signals (brand affinity, price range preference). Deploying BM25 and never tuning it means relevance quality degrades relative to competitors over time.

**Production consequence**: Users start using filters instead of search (implicit signal that search is failing them). Conversion rate from search decreases. This is a slow, invisible degradation — no errors, no alerts, just worse business outcomes.

**What a FAANG interviewer will ask**: "How do you know your search is getting better over time?"

**Better approach**:
- Implement search result click tracking from day 1 (not V3): `POST /api/v1/search/{queryId}/click` with document ID
- Track zero-result queries (queries with 0 hits) — this is the highest-signal quality metric
- A/B test ranking changes using interleaved results (Team A ranking vs Team B ranking served to same user, clicks determine winner)
- Define an offline evaluation dataset: 500 human-labeled (query, document, relevance_score) tuples. Run NDCG@10 metric against this dataset on every ranking change
- Monthly relevance review: examine the top 100 queries by volume, manually evaluate result quality, identify systematic failures

---

### Weakness 4: Autocomplete Has No Freshness Awareness

**The problem**: Elasticsearch Completion Suggester uses pre-indexed suggestion weights. If a new product "iPhone 16 Pro" is added to the catalog, it won't appear in autocomplete until the weight is updated in the Completion Suggester field — which requires a partial document update, not just a body field update.

**Deeper problem**: Completion Suggester is stored in a separate in-memory FST (Finite State Transducer) per shard. Updates to the `suggest` field require reloading the FST on all shards — a non-trivial cluster operation.

**Production consequence**: New products, trending search terms, and breaking news don't appear in autocomplete for seconds to minutes after they should. For e-commerce flash sales or breaking news search, this is a real UX failure.

**Better approach**:
- Separate the autocomplete backend from the search backend: use Redis sorted sets as the primary autocomplete store (millisecond update latency, O(log N) prefix lookup via `ZRANGEBYLEX`)
- Hybrid autocomplete: Redis sorted set for top-1000 suggestions (updated in real-time), Elasticsearch Completion Suggester for long-tail suggestions (updated periodically)
- Trending injection: real-time trending terms from ClickHouse inserted into Redis sorted set every 60 seconds with boosted weight
- This separation also solves the hot shard problem for autocomplete — Redis is horizontally scalable independent of Elasticsearch

---

### Weakness 5: No Rate Limiting at the Elasticsearch Query Level

**The problem**: The API gateway rate limits at the HTTP request level (per client, per endpoint). But a single search query can generate wildly different cluster load: a simple keyword query → 5ms CPU per shard; a complex aggregation with nested buckets → 500ms CPU per shard. A client that sends 100 complex aggregation queries at once can saturate coordinating node memory and trigger GC pauses across the cluster.

**What breaks in production**: A single tenant running a large analytics query (e.g., "aggregate all products by category × brand × price range across 10M documents") brings down search performance for all other tenants. This is a search equivalent of a noisy neighbor at the compute level.

**Better approach**:
- Query cost estimation: parse the ES DSL before forwarding, assign a cost score (keyword query = 1, filter = 1, aggregation bucket = 5, nested aggregation = 25). Reject if cost > threshold
- Per-tenant query budget: each tenant has a `max_query_cost_per_minute` quota enforced at the API gateway via Redis counter (`INCR tenant:${id}:query_cost`, TTL=60s)
- Async aggregations: heavy aggregation queries (cost > 100) are submitted asynchronously (`POST /api/v1/search/async`), executed in a dedicated low-priority thread pool, results fetched via polling or webhook. Prevents synchronous cluster saturation

---

## Advanced Improvements

### Improvement 1: Hybrid Search (BM25 + Dense Vector)

**Current**: BM25 keyword matching only. Query "laptop for video editing" matches documents containing those exact words. Misses: documents about "high-performance notebooks for content creators" — semantically relevant, lexically different.

**Advanced**: Hybrid search combines sparse retrieval (BM25) with dense retrieval (cosine similarity on embeddings):

1. Offline: generate document embeddings using a sentence-transformer model (`all-MiniLM-L6-v2` or fine-tuned domain model). Store as `dense_vector` field in Elasticsearch (`knn_vector` type, 384 dimensions)
2. At query time: encode query using the same model → k-NN search via ES `knn` query (HNSW index) → returns top-100 semantic matches
3. Combine: merge BM25 top-100 with kNN top-100 using Reciprocal Rank Fusion (RRF) → deduplicate → return top-10

**Result**: "laptop for video editing" now retrieves "high-performance notebooks for content creators" even without lexical overlap.

**Tradeoff**: Embedding generation adds 20–50ms per query (inference latency). Mitigate with: query embedding cache (same query → same vector), async embedding generation for indexing, GPU-accelerated inference service. Dense vectors add ~30% storage overhead to the ES index. Only deploy when relevance quality from BM25 alone is demonstrably insufficient.

---

### Improvement 2: Search-as-You-Type with Sub-20ms Latency

**Current**: Autocomplete uses Elasticsearch Completion Suggester (FST). Latency: 15–30ms. Problem: at 20K QPS, even 30ms queries consume significant cluster resources.

**Advanced**: Dedicated search-as-you-type tier using Redis:

1. At index time: for each document, extract all prefix substrings of significant fields (title, category). Store in Redis sorted set: `ZADD autocomplete:{tenant_id} 0 "lap"`, `ZADD autocomplete:{tenant_id} 0 "lapt"`, `ZADD autocomplete:{tenant_id} 0 "lapto"`, with score = document weight (popularity, CTR)
2. At query time: `ZRANGEBYLEX autocomplete:{tenant_id} [lapto [lapto\xff LIMIT 0 10` → O(log N + K) where K=10
3. Return top-10 with < 3ms p99 (Redis is memory-resident, no ES cluster involved)

**Supplement with Elasticsearch** for long-tail queries not in Redis prefix index (Redis stores top-10K documents; ES handles the rest).

**Result**: Sub-5ms autocomplete at any QPS (Redis cluster horizontal scaling). Elasticsearch is reserved for full-text search only.

---

### Improvement 3: Proactive Reindex with Dual-Write During Mapping Migration

**Current**: Reindex is a batch operation with a transition period. During reindex, new writes go to the old index; after alias switch, writers are updated.

**Advanced**: Zero-gap dual-write reindex:

1. Before reindex: enable dual-write in the application — every new document is written to BOTH `documents_v1` and `documents_v2` simultaneously
2. Start `_reindex` from v1 to v2 (historical backfill)
3. After reindex: query-time validation (run the same query against both indices, compare result overlap; target > 95% overlap)
4. Switch alias: `documents` → `documents_v2` (atomic)
5. Disable dual-write to `documents_v1`
6. After 24 hours of validation: delete `documents_v1`

**Why this eliminates the gap**: At no point are writes going only to the old index. Documents indexed after the reindex starts are guaranteed to be in v2 before the alias switch. The historical backfill catches all documents that existed before the migration started.

**Tradeoff**: Dual-write increases indexing throughput requirement by 2x during migration. Pre-scale the indexing consumer pods. Schedule reindex during off-peak hours.

---

### Improvement 4: Query Result Caching with Intelligent Cache Warming

**Current**: Redis caches exact-match search results with a 30-second TTL. Cache hit rate: ~30% (many unique queries).

**Advanced**: Multi-level caching with query normalization:

1. **Query normalization**: strip whitespace, lowercase, sort multi-word terms (canonical form). `" Laptop  Gaming "` and `"gaming laptop"` hit the same cache key
2. **Fuzzy cache matching**: cache at the n-gram level — a cache entry for "laptop" is reused for "laptop gaming" with result filtering on top
3. **Cache warming**: after each new document indexed that affects a cached query result, invalidate that cache entry proactively via ES `percolator` queries (pre-stored queries that match incoming documents)
4. **Popularity-weighted TTL**: high-traffic queries (> 100 hits/min) get 5-minute TTL. Low-traffic queries: 30 seconds. Prevents cache pollution from long-tail queries

**Result**: Cache hit rate increases from 30% to 60–70% at steady state. Reduces Elasticsearch QPS by 60%, which directly reduces cluster sizing requirements and cost.

---

### Improvement 5: Incremental Segment Merge Scheduling

**Current**: Elasticsearch merges Lucene segments automatically in the background (merge scheduler). At high indexing throughput, large merge operations can consume 80% of disk I/O, causing search query latency spikes.

**Advanced**: Merge scheduling control:

1. Configure `index.merge.scheduler.max_thread_count` to limit concurrent merge threads (1 for spinning disks, CPU-count/2 for NVMe SSDs)
2. Use ILM to defer heavy segment merges to off-peak hours: after rollover, warm-tier index runs `forcemerge` to 1 segment per shard during the 2 AM maintenance window
3. Monitor `merges.total_time_in_millis` per index. Alert if merge time > 10% of total I/O time — indicates under-provisioned disk or too-frequent small writes
4. For hot indices: keep `refresh_interval` at 1s, accept many small segments. For warm/cold: `forcemerge` to minimize segments and maximize query cache efficiency

---

## What a FAANG Interviewer Would Challenge

| Challenge | Strong Response |
|---|---|
| "Elasticsearch is not ACID. What happens if a write succeeds in PostgreSQL but the CDC event is lost?" | "Debezium provides at-least-once delivery via Kafka offset commits. If the consumer crashes before committing, the event is re-delivered. Duplicate indexing is idempotent (ES upsert by `_id`). We run daily count reconciliation between PostgreSQL and Elasticsearch; discrepancies > 0.1% trigger automated re-sync." |
| "Your index has 5 shards. At 1B documents, what do you do?" | "Use ES `_split` API to double shard count (5 → 10 → 20) without reindex. For time-based data, use ILM with monthly rollover — each new monthly index is provisioned with the correct shard count based on current growth rate. Never change shard count of an existing index." |
| "A complex nested aggregation query crashes your cluster. How do you prevent this?" | "Query cost estimation at the API gateway: count aggregation nesting depth, bucket count, and doc count. Reject queries exceeding a cost threshold. For legitimate heavy analytics, route to an async job API — submit query, get a job ID, poll for results. Async queries run on dedicated coordinating nodes isolated from the synchronous search path." |
| "BM25 scores are not comparable across queries. How do you handle this in multi-index search?" | "BM25 is IDF-dependent: scores are calibrated to the document corpus of each index. Cross-index score comparison is meaningless. Solution: score normalization (normalize each index's scores to [0,1] range before merging). Better: use Reciprocal Rank Fusion (RRF) — combine ranked lists by rank position, not by score. RRF is robust to score magnitude differences and is now available natively in Elasticsearch 8.8+." |
| "Your CDC pipeline has eventual consistency. How would a user who just added a product know it's searchable?" | "Two strategies: (1) Optimistic: after submission, show the user their own product immediately via a direct PostgreSQL read (bypass ES). After 5 seconds, switch to ES. (2) Synchronous guarantee for the submitting user: use Elasticsearch `refresh=wait_for` parameter on the indexing write — wait for the next refresh cycle before returning. Use sparingly (adds ~1s latency), only for UGC where the author must immediately see their submission." |
| "How do you handle stopwords for search vs autocomplete differently?" | "For full-text search: remove stopwords for most queries to improve recall. Preserve for phrase queries ('to be or not to be'). For autocomplete: never remove stopwords — user is mid-type, 'the' prefix must surface 'the Beatles', not return nothing. Use `stop` token filter in the main `english` analyzer; use `whitespace` analyzer for the `suggest` completion field." |

---

## Tech Debt Risks

| Risk | When It Hurts | Prevention |
|---|---|---|
| Fixed shard count on original index | At 10x document growth, shard size exceeds 50GB — query performance degrades | ILM rollover from day 1; provision shard count for 2-year projected volume |
| Debezium WAL slot growing unbounded | When indexing pipeline has sustained downtime > 2 hours | Alert on WAL slot lag > 20GB; configure `max_slot_wal_keep_size` |
| Synonym file managed outside version control | When synonyms diverge between environments | Synonyms stored in a managed config file in git, deployed via CI/CD |
| Search query logs growing in ClickHouse without TTL | After 6 months, ClickHouse disk fills | ILM on ClickHouse tables: compress after 30 days, delete after 2 years |
| Elasticsearch mapping explosion (too many dynamic fields) | When documents have user-defined keys — ES auto-maps each as a field, mapping bloats | Set `dynamic: strict` or `dynamic: false` on mappings; require explicit field registration |
| Inconsistent tokenization between index and query time | When index analyzer and search analyzer differ — query terms don't match indexed terms | Always use `search_analyzer` equal to `analyzer` unless deliberately asymmetric |

---

## Operational Burden Assessment

| Component | Burden | Mitigation |
|---|---|---|
| Elasticsearch cluster | Very High (shard management, JVM heap tuning, merge scheduling, snapshot management, cluster health monitoring) | Use Elastic Cloud or AWS OpenSearch managed service; self-host only for cost at > 20 data nodes |
| Debezium / Kafka Connect | High (connector management, WAL slot lag monitoring, schema evolution coordination) | Use Confluent Cloud Managed Connect for Kafka Connect; monitor WAL slot via PostgreSQL `pg_replication_slots` view |
| Kafka cluster | High (broker management, partition rebalancing, consumer lag monitoring) | MSK (fully managed); alert on consumer group lag > 30 seconds |
| ClickHouse (search analytics) | Medium (insert optimization, query tuning, backup) | ClickHouse Cloud or self-host with `MergeTree` + TTL policies configured from day 1 |
| Redis (autocomplete + cache) | Low-Medium | ElastiCache Cluster; monitor eviction rate and memory usage |
| Embedding service (V3) | Medium (GPU instance management, model versioning, inference latency) | Use SageMaker endpoint or a dedicated GPU node group in EKS with HPA on inference queue depth |

**Summary**: Elasticsearch is the dominant operational complexity driver. For teams < 5 engineers, managed Elastic Cloud eliminates most of the burden. For > 20 data nodes, self-hosting with a dedicated SRE becomes cost-effective. Debezium WAL slot management is the most common production incident source — invest in monitoring and runbooks before V1 goes to production.

---

## Final Architecture Evaluation Score

| Dimension | Score | Notes |
|---|---|---|
| Correctness | 9/10 | Dual-store model is sound; CDC + idempotent indexing correctly handles at-least-once |
| Scalability | 9/10 | ES horizontal sharding + coordinating nodes + KEDA scales to 1B docs; multi-region in V3 |
| Relevance Quality | 7/10 | BM25 + synonym support is solid for V1; LTR + vector search needed for competitive quality |
| Reliability | 8/10 | CDC single-point-of-failure risk mitigated by distributed Kafka Connect; residual WAL slot risk |
| Security | 9/10 | Field-level security, tenant isolation, GDPR deletion pipeline — comprehensive |
| Observability | 9/10 | Indexing lag, search p99, cluster health, DLQ size — all covered |
| Operational Simplicity | 6/10 | ES + Debezium + Kafka + ClickHouse = significant operational footprint |
| Interview Readiness | 10/10 | Inverted index, BM25, shard design, CDC failure modes, multi-tenancy tradeoffs — deep coverage |

**Overall**: This is a well-designed production search platform for a growth-stage product (1M–100M documents). For a startup, starting with PostgreSQL full-text and migrating to Elasticsearch when scale demands it is the right call — the operational cost of running Elasticsearch at small scale is rarely justified. For FAANG-scale (1B+ documents, 50K+ QPS globally), the multi-region CCR architecture with LTR + hybrid search is the correct target state.
