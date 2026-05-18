# 11 — Failure Scenarios: Mini Search Engine

## Objective

Analyze realistic production failure modes for the search platform, their blast radius, detection mechanisms, and step-by-step recovery runbooks. Cover ES cluster failures, data drift, indexing lag spikes, hot shards, and reindex during live traffic.

---

## 1. Failure Scenarios Summary

| # | Failure | Severity | Detection | MTTR Target |
|---|---------|----------|-----------|-------------|
| F-01 | ES cluster split-brain | P0 | Cluster health RED | 15 min |
| F-02 | ES data node failure (single node) | P1 | Shard unassigned alert | 5 min auto-recover |
| F-03 | Index shard corruption | P1 | Search errors + missing docs | 30 min |
| F-04 | Indexing lag spike (consumer falls behind) | P2 | Lag > 30 seconds | 10 min |
| F-05 | PostgreSQL → ES data drift | P2 | Reconciliation job | 1 hour |
| F-06 | Full reindex during production traffic | P2 | Latency spike alert | Planned maintenance |
| F-07 | Autocomplete query causing ES GC pressure | P1 | Heap usage > 85%, GC time > 5% | 5 min |
| F-08 | Query timeout cascade | P0 | Error rate > 5% | 5 min |
| F-09 | Hot shard (all traffic to one shard) | P1 | CPU skew > 3x | 30 min |
| F-10 | Kafka broker failure | P2 | Consumer lag spike | Auto-recover 10 min |
| F-11 | Redis cache eviction storm | P3 | Cache hit rate < 20% | Auto-recover 5 min |

---

## 2. F-01: Elasticsearch Cluster Split-Brain

### What Happens

Split-brain occurs when network partitioning divides the cluster into two groups, each believing it is the only active cluster and electing its own master. Both halves accept writes, leading to irreconcilable divergence.

### Prevention

ES 8.x uses a consensus-based election algorithm (Raft-like) that prevents split-brain natively when configured correctly:

```yaml
# elasticsearch.yml — master-eligible nodes
cluster.initial_master_nodes: ["master-1", "master-2", "master-3"]
# Minimum master-eligible nodes to elect a master:
# quorum = floor(total_master_nodes / 2) + 1 = 2 of 3
```

With 3 master nodes, quorum = 2. If network partition splits 1 vs 2 nodes, only the partition with 2 nodes can elect a master. The singleton partition becomes read-only (no master → no writes).

**Critical:** Never run even number of master-eligible nodes (4 master nodes = 2-2 split = deadlock).

### Detection

- `GET /_cluster/health` returns `status: red`
- Master election logs in ES logs: `found X master-eligible nodes but quorum Y not reached`
- Monitoring alert: cluster status RED for > 30 seconds

### Recovery Runbook

1. **Identify network partition:** `ping master-1` from each node; identify which nodes cannot communicate
2. **Restore network:** Fix network partition (usually AWS security group, VPC routing issue)
3. **ES auto-heals:** Once quorum is restored, ES elects master automatically
4. **Verify:** `GET /_cluster/health` → wait for `green`
5. **Check for divergence:** Compare shard allocation across nodes (`GET /_cat/shards?v`)
6. **If data divergence exists:** Identify diverged shards, take snapshot of one, restore from snapshot (accept data loss for diverged window — communicate to ops)
7. **Post-mortem:** Root cause network partition; implement multi-AZ placement with dedicated network path for ES inter-node communication

---

## 3. F-02: ES Data Node Failure (Single Node)

### What Happens

One data node goes down (hardware failure, OOM kill, pod eviction). All primary and replica shards on that node become unavailable. Replica shards on other nodes are promoted to primary. Cluster enters YELLOW (all primaries assigned, some replicas missing).

### Auto-Recovery

With `number_of_replicas >= 1`, ES automatically:
1. Promotes replica shards on surviving nodes to primary
2. Creates new replica assignments for orphaned primaries
3. Cluster returns to GREEN after new replicas fully sync (peer recovery)

**Time to GREEN:** Depends on shard size. At 30 GB per shard, peer recovery at 500 MB/sec = ~60 seconds per shard. 8 shards = ~8 minutes to full GREEN.

### Mitigation

- `cluster.routing.allocation.node_concurrent_recoveries: 4` — speed up recovery
- `indices.recovery.max_bytes_per_sec: "500mb"` — throttle to avoid saturating network during recovery
- Monitor YELLOW duration — alert if > 15 minutes

### Runbook

1. Alert: `cluster_status != green` for > 2 minutes
2. `GET /_cat/shards?v&h=index,shard,prirep,state,node` — identify UNASSIGNED shards
3. If node is permanently gone: `GET /_cat/nodes?v` — node missing from list
4. `GET /_cluster/reroute?retry_failed=true` — trigger ES to retry shard allocation
5. If auto-recovery fails: manually assign shard: `POST /_cluster/reroute` with allocate_stale_primary
6. Add replacement node to cluster if permanent hardware failure
7. Monitor until GREEN: `watch -n 5 "curl -s 'http://es:9200/_cluster/health' | jq .status"`

---

## 4. F-03: Index Shard Corruption

### What Happens

A Lucene segment becomes corrupt (hardware bit-flip, incomplete write during crash). Affected shard cannot be opened. Queries against that index may return errors or partial results.

### Detection

- ES log: `IndexCorruptedException: index corrupted`
- Search API returns 500 for queries hitting corrupt shard
- `GET /_cat/shards?v` shows shard in UNASSIGNED state with `reason: INDEX_CREATED[allocation_failed]`

### Recovery Runbook

1. **Identify corrupt shard:** `GET /_cat/shards?v` → find UNASSIGNED or INITIALIZING shard stuck in error
2. **Check if replica is healthy:** If a replica shard is intact, promote it to primary: `POST /_cluster/reroute` with `allocate_stale_primary`
3. **If no healthy replica:** Restore from snapshot
   ```
   POST /_snapshot/s3-backup/snapshot-2024-01-15/_restore
   {
     "indices": "products_v3",
     "ignore_unavailable": true,
     "partial": true
   }
   ```
4. **Reindex from PostgreSQL for gap period:** Identify documents created between snapshot date and now; re-enqueue them to `document-events` Kafka topic
5. **Verify:** Compare document counts between PG and ES after recovery
6. **Post-mortem:** Enable ES shard checksums; verify hardware memory (ECC RAM); enable ES soft deletes for recoverability

---

## 5. F-04: Indexing Lag Spike (Consumer Falls Behind)

### What Happens

Kafka consumer falls behind due to: ES cluster slowness, consumer pod OOM/crash, indexing throughput spike, or Kafka broker issue. NRT SLA (< 5 seconds) is breached — new documents are not searchable.

### Detection

- Kafka consumer lag metric: `kafka_consumer_group_lag > 10000 messages`
- Custom metric: `indexing_lag_seconds > 30`
- Burrow (Kafka lag monitor) alert

### Impact

- Search results do not reflect documents indexed in last N seconds/minutes (depending on lag magnitude)
- Clients may search for documents just created and get zero results
- No error returned — silent staleness

### Recovery Runbook

1. **Identify cause:**
   - `GET /_cluster/health` — is ES slow? (status not green?)
   - `kubectl describe pods indexing-consumer-*` — OOM kills? Pod crashes?
   - Kafka broker metrics — broker failures?
2. **Scale consumers:** `kubectl scale deployment indexing-consumer --replicas=32`
3. **If ES is bottleneck:** Trigger backpressure mode (consumers pause, ES stabilizes, then resume)
4. **If throughput spike:** Consumers catch up automatically once spike subsides; Kafka has buffered all events
5. **Monitor lag drain:** `kafka-consumer-groups.sh --describe --group indexing-nrt-consumer-group`
6. **Communicate SLA breach:** If lag > 5 minutes, notify affected tenants

---

## 6. F-05: PostgreSQL → ES Data Drift

### What Happens

Over time, the ES index diverges from PostgreSQL (source of truth) due to:
- Lost Kafka events (rare but possible in edge cases)
- Failed indexing jobs not retried (DLQ not processed)
- ES shard recovery from old snapshot (missing gap period documents)
- Application bugs that updated PG but not published events

### Detection

Scheduled reconciliation job (runs hourly):
```sql
-- Find documents in PG that are not indexed or indexed with wrong version
SELECT d.document_id, d.version AS pg_version, d.updated_at
FROM documents d
WHERE d.status = 'INDEXED'
  AND d.indexed_at < NOW() - INTERVAL '5 minutes'
  AND (d.checksum != es_checksum OR es_doc_missing)
LIMIT 10000
```

Also compare counts:
```
PG: SELECT COUNT(*) FROM documents WHERE index_name='products' AND status='INDEXED'
ES: GET /products/_count {"query": {"term": {"tenant_id": "acme"}}}
```

If counts diverge by > 0.1%, trigger reconciliation.

### Recovery Runbook

1. **Identify diverged documents:** Run reconciliation query; collect `document_id` list
2. **Re-enqueue to Kafka:** Publish `DocumentReconciliation` event to `document-events` for each diverged doc
3. **Consumers re-index:** Normal indexing pipeline handles reconciliation events idempotently
4. **Verify:** Re-run reconciliation query → expect zero differences
5. **Root cause:** Audit DLQ for unprocessed failures; review indexing_jobs table for FAILED status
6. **Prevent:** Enable checksum drift detection in monitoring; alert on daily drift > 0.01%

---

## 7. F-06: Full Reindex During Production Traffic

### What Happens

Schema change requires full reindex. Reindex job runs on live cluster concurrently with user search traffic. Risk: reindex saturates ES CPU/I/O, causing search latency degradation.

### Mitigation Strategy

1. **Throttle reindex:** `POST /_reindex?requests_per_second=500` (limits throughput)
2. **Off-peak scheduling:** Start reindex at 2 AM; estimated 3 hours for 100M docs at 10,000 docs/sec
3. **Monitor latency during reindex:** Alert if p99 search latency > 80ms during reindex
4. **Adaptive throttling:** If search latency spikes, reduce reindex rate dynamically via API: `POST /_tasks/{taskId}/_rethrottle?requests_per_second=100`
5. **Separate data nodes for old/new indices:** Use shard allocation filtering to route new index to specific nodes, protecting live query nodes

### Recovery if Reindex Fails Mid-Way

- Reindex is restartable: new index accumulates docs idempotently; restart from checkpoint
- Delete partial new index: `DELETE /products_v2`
- Re-create and restart reindex from PostgreSQL scroll (not ES _reindex API — PG scroll is idempotent)

---

## 8. F-07: Autocomplete Query Causing ES GC Pressure

### What Happens

Completion Suggester stores its FST (finite state transducer) data structure in JVM heap. Large FST + many autocomplete queries simultaneously triggers heap pressure and long GC pauses (stop-the-world GC). During GC pause, all ES shard operations stall. Search latency spikes to seconds.

### Detection

- JVM GC pause time > 500ms
- ES heap usage > 85%
- Node circuit breaker: `GET /_nodes/stats?filter_path=*.breakers`

### Recovery Runbook

1. **Immediate:** Disable autocomplete endpoint (`502` from API gateway for `/suggest`) to stop driving ES heap
2. **Identify FST size:** `GET /_cat/segments?v&h=index,shard,segment,ram.committed` — look for large completion field segments
3. **Scale out:** Add coordinating nodes to distribute autocomplete request fan-out
4. **Reduce FST size:** Limit completion suggester `max_input_length` (default 50 chars); limit weight field cardinality
5. **Alternative:** Move autocomplete to Redis sorted set (removes ES GC risk entirely)
6. **Monitor:** Set JVM GC time alert threshold: > 5% of wall time

---

## 9. F-08: Query Timeout Cascade

### What Happens

One slow ES query (heavy aggregation, many shards) times out. ES returns partial results or 500. Query Service sees error, retries → retry amplification. ES is now processing N retried versions of the same slow query. Cascade begins.

### Detection

- Error rate > 1% on search API
- ES `search.timeout` exceeded counter increases
- Query Service error logs: `SocketTimeoutException: ES timeout`

### Recovery Runbook

1. **Immediate:** Set query timeout on ES calls: `GET /index/_search?timeout=5s` (ES returns partial results rather than timing out with error)
2. **Identify slow queries:** ES slow query log: `index.search.slowlog.threshold.query.warn: 2s`
3. **Circuit breaker:** Resilience4j circuit breaker on Query Service's ES client
   - Open after 50% error rate in 30s window
   - Return cached results (Redis) for 60 seconds while circuit is open
4. **Drop heavy queries:** API Gateway rate-limits aggregation queries more aggressively (separate limit from keyword queries)
5. **ES query kill:** `GET /_tasks?actions=*search&detailed=true` → `DELETE /_tasks/{taskId}` for long-running queries
6. **Root cause:** Was it a user-initiated DDoS (scraper running complex aggregations)? Rate limit that tenant.

---

## 10. F-09: Hot Shard Problem

### What Happens

All queries for one heavily-used tenant route to the same shard (if using custom routing) or one shard receives disproportionate documents due to hash collision or uneven data distribution. That shard's node runs at 100% CPU while others idle.

### Detection

- `GET /_cat/nodes?v&h=name,cpu` → one node at 100%, others at 10%
- `GET /_cat/shards?v` → one shard has 10x more docs than others
- Per-shard query latency: coordinating node logs show one shard always takes 10x longer

### Recovery Runbook

1. **Confirm hot shard:** `GET /_cat/thread_pool/search?v&node_id=<hot_node>` — check search queue depth
2. **Short-term:** Increase replicas for hot index: `PUT /products/_settings {"number_of_replicas": 2}` — distributes read load to 3 copies
3. **Medium-term (custom routing issue):**
   - Change routing key from `tenant_id` to `tenant_id + rand(0,N)` — distributes tenant across N shards
   - Requires reindex (new routing strategy applies to new documents; old ones stay on old shards)
4. **Long-term:** Reindex with higher shard count — more shards = finer distribution
5. **Shard rebalancing:** `POST /_cluster/reroute` with `move` command to relocate hot shards to less-loaded nodes

---

## 11. Interview Discussion Points

- **How do you distinguish between ES being truly down vs a transient network blip?** Health check with retries: 3 consecutive failed health checks over 15 seconds. Single failure → warn. Three failures → alert + circuit breaker open. ES going from GREEN → YELLOW is normal (replica rebalancing); YELLOW → RED is the emergency.
- **What's the blast radius of a master node failure?** With 3 dedicated master nodes and 1 failing, ES automatically elects a new master from the remaining 2 (quorum = 2). Cluster remains operational. The election takes ~5–30 seconds — during this window, no index/mapping changes can be made. Queries continue (data nodes serve independently). Zero user impact if properly configured.
- **How do you prevent a full reindex from degrading production search?** (1) Throttle reindex API to 500 docs/sec. (2) Schedule during off-peak. (3) Route new index to dedicated warm nodes during reindex (allocation filtering). (4) Set adaptive throttling tied to search latency. (5) Pre-warm OS page cache on new index nodes before cutting over alias.
- **How do you recover from data drift discovered 24 hours later?** If drift < 1%: reconciliation job re-enqueues affected documents from PG to Kafka; consumers re-index. If drift > 5%: likely a systemic failure; full reindex from PG may be faster than reconciliation. Drift detection SLA: run reconciliation job hourly to catch drift within 1 hour of occurrence.
