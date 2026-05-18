# 09 — Caching Strategy

## Objective
Define the multi-layer caching architecture for the Collaborative Document Editor. Focus on cache placement, invalidation strategy, TTL policies, and the unique challenges of caching mutable, collaboratively edited content.

---

## Caching Challenges Unique to Collaborative Editing

Traditional caching assumes relatively static content. Collaborative documents are different:

1. **Content mutates continuously** — a cached document snapshot can be stale by 10,000 ops within a minute
2. **Permission changes are security-critical** — serving stale permissions means unauthorized access
3. **Active documents are hot** — a single viral document can generate 10 M cache reads/hour
4. **Presence data expires by nature** — cursor positions are only valid for seconds
5. **Snapshot is large** — caching 50 KB per active document × 2 M docs = 100 GB cache footprint

---

## Cache Layers

### Layer 1: CDN (CloudFront / Fastly)
**What is cached:** Static document assets only (embedded images, exported PDFs, document cover images)

**What is NOT cached:** Document content (changes too frequently), WebSocket connections (cannot be CDN-cached)

**TTL:** Images: 7 days (immutable once uploaded); Exports: 1 hour (with cache invalidation on new export)

**Cache key:** S3 object URL; content-addressed (URL includes content hash)

---

### Layer 2: Document Snapshot Cache (Redis)

**What:** Materialized document state = latest snapshot binary + small pending ops buffer

**Structure:**
```
Key: doc:snapshot:{documentId}
Type: String (compressed binary — MessagePack or Protobuf)
TTL: 10 minutes (sliding; reset on each access)
Size: ~50–200 KB per document (compressed)
```

**Population strategy:** Cache-aside
1. On cache miss: Document Service fetches latest snapshot from S3, replays pending ops from PostgreSQL, writes to Redis
2. Cache is populated lazily on first access after eviction

**Invalidation strategy:** TTL-based + event-driven
- TTL eviction for inactive documents (10 minutes of no reads)
- Active documents: snapshot cache is continuously updated by the Collaboration Service (writes new snapshot entry every time a checkpoint is created — every 5 minutes or 1,000 ops)
- On `SnapshotCreated` event from Kafka: update `doc:snapshot:{documentId}` with new snapshot + reset pending ops buffer

**Stale content handling:** Between snapshot updates, the cache entry may lag the live state by up to 5 minutes worth of ops. For document open:
- Serve cached snapshot + fetch ops since `snapshot.at_seq` from `doc:ops:{documentId}` in Redis (see below)
- This gives a near-current document state without re-materializing from PostgreSQL

---

### Layer 3: Op Buffer Cache (Redis Sorted Set)

**What:** Recent ops applied to active documents since the last snapshot

**Structure:**
```
Key: doc:ops:{documentId}
Type: Sorted Set (score = seq, value = serialized op)
TTL: 15 minutes
Max size: 1,000 ops per document (pop oldest when exceeding limit)
```

**Usage:**
- Collaboration Service writes each applied op here immediately (alongside Kafka publish)
- Document Service reads `ZRANGEBYSCORE doc:ops:{docId} (lastSnapshotSeq +inf` to get pending ops
- WebSocket Gateway uses `ZRANGEBYSCORE` for catch-up delivery on reconnect (if gap < 1,000 ops)

**Eviction:** When op count exceeds 1,000, a snapshot should have been created. If not (Snapshot Service is behind), fall back to PostgreSQL for op replay.

---

### Layer 4: Permission Cache (Redis Hash)

**What:** Effective permission level for `(userId, documentId)` pairs

**Structure:**
```
Key: perm:{userId}:{documentId}
Type: String
Value: "owner" | "editor" | "commenter" | "viewer" | "none"
TTL: 60 seconds
```

**Population:** Cache-aside; populated on first permission check
**Invalidation:**
- TTL-based (60-second maximum staleness — acceptable security tradeoff)
- Event-driven: `PermissionGranted` and `PermissionRevoked` Kafka events consumed by a cache invalidation service that calls `DEL perm:{userId}:{documentId}` for affected pairs
- On permission revocation: immediate DEL ensures the next request within the 60-second window re-checks the database

**Security consideration:** 60-second stale permission window means a revoked user can continue editing for up to 60 seconds. For security-sensitive revocations (e.g., data breach response), the revocation event triggers immediate DEL AND a kill message to the user's active WebSocket session.

---

### Layer 5: User & Session Cache (Redis)

**What:** User profile data, active session metadata

**Structure:**
```
Key: user:{userId}
Type: Hash
Fields: displayName, email, avatarUrl, workspaceId
TTL: 30 minutes

Key: session:{sessionId}
Type: Hash
Fields: userId, documentId, wsNodeId, connectedAt, lastSeq
TTL: session lifetime (refreshed by heartbeat)
```

---

### Layer 6: Presence Cache (Redis Hash — ephemeral)

**What:** Real-time cursor positions and selections for all collaborators on a document

**Structure:**
```
Key: presence:{documentId}
Type: Hash
Field: {userId}
Value: JSON {cursor: int, selection: {start, end}, color: hex, displayName: string, updatedAt: epoch}
TTL: 30 seconds per hash entry (implemented via Lua script or scheduled ZRANGEBYLEX cleanup)
```

**Population:** Written by WebSocket Gateway on every cursor/selection event from client
**Eviction:** TTL enforced by periodic cleanup job (every 10 seconds, remove entries where `updatedAt < now - 30s`). No Redis native per-field TTL available; managed at application level.
**No persistence:** Presence is never written to PostgreSQL or Kafka. Loss of Redis does not cause data loss; presence resumes after client next sends a cursor event.

---

### Layer 7: Application-Level Cache (JVM Heap — Collaboration Service)

**What:** Hot document state for active documents currently being edited by the pod

**Structure:**
- In-memory `ConcurrentHashMap<DocumentId, DocumentState>`
- `DocumentState` = current op sequence, last 100 ops for OT transforms, active session count
- Eviction: LRU; max 10,000 documents per pod (100 pods × 10,000 = 1 M hot documents across the cluster)
- Size: 50 KB state × 10,000 docs = 500 MB per pod (acceptable)

**Population:** Loaded from Redis snapshot cache on first op for a document on this pod
**Invalidation:** Not needed (single pod owns document state via consistent hashing; no concurrent writes from other pods)

---

## Cache Invalidation Strategy Summary

| Cache | Invalidation Method | Max Staleness |
|---|---|---|
| CDN static assets | Content-addressed URLs (never stale) + manual purge | 0 for immutable assets |
| Document snapshot (Redis) | TTL (10 min) + SnapshotCreated event | 10 minutes (ops fill the gap) |
| Op buffer (Redis) | TTL (15 min) + LRU eviction at 1000 ops | N/A (append-only) |
| Permission (Redis) | TTL (60 sec) + PermissionRevoked event | 60 seconds |
| User profile (Redis) | TTL (30 min) + profile update event | 30 minutes |
| Presence (Redis) | Application-managed TTL (30 sec per user) | 30 seconds |
| JVM heap (Collaboration) | LRU + pod-level document ownership | N/A (single writer) |

---

## Cache Sizing

| Cache | Items | Item Size | Total |
|---|---|---|---|
| Active document snapshots | 2 M | 100 KB | 200 GB across Redis cluster |
| Op buffers | 2 M | 200 KB | 400 GB across Redis cluster |
| Permissions | 20 M (users × docs) | 20 bytes | 400 MB |
| User profiles | 100 M | 200 bytes | 20 GB |
| Presence | 25 M active sessions | 100 bytes | 2.5 GB |

**Total Redis cluster:** ~650 GB data → 6.5 TB with 10× replication factor → approximately 20 Redis nodes at 256 GB each with 50% utilization headroom.

---

## Autosave & Checkpoint Strategy

**Autosave** (frequent, lightweight): Every 30 seconds of editing activity, the client sends a "checkpoint" signal. The server ensures the current op is durable in Kafka. No snapshot created.

**Checkpoint** (periodic, heavier): Every 5 minutes OR every 1,000 ops, Snapshot Service materializes and persists a snapshot to S3. This reduces the op replay window and bounds document open latency.

**Named revision** (user-initiated): User clicks "Save version"; creates a named Revision pointing to current op seq. No additional snapshot created (can share with the auto-checkpoint).

**Cache warming on checkpoint:** When a new snapshot is written to S3, the Snapshot Service immediately warms the Redis snapshot cache for active documents. This prevents a cache miss storm after checkpoint creation.

---

## Tradeoffs

| Decision | Pro | Con |
|---|---|---|
| 10-minute snapshot TTL in Redis | Reduces S3 reads significantly | 200 GB Redis footprint; expensive |
| 60-second permission cache TTL | Fast permission checks (cache hit rate > 99%) | 60-second window for stale permission |
| JVM heap cache in Collaboration Service | Sub-millisecond OT state access | Memory pressure; state lost on pod restart |
| Application-managed presence TTL | Presence-specific cleanup logic | More complex than TTL-native (Redis 7+ supports Hash expiry with JSON module) |

---

## Interview Discussion Points
- How does the document snapshot cache interact with the op buffer cache to provide a consistent document view?
- What is the cache stampede problem, and how do you prevent it when a hot document's cache entry expires?
- Why can you not simply use Redis `EXPIRE` per Hash field for presence, and how does Redis 7.4's new field-level expiry change this?
- At 100 M DAU, what is the cost comparison of storing document snapshots in Redis vs serving them from S3 with CloudFront?
- How do you handle cache poisoning — a scenario where a corrupt snapshot is written to Redis and served to thousands of users?
