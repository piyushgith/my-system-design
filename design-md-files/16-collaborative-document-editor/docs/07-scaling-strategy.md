# 07 — Scaling Strategy

## Objective
Define how each layer of the system scales from zero to 100 M DAU. Address horizontal scaling, fan-out problem, hot document isolation, connection management, and the non-linear scaling challenges unique to real-time collaboration.

---

## Scaling Challenges Unique to Collaborative Editing

Unlike typical read-heavy systems, collaborative editing has three distinct scaling problems:

1. **Fan-out problem**: Each write (op) must be delivered to all N concurrent readers on the same document. At 1,000 users per document × 10,000 ops/sec, that's 10 M messages/sec from a single document.
2. **Session affinity**: All editors of the same document need to receive the same globally ordered op stream. Random routing breaks ordering.
3. **Memory-bound hot state**: The Collaboration Service must hold document state in memory for transformation. At 2 M active documents, the total memory required is non-trivial.

---

## Layer-by-Layer Scaling

### WebSocket Gateway Layer

**Problem:** 25 M concurrent WebSocket connections.

**Approach:**
- Each WebSocket Gateway node handles 50 K connections (typical for Java with Netty, using non-blocking I/O)
- 25 M / 50 K = **500 nodes** at peak
- Nodes are stateless regarding document content; they only maintain connection state
- Kubernetes HPA scales nodes based on active connection count

**Session routing:**
- Client connects to the nearest WebSocket Gateway (geo-DNS routing)
- The gateway registers the connection in a Redis Hash: `ws_sessions:{docId}` → `{userId: nodeId}`
- When a fan-out message arrives on Redis pub/sub channel `presence:{docId}`, each node checks if it has any connections for that docId and pushes to them

**Sticky sessions:** Not required at WebSocket Gateway level because document state lives in Collaboration Service, not in the gateway. The gateway is a pure message router.

---

### Collaboration Service Layer

**Problem:** A single Collaboration Service cannot handle all active documents; but all ops for one document must go to the same pod (for atomic sequence assignment).

**Approach: Consistent Hashing by DocumentId**

```
┌─────────────────────────────────────────┐
│         Collaboration Service Cluster    │
│                                          │
│  Pod 1: owns docs {0x000..0x3FF}         │
│  Pod 2: owns docs {0x400..0x7FF}         │
│  Pod 3: owns docs {0x800..0xBFF}         │
│  Pod 4: owns docs {0xC00..0xFFF}         │
└─────────────────────────────────────────┘
```

- Consistent hash ring maps `documentId → pod`
- WebSocket Gateway routes ops to the correct Collaboration pod
- When a pod is added/removed, rehashing migrates only a fraction of documents (consistent hash property)

**Pod failure:** When a pod dies, its documents are redistributed across surviving pods. During redistribution (~5 seconds), ops for migrating documents return 503 to clients (clients retry). The new pod loads document state from Redis cache + PostgreSQL.

**Hot document isolation:** A document with 10 K ops/sec is assigned to a dedicated pod pool ("hot tier"). Routing table (stored in ZooKeeper or Redis) maps hot documents to their dedicated pod. The hot tier autoscales independently.

**Memory estimation for active state:**
- 2 M active documents × 50 KB average hot state = **100 GB** distributed across pods
- With 500 pods: 200 MB per pod — well within heap limits

---

### Kafka Scaling

**Problem:** 10 M ops/sec × 200 bytes = 2 GB/sec inbound; fan-out is 10× = 20 GB/sec.

**Topic design:**
- `doc-ops` topic: 1,000 partitions
- Partition key: `documentId` (ensures ops for a document are in one partition, preserving order)
- Replication factor: 3 (for durability)
- Retention: 7 days (compressed); 2 TB per broker needed for 7 days at 2 GB/sec compressed

**Throughput math:**
- 10 M ops/sec × 200 bytes = 2 GB/sec
- Kafka compression (Snappy): ~3× → ~700 MB/sec
- With 1,000 partitions: 700 KB/sec per partition (well within per-partition limits)

**Consumer groups:**
- Snapshot Service: consumer group consuming all partitions; 100 consumers for parallelism
- Search Service: separate consumer group; can lag without affecting editing
- Audit Service: separate consumer group; not latency-sensitive

**Hot partition problem:** If one document has 10 K ops/sec and a partition handles 100 docs, that partition gets hot. Solution: hot documents get their own dedicated partition(s) in a separate "hot-docs" topic.

---

### Fan-Out Architecture

The fan-out from 1 op to 1,000 clients is the hardest scaling problem.

**Option A: Redis Pub/Sub per document**
- Each WebSocket Gateway node subscribes to channels for documents its clients have open
- Collaboration Service publishes one message per op to `doc:{docId}` channel
- Redis distributes to all subscribed Gateway nodes
- Each Gateway node pushes to its local connections

**Scaling Redis Pub/Sub:**
- Redis single instance: handles ~1 M pub/sub messages/sec
- At 10 M ops/sec × 10 subscribers average = 100 M fan-out messages/sec → need 100 Redis instances
- Cluster Redis: shard pub/sub channels by `docId` across cluster nodes

**Option B: Kafka fan-out (for async, non-real-time path)**
- WebSocket Gateway nodes each consume from Kafka (polling their relevant document partitions)
- Higher latency (50–200 ms Kafka poll interval) but more durable
- Used as fallback when Redis Pub/Sub fails

**Option C: Direct WebSocket mesh (gossip)**
- Collaboration Service maintains a registry of which Gateway nodes have clients for each document
- Directly sends op to only the relevant Gateway nodes (not all)
- Reduces Redis Pub/Sub load significantly at the cost of more complex service-to-service communication

**Production choice:** Redis Pub/Sub for real-time fan-out + Kafka for durable delivery. Gateway nodes subscribe to only the document channels they have active connections for (not all channels). This dramatically reduces Redis Pub/Sub traffic.

---

### PostgreSQL Scaling

**Problem:** 10 M ops/sec → 10 M inserts/sec into op_log.

**Direct PostgreSQL writes at 10 M/sec are not feasible.** Solution: buffered batch writes.

**Approach: Write Buffer in Kafka → Batch Writer**
1. Collaboration Service writes op to Redis (immediate, for hot cache)
2. Collaboration Service publishes op to Kafka (durable, async)
3. Batch Writer consumer reads from Kafka in batches of 10,000 ops
4. Writes to PostgreSQL using COPY or multi-row INSERT
5. Target: 1,000 PostgreSQL inserts/sec per writer × 10,000 rows/batch = 10 M rows/sec with 10 writers

**Batching tradeoff:** Ops reach PostgreSQL with ~200 ms delay. During this window, crash recovery must fall back to Kafka replay. This is acceptable because Kafka is the source of truth.

**Read replicas:**
- 5 read replicas per PostgreSQL primary shard
- Document open path reads from replica (eventual consistency acceptable — max lag 100 ms)
- Permission checks read from replica with short TTL cache

---

### Document Load Optimization (Thundering Herd)

When a viral document is shared and 10,000 users open it simultaneously:

1. **CDN for static assets** (images in doc): S3 + CloudFront CDN
2. **Snapshot cache warming**: When a document receives a share-link access spike, the Document Service pre-warms the Redis snapshot cache proactively
3. **Read coalescing**: Multiple simultaneous document-open requests for the same docId are coalesced into a single backend fetch (request deduplication in Document Service using Redis SET NX lock)
4. **Staggered snapshot delivery**: For documents with > 1,000 simultaneous openers, serve the snapshot from S3 via CDN (static URL with short TTL) rather than dynamic assembly

---

## Scaling Evolution Table

| Phase | Users | Architecture |
|---|---|---|
| MVP | < 1K | Monolith, single PostgreSQL, no Kafka, polling instead of WS |
| Growth | 10K–1M | Separate WS service, Redis for presence, Kafka introduced |
| Scale | 1M–10M | Microservices split, read replicas, Kafka fan-out, S3 snapshots |
| Hyper-scale | 10M–100M | Consistent hash for Collaboration pods, hot doc isolation, Redis cluster, Citus sharding |
| Global | 100M+ | Multi-region active-active, regional Kafka clusters, geo-partitioned storage |

---

## Multi-Region Scaling

At global scale, a user in Tokyo editing a document should not round-trip to US-East:

**Regional Collaboration:**
- Each region has its own Collaboration Service cluster
- The "master region" for a document is determined by the owner's region at creation time
- Cross-region ops flow through inter-region Kafka replication (MirrorMaker 2)
- Ops are sequenced in the master region; all other regions are followers
- Cross-region latency: 150 ms (Tokyo to US-East) — acceptable for async delivery but causes visible collaboration lag

**Conflict:** If a Tokyo user and a New York user edit the same document simultaneously, their ops both go to the master region for sequencing. This adds 150 ms round-trip for one of them. Acceptable for most cases; not acceptable for a same-room meeting scenario. Solution: temporarily migrate document master to the region with the most concurrent editors.

---

## Interview Discussion Points
- Why does consistent hashing solve the Collaboration Service routing problem better than random load balancing?
- What happens to document state when a Collaboration pod is gracefully rolled (Kubernetes rolling update)?
- At 100 M concurrent WebSocket connections (theoretical), what breaks first in the described architecture?
- How does the thundering herd problem differ from a hot document problem, and why does the solution differ?
- When would you abandon Redis Pub/Sub for fan-out and move to a dedicated message passing system (e.g., NATS)?
- How do you handle the case where the batch PostgreSQL writer is delayed — what is the impact on version history queries?
