# 09 — Caching Strategy: Kafka-like Event Streaming System

---

## Objective

Define caching layers across broker, client, and schema registry to maximize throughput, minimize latency, and reduce I/O overhead. Kafka's architecture is fundamentally cache-centric — the OS page cache IS the primary read cache.

---

## Caching Philosophy

Unlike web applications with explicit Redis caches, Kafka's caching is mostly implicit and OS-level:

| Cache Layer | Location | What's Cached |
|---|---|---|
| OS Page Cache | Broker OS | Log segment data (the primary cache) |
| Client Metadata Cache | Producer/Consumer process | Topic/partition/leader metadata |
| Schema Cache | Producer/Consumer process | Schema definitions by ID |
| Offset Cache | Consumer process | Last committed offset |
| ACL Cache | Broker process | Authorization rules |
| Group Metadata Cache | Group Coordinator (in-memory) | Consumer group state |
| Quota Cache | Broker process | Per-client quota state |

---

## OS Page Cache — The Primary Cache

### Why Page Cache over Application-Managed Cache

Kafka deliberately avoids in-JVM message caching:

| Approach | JVM Heap Cache | OS Page Cache |
|---|---|---|
| Surviving GC | GC pressure at 32+ GB heap | No GC — kernel managed |
| Warm after restart | Cold start — must reload | Survives broker JVM restart |
| Zero-copy consumer delivery | Not possible (data in heap) | `sendfile` syscall — zero copies |
| Memory efficiency | Object overhead (20–50 bytes per object) | Raw bytes — no overhead |
| Eviction policy | LRU in application code | OS LRU — battle-tested |
| Cache hit rate | Depends on heap size vs working set | Entire free RAM available |

**Key insight:** A broker with 128 GB RAM and 6 GB JVM heap has 122 GB available for page cache. Typical working set (last few hours of writes) often fits entirely in page cache — consumers reading recent data never touch disk.

### Page Cache Sizing

```
Effective page cache = Total RAM - JVM heap - OS overhead (~2 GB)

Example: 128 GB RAM, 6 GB heap → ~120 GB page cache
Write throughput: 1 GB/sec
Last 2 minutes in cache: 120 GB

Consumers reading records from last 2 minutes: 100% cache hit
Consumers reading older records: disk I/O (covered by tiered storage)
```

### Cache-Friendly Access Pattern

Sequential read/write access pattern is maximally cache-friendly:
- Writes: always append → OS writes sequentially into page cache
- Consumer reads: sequential from an offset → high locality, prefetch-friendly
- OS read-ahead: kernel detects sequential access and prefetches next pages automatically

### Broker Tuning for Page Cache

```
# Keep JVM heap small to leave RAM for page cache
-Xmx6g -Xms6g

# Flush data to disk less frequently (more stays in page cache)
log.flush.interval.messages=10000
log.flush.interval.ms=1000

# Avoid swapping page cache to disk (never swap)
vm.swappiness=1

# Optimize page cache writeback
vm.dirty_ratio=80
vm.dirty_background_ratio=5
```

---

## Client Metadata Cache

### What's Cached

Clients (producers and consumers) maintain an in-memory metadata cache:

```
MetadataCache {
  clusterId: String
  brokers: Map<brokerId, BrokerInfo(host, port, rack)>
  topics: Map<topicName, TopicMetadata>
    TopicMetadata {
      partitionCount: int
      partitions: Map<partitionId, PartitionMetadata>
        PartitionMetadata {
          leaderId: int
          leaderEpoch: int
          isrList: List<int>
          offlineReplicas: List<int>
        }
    }
}
```

### Cache Refresh Triggers

| Trigger | Action |
|---|---|
| On startup | Full metadata fetch for subscribed topics |
| `LEADER_NOT_AVAILABLE` error | Refresh affected partition metadata |
| `NOT_LEADER_OR_FOLLOWER` error | Refresh affected partition, retry on new leader |
| Periodic (default `metadata.max.age.ms`=300s) | Background refresh regardless of errors |
| Topic partition count change | Refresh when new partition discovered |

**Why not push-based metadata updates?** Pull-based refresh on error is simpler — most clients don't need real-time partition leader updates. The milliseconds of routing to wrong broker (which returns an error) is acceptable.

### Client-Side Partition Routing Cache

Producer caches the partition assignment for each message key:
```
RecordAccumulator:
  PartitionerCache: key → partition (based on murmur2 hash)
  
Note: cached routing becomes stale after partition count change.
Clients detect new partitions during metadata refresh and invalidate key routing cache.
```

---

## Schema Registry Cache

### In-Process Schema Cache (Producers/Consumers)

```
// Producer-side
SchemaRegistryClient {
  cache: HashMap<SubjectVersionPair, ParsedSchema>
  idToSchemaCache: HashMap<Integer, ParsedSchema>
  
  // First registration per topic
  getSchemaId(subject, schema) → schemaId  // network call
  // Subsequent records with same schema
  schemaId = localCache.get(subject) → no network call
}
```

Cache is never invalidated — schema versions are immutable. Once cached, schema ID → schema definition is permanent.

**Cache size:** Typically bounded (default `max.schemas.per.subject=1000`). For most services, 10–100 schemas per deployment — trivial memory.

### Schema Registry Server-Side Cache

Schema Registry service itself caches all schemas in-memory:

```
SchemaRegistryCache {
  schemasById: ConcurrentHashMap<Integer, Schema>
  schemasBySubjectVersion: ConcurrentHashMap<String, Schema>
  latestVersionBySubject: ConcurrentHashMap<String, Integer>
}
```

On startup: load all schemas from PostgreSQL into memory. Subsequent requests served from cache — PostgreSQL only for durability.

**Cache warm-up time:** 50K schemas × avg 1 KB = 50 MB — loads in < 1 second.

---

## Group Coordinator Cache (Broker In-Memory)

Consumer group state maintained entirely in memory by the group coordinator broker:

```
GroupCoordinator {
  groups: ConcurrentHashMap<groupId, GroupMetadata>
  GroupMetadata {
    state: GroupState
    generationId: int
    leader: String
    members: Map<memberId, MemberMetadata>
    pendingOffsetCommits: Map<TopicPartition, OffsetAndMetadata>
    offsets: Map<TopicPartition, OffsetAndMetadata>  // committed offsets
  }
}
```

**On coordinator restart:** Reload group metadata from `__consumer_offsets` topic (log replay). Warm-up time depends on partition size — typically < 10 seconds.

**Memory pressure:** 100K consumer groups × 10 partitions × avg metadata ~500 bytes = ~500 MB — manageable.

---

## ACL Cache (Broker)

ACL rules loaded into memory on startup from metadata log:

```
SimpleAclAuthorizer {
  aclCache: Map<ResourcePattern, VersionedAcls>
  // Refreshed on metadata update from controller (push notification)
}
```

**Update latency:** ACL changes propagate via metadata log to all brokers in < 100ms (metadata log replication). Brief window where old ACL applies — acceptable for most use cases.

---

## What Kafka Does NOT Cache (By Design)

| Data | Reason Not Cached |
|---|---|
| Individual messages in JVM heap | GC pressure, page cache is better |
| Producer offset bookkeeping | Producer is stateless for offsets; broker assigns |
| Consumer processing state | Consumer responsibility, not broker's |
| Cross-partition ordering state | Not Kafka's concern; consumer must handle |

---

## Caching Anti-Patterns

### Anti-pattern: Caching consumer-side application state in broker
**Problem:** Application business state (e.g., "order processed") must not be in the broker. Broker only knows offsets — never message semantics.
**Fix:** Consumer application manages its own state store.

### Anti-pattern: Warming consumer from beginning on every restart
**Problem:** Consumer fetches from offset 0 on restart → reads all historical data → page cache eviction for current writers.
**Fix:** Consumers commit offsets and resume from last committed offset on restart.

### Anti-pattern: Tight heap to "save memory for other apps"
**Problem:** Reduces page cache size → more disk I/O → higher latency.
**Fix:** Dedicate brokers to Kafka only. Leave 90%+ of RAM for page cache.

---

## Redis Usage (When and Why)

Kafka itself does not use Redis. Redis may be added for:

| Use Case | How | Notes |
|---|---|---|
| Consumer lag dashboard | Cache lag metrics per group | Poll Kafka AdminClient, store in Redis, serve UI |
| Schema lookup acceleration | L1 cache in front of Schema Registry | Rarely needed — in-process cache is usually sufficient |
| Rate limiting (admin API) | Redis token buckets for REST API | Kafka's internal quota handles broker-level throttling |
| Dead letter queue tracking | Track failed message keys with TTL | Application-level concern, not Kafka infrastructure |

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| OS page cache primary | No GC, zero-copy, survives JVM restart | Cache eviction under memory pressure (other processes) — dedicate brokers |
| Client-side metadata cache (pull) | Simple; error-triggered refresh sufficient | Brief routing to wrong broker on failover — adds ~1 extra RTT |
| In-process schema cache | Zero network overhead for schema lookup | Memory overhead (small); schema registry server can be taken down briefly |
| Group metadata in-memory | Low latency offset commits, no external DB | Coordinator restart = cold cache → replay from __consumer_offsets |

---

## Interview Discussion Points

- **Why does Kafka not use Redis or Memcached?** Page cache + zero-copy delivers comparable performance without the operational overhead. Adding Redis would add a network hop to the critical consumer path
- **What happens to the cache when a broker restarts?** JVM cache is empty; page cache survives if OS is not restarted (warm page cache after JVM restart). Full disk warm-up needed only after OS restart — sequential reads help OS prefetch quickly
- **How do consumers avoid the cold-start problem?** Commit offsets → resume from last committed offset. Combined with page cache warmth from other consumers reading the same partitions, cold start impact is minimal
- **What is the cache invalidation strategy for metadata?** Pull-based with error triggers. Partition leader metadata is versioned by `leaderEpoch`. Stale routing gets a `NOT_LEADER` error → refresh → retry. Eventually consistent within 1 RTT
- **When would you add Redis in front of Kafka?** Rarely for core path. Use cases: consumer lag dashboard (cache metrics for UI), application-level deduplication cache (tracking processed message IDs), or rate limiting for external-facing producer API
