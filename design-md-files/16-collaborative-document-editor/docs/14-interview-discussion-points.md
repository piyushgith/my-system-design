# 14 — Interview Discussion Points

## Objective
Prepare for Staff+ and FAANG-level system design interviews by anticipating follow-up questions, tradeoff challenges, scaling evolution questions, and "what breaks first" analyses. This file is a direct interview preparation asset.

---

## Section 1: Core Algorithm Questions (Most Likely to Be Asked)

### Q: Explain how Operational Transformation works. Walk me through a concrete example.

**Answer framework:**
1. Set up the scenario: two users, same document, concurrent edits
2. Show the problem without OT (naive apply → wrong result)
3. Define the transformation function: `T(op_a, op_b) → op_a'` — transform op_a as if op_b was applied first
4. Show concrete Insert-Delete interaction: if op_b deletes a character before op_a's insert position, shift op_a's position left by 1
5. Explain the server's role: assigns canonical sequence; transforms all incoming ops against all ops applied since the op's `parentSeq`
6. Name the convergence property: all clients must reach the same state regardless of message arrival order

**Trap to avoid:** Don't claim OT is simple. Mention the diamond problem, the complexity of rich-text OT (formatting ops interact with positional ops in non-obvious ways), and why Google Docs took years to get right.

---

### Q: Why would you choose CRDT over OT? Under what conditions?

**OT advantages:**
- Simpler storage (just position + content)
- Well-suited when you have a central server (which you do)
- Easier undo/redo semantics
- No tombstone garbage collection problem

**CRDT advantages:**
- Peer-to-peer (no central server required)
- Natural offline support (merge is always correct by mathematical property)
- Cursor positions anchor to character identity, not position (much more stable)
- No convergence proofs needed (guaranteed by data structure)

**When to choose CRDT:**
- P2P architecture (no server)
- Offline-first mobile app where offline edits may diverge for days
- When OT bugs would be catastrophic and correctness guarantees are paramount

**When to choose OT:**
- Server-centric architecture (you already have it)
- Rich-text with complex formatting interactions
- When undo/redo is a core UX requirement

**Production reality:** Most at-scale systems use a hybrid. Google Docs uses server-centric OT. Figma and Notion use CRDT-based approaches. Linear uses CRDT for specific data types.

---

### Q: How does offline editing work with your architecture?

**Answer framework:**
1. Client buffers ops in local storage (IndexedDB)
2. Client applies ops speculatively (local state advances without server ack)
3. On reconnect: client sends `lastServerSeq` → server sends catch-up ops
4. Client applies catch-up ops to the server-authoritative state
5. Client re-bases buffered local ops against catch-up ops (OT transform)
6. Re-based ops sent to server; server transforms and sequences

**Key points:**
- Client's speculative local state may differ from the final state after rebase (user may see their text "jump" slightly on reconnect)
- This is acceptable UX; the alternative is locking the document while offline (unacceptable)
- Maximum offline duration is bounded by Kafka retention (7 days); after that, full resync

---

## Section 2: Scaling Questions

### Q: How do you handle a document with 10,000 simultaneous editors?

**Answer framework:**
1. This is the fan-out problem: 1 op × 10,000 recipients = 10,000 messages per op
2. At 10 K ops/sec: 100 M messages/sec from one document
3. Solutions:
   - Hot document tier: dedicated Collaboration pod with higher resources
   - Redis Pub/Sub partitioned by docId: each WS node subscribes only to channels for its connected clients
   - Presence sampling: cursor events rate-limited to 1 per 2 seconds per user
   - Micro-batching: aggregate 10 ops into one broadcast message when ops come in bursts
4. Degraded mode for extreme load (> 100K editors): read-only for viewers, write access for limited editors

### Q: What breaks first at 100M DAU?

**Ranked list:**
1. **WebSocket connections** — 25 M concurrent connections requires 500 gateway nodes; connection overhead, not throughput, is the first bottleneck
2. **Redis Pub/Sub fan-out** — At 10 M ops/sec × 10 recipients, Redis becomes the bottleneck. Need Redis Cluster + sharded channels
3. **Kafka throughput** — 2 GB/sec raw is achievable but requires careful partition tuning and compression
4. **PostgreSQL op_log inserts** — Direct writes at 10 M/sec are impossible; batch writer pattern is required
5. **Snapshot Service** — Single-threaded compaction falls behind; needs aggressive parallelism

---

## Section 3: Reliability & Consistency Questions

### Q: Is this system AP or CP under a network partition?

**Answer:** AP during real-time editing; CP for certain operations.

- **Editing operations:** System continues accepting ops (A) even if the master region is unreachable; ops may temporarily diverge across regions. Convergence happens when partition heals (eventual consistency).
- **Permission checks:** System rejects new session establishments if permission service is unavailable and cache is expired (CP decision for security).
- **Sequence assignment:** Requires coordination (CP behavior); if the sequence authority is unavailable, ops queue until it recovers.

**The key insight:** Collaborative editing naturally tolerates temporary inconsistency (users can see slightly different states briefly); security decisions cannot.

### Q: How do you ensure no operation is lost?

**Defense in depth:**
1. Client buffers ops until explicit server `ack`; retransmits on disconnect
2. Server writes to Kafka with `acks=all` before sending ack to client
3. Kafka replication factor 3 ensures no data loss on single broker failure
4. Kafka is the source of truth; if Collaboration Service crashes, ops in Kafka are replayed by the new pod
5. Client `clientSeq` deduplication prevents duplicate processing of retransmitted ops

---

## Section 4: Design Depth Questions

### Q: How does the version history work? How do you reconstruct any version?

**Answer:**
1. Every operation is stored immutably in the op log with a sequence number
2. Snapshots are created every N ops; stored in S3 with their sequence number
3. To reconstruct version at seq K: find latest snapshot with `at_seq <= K`, replay ops `(snapshot.at_seq, K]` on top
4. Named revisions (user-visible versions) are just pointers to a seq number; no additional storage
5. Diff between two versions: reconstruct both; apply Myers diff algorithm; return semantic diff using op log when possible (more meaningful than character diff)

### Q: How do comments stay anchored when the document is heavily edited above them?

**Answer:**
1. Comments store the character offset at creation time AND the seq number when they were created
2. The Comment Service subscribes to the op stream (via Kafka)
3. For each op applied to the document, the Comment Service transforms all open comment anchor ranges using the same OT rules:
   - If an INSERT at position p precedes anchor.start, shift anchor.start and anchor.end by insertion length
   - If a DELETE overlaps the anchor, either shrink or nullify the anchor (depending on how much was deleted)
4. This keeps comment anchors current with document edits in near-real-time (< 1 second lag)

**Tradeoff:** This means Comment Service has a dependency on the OT transform engine. Managed via shared library. If Comment Service falls behind (Kafka lag), comments may temporarily show incorrect positions — resolved when Service catches up.

### Q: How do you implement undo/redo in a multi-user system?

**Answer:**
- Each user has their own undo stack maintained on the client
- Undo does NOT revert other users' changes; it only undoes the current user's ops
- Implementation: on undo, generate the inverse operation of the user's last op (`inverseOp`), transformed against all ops applied since that op
- Example: user inserted "Hello" at seq 50; other user added " World" after at seq 51. User undoes: inverse op is Delete("Hello" at position adjusted for " World" insertion). OT transforms the delete position.
- Redo: re-apply the original op, similarly transformed
- Undo stack is per-session (not persisted); closing and reopening the document clears undo history

---

## Section 5: Tradeoff & Architecture Critique

### Q: Why event sourcing? Isn't it overengineered for this use case?

**When it's justified here:**
- Version history IS event sourcing; you cannot avoid storing the op log if you want history
- Offline sync is natural in event-sourced systems
- OT requires the op log anyway for transformation
- The extra complexity (replay, snapshots) is bounded and well-understood

**When it would be overengineered:**
- If version history was not required, a simpler "last-write-wins with MVCC" would work
- If the system was much smaller scale (< 100K users), event sourcing adds operational complexity without commensurate benefit

### Q: What are the weaknesses of this architecture?

**Honest weaknesses:**
1. **Operational complexity:** 10+ microservices, Kafka, Redis, multiple PostgreSQL clusters, Schema Registry — requires a dedicated platform team
2. **OT correctness:** Getting OT right for rich text is genuinely hard. A single transform function bug causes silent divergence. Property testing helps but cannot guarantee correctness for all future op types.
3. **Schema evolution:** Avro schemas for ops must be evolved carefully. A breaking schema change corrupts the op log.
4. **Startup curve:** A startup should NOT build this. Start with a Firebase Realtime Database / Firestore-backed client and a simple conflict-resolution strategy.
5. **Hot document scalability:** The consistent-hash routing for Collaboration Service adds coordination overhead; complex to maintain correctly during rolling updates.
6. **Tombstone accumulation (if CRDT is adopted):** CRDT tombstones in a long-lived heavily-edited document can make the internal data structure many times larger than the visible content.

---

## Section 6: Staff/Principal Engineer Questions

### Q: How would you design document branching (like Git branches for documents)?

**Answer framework:**
- Each branch is an independent op log forked from a specific seq number
- Branch create: record `{branchId, documentId, forkSeq}`; all new ops go to the branch's op log
- Merge: generate a diff between the branch head and the main head (since fork point); apply as a set of ops to main
- Conflicts: merge conflicts resolved by a conflict UI (similar to Git merge conflicts)
- This is a separate bounded context (Branch Context) with the same OT engine but independent op sequencing

### Q: How would you support a "live document" for 1 million simultaneous viewers (Super Bowl playbook)?

**Answer:**
- At 1M viewers, fan-out is the only problem (viewers don't edit)
- Convert to a broadcast model: server publishes a live "video stream" of document changes via SSE (Server-Sent Events) or HTTP/2 push, not WebSocket
- CDN-cacheable snapshot delivered to viewers; live op deltas broadcast via SSE through CDN edge nodes (Cloudflare Workers / Lambda@Edge)
- Editors are a tiny subset (< 100); they use the full WS-based collaboration system
- Viewers receive a rendered HTML snapshot + incremental DOM updates; not the raw op format

### Q: How do you handle a GDPR right-to-erasure request when the user's content is embedded in a shared document?

**Answer:**
- User's INSERT ops have their content nulled and author_id replaced with a tombstone UUID
- The document content is affected: text inserted by the deleted user is removed
- This is transparent to other users (they see the text disappear, similar to a delete op)
- An automatic op is generated: `Delete(range covered by erased user's inserts)` — applied to the document
- Comments by the user are anonymized (author_id tombstoned; comment body preserved if it adds context, or deleted if requested)
- Trade-off: the document "loses" the user's contributions permanently; this is the legally required behavior

---

## Common Interview Mistakes

| Mistake | Why It's Wrong | Correct Approach |
|---|---|---|
| "I'll use a database trigger to broadcast changes" | Doesn't scale; polling-based; not real-time | Kafka event stream + Redis Pub/Sub |
| "I'll use WebSocket and broadcast to all users" | Doesn't address ordering; no conflict resolution | OT/CRDT + sequence numbers |
| "Last-write-wins for conflict resolution" | Causes data loss; unacceptable for collaborative editing | OT transformation preserves both users' intent |
| "I'll store the full document on every save" | 1 billion documents × 50 KB × every save = petabytes | Store ops (deltas); materialize snapshots periodically |
| "I'll use a single Redis instance for all fan-out" | Single Redis = 1 M messages/sec cap; not enough | Redis Cluster + sharded pub/sub |
| Starting with microservices for MVP | Operational complexity kills small teams | Modular monolith → extract when needed |

---

## Startup vs FAANG Comparison

| Decision | Startup | FAANG |
|---|---|---|
| Architecture | Firebase + Yjs client-side CRDT | Event-sourced microservices |
| Conflict resolution | CRDT (simple, correct) | Hybrid OT + CRDT |
| Version history | Periodic full snapshots (simpler) | Full op log + compaction |
| Real-time sync | Firebase Realtime / Pusher | Custom WS + Redis Pub/Sub |
| Search | Postgres full-text search | Elasticsearch |
| Deployment | Heroku / single cloud region | Multi-region K8s |
| Team size | 2–5 engineers | 50+ engineers (platform + product) |
| Time to MVP | 4–8 weeks | 6–18 months for full system |
