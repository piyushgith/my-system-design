# 14 — Interview Discussion Points: Mini Search Engine

---

## Objective

Prepare for FAANG-level system design interview discussions on search engine architecture — covering common interviewer follow-ups, tradeoff deep dives, scaling evolution questions, distributed systems challenges, and staff/principal engineer discussion points.

---

## Common Interviewer Questions

### Round 1: Fundamentals (Senior Engineer Level)

**Q: Why do you use Elasticsearch instead of PostgreSQL full-text search?**

> Strong answer: PostgreSQL supports full-text via `tsvector`/`GIN` and trigram similarity via `pg_trgm`. For small datasets (< 1M documents) this is sufficient. At 100M documents with 10K QPS: (1) Inverted index in Lucene is natively distributed — queries fan out to all shards in parallel. PostgreSQL has no native query fan-out. (2) BM25 relevance scoring is built into Elasticsearch — PostgreSQL `ts_rank` is primitive. (3) Faceted aggregations (`GROUP BY` equivalents) in Elasticsearch use pre-computed data structures (doc values); in PostgreSQL at 100M rows they require expensive sequential aggregations. (4) Horizontal scaling: Elasticsearch adds data nodes and rebalances shards automatically; PostgreSQL requires manual sharding (Citus). The tradeoff: Elasticsearch is not ACID. We keep PostgreSQL as the source of truth and use Elasticsearch as a derived, eventually consistent read model.

**Q: How does an inverted index work?**

> Strong answer: An inverted index maps each unique term to a posting list: the list of document IDs containing that term, plus term frequency and position data. At index time: document text is tokenized (split into terms), normalized (lowercased, stemmed), and each term is inserted into the index with the document ID. At query time: the query terms are looked up (O(1) hash lookup in the term dictionary), their posting lists are retrieved, and set operations (AND/OR/NOT) are performed via posting list intersection/union using skip pointers for efficiency. Lucene (Elasticsearch's engine) stores the inverted index in immutable segments on disk, merging segments in the background. Immutability makes caching trivial and enables concurrent reads without locking.

**Q: What is BM25 and how does it differ from TF-IDF?**

> Strong answer: Both are term-frequency × inverse-document-frequency relevance models. TF-IDF score grows linearly with term frequency — a document with a term 100 times scores 100x higher than one with it once, which is unrealistic. BM25 adds: (1) Term frequency saturation: score increases sublinearly with term frequency (controlled by parameter k1; default 1.2). After ~20 occurrences, additional occurrences add negligible score. (2) Field length normalization: shorter documents score higher for the same term frequency than longer documents (controlled by parameter b; default 0.75). A 50-word product description matching "laptop" is more relevant than a 5,000-word article that mentions "laptop" once in passing. BM25 is empirically better for information retrieval. Elasticsearch uses BM25 by default since version 5.0.

**Q: How do you ensure near-real-time indexing? What's the Elasticsearch refresh interval?**

> Strong answer: Elasticsearch `refresh_interval` defaults to 1 second. Refresh makes newly indexed documents visible to search by writing Lucene segments from the in-memory buffer to the OS page cache (not necessarily fsync'd to disk). This means documents are searchable within ~1 second of indexing — not immediately. Our NRT SLA is 5 seconds, accounting for: Kafka propagation (~500ms), consumer processing (~500ms), ES write (~500ms), refresh cycle (~1s). Total: ~2.5s p50, 5s p99. For faster NRT: reduce `refresh_interval` to 500ms at the cost of 2x more small segment merges (increased I/O). For bulk reindex: disable refresh entirely during import (`refresh_interval: -1`), then force refresh after completion. Crucial: Elasticsearch refresh is NOT flush — flush writes to disk with fsync. Translog provides durability between flushes (default flush every 5 minutes or 512MB of translog).

**Q: How do you handle schema changes (mapping updates) in Elasticsearch?**

> Strong answer: Elasticsearch field mappings are largely immutable after creation — you cannot change a field's type (e.g., `text` to `keyword`) or remove a field. Strategy: (1) Create a new index with the updated mapping (e.g., `products_v2`). (2) Create an alias pointing to the old index (`products` → `products_v1`). (3) Reindex from old to new: `POST _reindex` (async, or use scroll+bulk API for more control). (4) Switch alias atomically: remove `products` from `products_v1`, add `products` to `products_v2` in a single alias update call. (5) Delete old index after validation. The alias gives zero-downtime schema migration — clients always query the alias, never the concrete index. For additive field additions (new field to existing mapping), Elasticsearch supports dynamic mapping updates without reindex.

---

### Round 2: Scalability (Senior/Staff Engineer Level)

**Q: System is getting 10x the search traffic. What breaks first?**

> Coordinating nodes first. At 100K QPS, coordinating nodes are the fan-out bottleneck — each query is broadcast to all relevant shards, results collected and merged. Coordinating node CPU saturates. Fix: add more coordinating nodes (stateless, easy). Second: OS page cache miss rate. ES relies heavily on Lucene segment files being cached in OS memory. At 10x traffic, more shards receive concurrent queries, evicting hot segment pages. Fix: increase node memory or reduce heap to give more to OS page cache (ES heap should not exceed 50% of RAM). Third: Kafka indexing lag. At 10x write volume, indexing pipeline backs up. Fix: scale indexing consumer pods (KEDA) + increase `refresh_interval` to 5s during write spikes to reduce merge I/O.

**Q: How do you handle a hot index (all queries hitting one index shard)?**

> Hot shard problem occurs when shard routing is uneven — all documents for a popular tenant land on shard 2. Causes: poor routing key selection or tenant with disproportionately large document count. Solutions: (1) Use `_routing` API to spread a single tenant across multiple shards: hash(tenant_id + document_id). (2) For autocomplete queries (highest QPS, lowest fanout): use a dedicated autocomplete index separate from the main search index with lower shard count and aggressive caching. (3) Forcemerge hot shards to fewer, larger Lucene segments (reduces per-query overhead from many small segments). (4) Add coordinating nodes with per-shard request routing awareness — route autocomplete to the cached hot path.

**Q: How do you support autocomplete at 20,000 QPS with < 30ms p99?**

> Autocomplete is latency-critical and has different characteristics from full-text search: (1) Queries are prefix-only (no BM25 scoring needed), (2) Result set is small (top 5–10 suggestions), (3) Same prefix is queried thousands of times per second (high cache-ability). Architecture: Use Elasticsearch Completion Suggester (FST-based — Finite State Transducer — not inverted index). Stored in-memory on each shard. Prefix lookup in O(prefix_length) — extremely fast. Cache autocomplete results aggressively in Redis with TTL=60s per prefix. At 20K QPS, cache hit rate for popular prefixes is > 95% — only 1,000 QPS reaches Elasticsearch. For personalized autocomplete (user's history) — skip cache, go to ES with user-specific suggest context.

**Q: How do you handle a full reindex of 100M documents without downtime?**

> Alias-based zero-downtime reindex: (1) Start writes to both old (`products_v1`) and new (`products_v2`) indices simultaneously (dual write). This ensures no writes are lost during reindex. (2) Run `_reindex` from v1 to v2 with a scroll window (1,000 docs/batch). At 50K docs/sec reindex throughput, 100M docs takes ~33 minutes. (3) After reindex completes, switch alias atomically: `products` alias now points to `products_v2`. (4) Stop dual writes to `products_v1`. (5) After validation, delete `products_v1`. Key risk: documents updated during reindex may be overwritten by the stale copy from v1. Mitigation: after reindex, replay the Kafka event stream from the reindex start time to re-apply all updates on top of v2. This guarantees v2 is fully consistent at alias switch time.

---

### Round 3: Distributed Systems Deep Dive (Staff Engineer Level)

**Q: How do you maintain consistency between PostgreSQL and Elasticsearch?**

> This is eventual consistency by design. PostgreSQL is source of truth; Elasticsearch is a derived read model. The pipeline: PostgreSQL → Debezium CDC → Kafka → Indexing Consumer → Elasticsearch. Two guarantees: (1) No document is permanently lost: failures at any pipeline stage cause retry. DLQ captures poison-pill messages for manual inspection. (2) Eventually consistent: under normal load, lag is < 5 seconds. Under failure and recovery, lag may spike to minutes. We surface the indexing lag metric (`indexing_lag_seconds`) to operators. For reads that require absolute consistency (e.g., admin sees their own just-submitted document), we fall back to PostgreSQL full-text search (pg_trgm) for queries from the document submitter for 10 seconds after submission.

**Q: Elasticsearch says it's "nearly" distributed — explain its consistency model.**

> Elasticsearch uses a primary-replica shard model with optimistic concurrency control. Writes go to the primary shard first; primary replicates to replica shards synchronously before acknowledging to client (when `wait_for_active_shards=all`). This ensures reads from replicas see the written data. Consistency risks: (1) Split-brain: if master-eligible nodes can't form quorum, cluster goes red (no writes, reads from stale shards). Quorum = (master_eligible_nodes / 2) + 1. With 3 master-eligible nodes, quorum = 2. (2) Replica lag: replica shards can temporarily be behind the primary between writes and replication acknowledgment. Short window. (3) Sequence number conflicts: if a primary fails mid-write, the new primary uses sequence number fencing to reject stale replica writes. This is Elasticsearch's version of the fencing token pattern.

**Q: How do you implement multi-tenant search isolation?**

> Three models with different tradeoffs: (1) **Index-per-tenant**: complete isolation, independent mapping evolution, easy data deletion (delete the index). Downside: cluster shard overhead at thousands of tenants (each index has minimum 1 shard — limits cluster to ~20K indices before master overhead becomes significant). Best for < 100 large tenants. (2) **Index-per-tier**: small tenants share an index, large tenants get dedicated indices. Routing key = `tenant_id`. Downside: a large tenant's heavy queries impact small tenants on the shared index (noisy neighbor). Medium complexity. (3) **Single index with field-level tenant isolation**: every document has `tenant_id` field; queries always include `filter: { term: { tenant_id } }`. Simplest operationally but requires trust in application-layer filtering. We use Elasticsearch field-level security (document-level security in paid tier) as defense-in-depth. **Our choice**: index-per-tier for MVP (< 50 tenants), evaluated at V2.

**Q: How does the CDC pipeline work and what are its failure modes?**

> Change Data Capture (CDC) uses Debezium to tail the PostgreSQL Write-Ahead Log (WAL). Debezium reads WAL events (INSERT, UPDATE, DELETE) and publishes them as structured Kafka messages. Failure modes: (1) **Debezium connector crash**: recovers from last committed Kafka offset. No data lost — WAL position is checkpointed. Risk: WAL retention must be long enough (WAL slot retention) to cover connector downtime. (2) **WAL slot lag grows unbounded**: if Debezium is slow, PostgreSQL cannot reclaim WAL disk space. Monitor replication slot lag and alert. Drop + recreate slot if lag exceeds disk budget. (3) **Schema change**: if PostgreSQL schema changes without Debezium knowing, the connector fails on the first affected WAL event. Fix: use Flyway migrations with Debezium-aware schema history topic. (4) **Poison pill**: a malformed document causes the indexing consumer to fail. Fix: DLQ + offset skip for that message + alert. (5) **Elasticsearch bulk rejection (429 Too Many Requests)**: consumer applies exponential backoff, Kafka consumer lag grows. Fix: increase refresh_interval during spike to reduce ES write amplification.

---

### Round 4: Advanced Topics (Staff/Principal Level)

**Q: Design autocomplete that learns from user behavior (trending searches).**

> Base layer: Elasticsearch Completion Suggester with static weights (document frequency, curated boost). Personalization/trending layer: (1) Log all search queries to Kafka → ClickHouse time-series store. (2) Compute top-N queries per 1-hour window (trending). (3) Boost trending queries in Redis sorted set: `ZINCRBY trending_autocomplete:en {score} "laptops"`. (4) At autocomplete time: merge Elasticsearch static suggestions with Redis trending suggestions, deduplicate, re-rank by composite score (base_score × 0.7 + trending_score × 0.3). (5) Personalization (per-user): store recent successful searches in Redis per user_id (`LPUSH user_history:{userId} "laptops"`). Surface user history as top-ranked suggestions before generic ones. **Why this matters**: Google Trends and Amazon autocomplete are driven by exactly this pattern. The complexity is in the real-time score decay — trending scores must decay over time (use exponential decay in ClickHouse materialized view).

**Q: Explain fuzzy search and how you'd tune it.**

> Elasticsearch fuzzy search uses edit distance (Levenshtein distance) to match terms within N character edits. `fuzziness: AUTO` maps to: edit distance 0 for 1–2 char terms, 1 for 3–5 chars, 2 for 6+ chars. Implementation: Elasticsearch uses a DFA (Deterministic Finite Automaton) compiled from the query term to enumerate all terms within edit distance N — lookup in the term dictionary without scanning all documents. Performance concern: high fuzziness on short terms (edit distance 2 on 3-char term) generates huge automata — response time blows up. Tuning: (1) Limit `fuzziness` to 1 for terms < 6 characters. (2) Enable `prefix_length: 2` — first 2 characters must match exactly (reduces automata size dramatically). (3) Use fuzzy only as a fallback: if exact/analyzed query returns 0 results, retry with fuzziness. Avoid applying fuzziness unconditionally to all queries.

**Q: How would you implement Learning to Rank (LTR)?**

> LTR replaces or augments BM25 with an ML model that reranks top-K Elasticsearch results. Architecture: (1) Elasticsearch returns top-100 BM25 candidates (fast, coarse ranking). (2) Feature extraction: compute per (query, document) features: BM25 score, query-document field overlap, document freshness, user click-through rate on this document for similar queries, document popularity. (3) LTR model (LambdaMART or LightGBM) reranks top-100 by learned feature weights. (4) Return top-10 to client. Training data: implicit feedback from user clicks (clicked document = positive signal; skipped document at high rank = negative signal). NDCG (Normalized Discounted Cumulative Gain) as offline evaluation metric. **Elasticsearch LTR plugin** (open-source, by Wikimedia) implements this pipeline natively. Tradeoff: LTR adds 20–50ms to query latency (model inference on top-100 docs). Only deploy when click data is available (> 1M queries/day for stable training signal).

**Q: How do you handle stopwords and synonyms correctly?**

> Stopwords (the, a, is, in): removed at index time and query time by standard analyzer. Risk: removing stopwords breaks phrase matching for queries like "The Who" (band name) or "to be or not to be". Solution: use a custom analyzer that keeps stopwords in phrase queries (`match_phrase`) but removes them in keyword queries. Stopword removal is a deployment-specific decision — not a universal default. Synonyms: applied at query time (not index time) via synonym filter in the analyzer chain. Query-time synonym expansion: `laptop => laptop, notebook, portable computer`. Why query time only: if synonyms change, you don't need to reindex — just update the synonym file and reload the analyzer. Risk: synonym expansion increases query breadth → lower precision (more false positives). Tune with minimum_should_match and boost on exact match to preserve precision. Multi-word synonyms (`laptop computer => laptop`) require synonym_graph token filter (not standard synonym filter) to handle correctly.

---

## "What Would Break First?" Analysis

| Traffic Multiplier | First Bottleneck | Resolution |
|---|---|---|
| 2x | Coordinating node CPU | Add 2 more coordinating nodes |
| 5x | OS page cache hit rate drops | Add memory to data nodes (prioritize OS cache over heap) |
| 10x | Indexing pipeline Kafka lag | Scale indexing consumer pods (KEDA) + increase `refresh_interval` |
| 20x | ES bulk rejection (429) | Add data nodes; tune `thread_pool.write.queue_size` |
| 50x | Shard count × query fan-out CPU | Reduce shard count via forcemerge; split hot indices |
| 100x | Network bandwidth between coordinating/data nodes | Dedicated 10GbE network; compute/storage separated (coordinating vs data nodes) |
| 1000x | Lucene segment merge I/O | Tiered storage (hot/warm/cold); ML-based segment merge scheduling |

---

## Common Mistakes Candidates Make

| Mistake | What to Say Instead |
|---|---|
| "Just index everything into Elasticsearch" | Explain why PostgreSQL is source of truth; ES is a derived projection. ES is not ACID; data loss during split-brain is possible without a durable source. |
| "Use fuzzy matching by default for all queries" | Fuzzy matching degrades precision and increases latency. Use exact match first; fuzzy as fallback on zero results. |
| "Increase refresh_interval to improve indexing throughput" | Correct, but explain the tradeoff: higher interval → longer NRT lag. Balance with NRT SLA requirements. |
| "One index for all tenants" | Explain noisy neighbor problem and data isolation risks. Discuss index-per-tenant vs shared-index tradeoffs explicitly. |
| "Elasticsearch replaces PostgreSQL" | ES is not a database. No transactions, no foreign keys, no referential integrity. PostgreSQL + Elasticsearch dual-store is the correct pattern. |
| Not mentioning alias-based zero-downtime reindex | Every interviewer will ask about schema evolution. Alias pattern is the standard answer. |
| Ignoring bulk indexing throughput | At 50K docs/sec reindex, cluster I/O is the limit. Explain bulk batch size tuning (1,000–10,000 docs), refresh_interval=-1 during reindex, and throttle settings. |
| Not discussing coordinating nodes | Candidates describe data nodes + master nodes but forget coordinating nodes. At high QPS, coordinating node is the fan-out bottleneck. |

---

## Senior vs Staff Engineer Expectations

| Dimension | Senior Engineer | Staff Engineer |
|---|---|---|
| Core design | Designs PostgreSQL + ES dual-store; explains why | Explains CDC pipeline failure modes; discusses alias-based migration; reasons about consistency windows |
| Scalability | Knows horizontal scaling via shard count | Analyzes bottleneck progression (coordinating → page cache → shard fan-out); discusses cost implications of data node memory |
| Relevance tuning | Knows BM25 exists | Explains BM25 parameters (k1, b); discusses when custom scoring functions override BM25; can design LTR pipeline |
| Failure handling | Identifies Elasticsearch cluster red state | Explains WAL slot lag risk in CDC, DLQ strategy, split-brain quorum, alias switch failure atomicity |
| Trade-off depth | Picks fuzzy vs exact | Explains fuzziness DFA explosion; minimum_should_match tuning; synonym precision/recall tradeoff |
| Leadership | Answers questions well | Drives the conversation; identifies unstated requirements (GDPR document deletion, multi-language support); flags overengineering risks |
