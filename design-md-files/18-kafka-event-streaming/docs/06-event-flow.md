# 06 — Event Flow: Kafka-like Event Streaming System

---

## Objective

Trace message lifecycle from producer publish to consumer processing. Cover internal replication flow, consumer group rebalance flow, leader failover, and offset commit flow with sequence diagrams.

---

## Flow 1: Producer Write (acks=all, Idempotent)

```mermaid
sequenceDiagram
    participant P as Producer Client
    participant SR as Schema Registry
    participant B1 as Broker 1 (Leader P0)
    participant B2 as Broker 2 (Follower)
    participant B3 as Broker 3 (Follower)

    P->>SR: GET /schemas/ids/42 (first-time only, cached after)
    SR-->>P: Schema definition

    P->>B1: MetadataRequest (user-events topic)
    B1-->>P: Partition 0 → Leader: Broker 1

    Note over P: Batch messages for linger.ms=5ms or batch.size=16KB

    P->>B1: ProduceRequest(partition=0, acks=-1, producerId=100, epoch=1, seq=0..4, batch)
    Note over B1: Append batch to local log (LEO advances)
    B1->>B2: FetchRequest (follower replica pull)
    B1->>B3: FetchRequest (follower replica pull)
    B2-->>B1: FetchResponse (acked seq 0..4)
    B3-->>B1: FetchResponse (acked seq 0..4)
    Note over B1: All ISR acked → advance HighWatermark
    B1-->>P: ProduceResponse(baseOffset=1000, errorCode=NONE)
```

**Key Events in Write Path:**
1. Producer batches records (amortizes per-message overhead)
2. Batch sent with idempotency metadata (producerId, epoch, sequence)
3. Leader appends to its log segment first
4. Followers replicate via pull (not push from leader)
5. HWM advances only after all ISR members acknowledge
6. Leader responds to producer only after HWM advance (for acks=all)

---

## Flow 2: Follower Replica Fetch (Internal)

```mermaid
sequenceDiagram
    participant F as Follower Broker 2
    participant L as Leader Broker 1

    loop Every replica.fetch.ms
        F->>L: FetchRequest(replicaId=2, partition=0, fetchOffset=LEO_follower, maxBytes=1MB)
        Note over L: Serve records from fetchOffset up to HWM
        L-->>F: FetchResponse(records, highWatermark=HWM_leader)
        Note over F: Append records to local log
        Note over F: Update local LEO
        F->>L: (next fetch carries updated LEO implicitly)
        Note over L: Update ISR list — F is in-sync if LEO within replica.lag.time.max.ms
    end
```

**ISR Management:**
- If follower's LEO falls behind leader LEO by more than `replica.lag.time.max.ms` (default 30s), it's removed from ISR
- Removed from ISR = writes with acks=all won't wait for this replica
- If follower catches up, it's re-added to ISR
- HWM = min(LEO across all ISR members)

---

## Flow 3: Consumer Fetch (Normal Path)

```mermaid
sequenceDiagram
    participant C as Consumer Client
    participant GC as Group Coordinator (Broker 3)
    participant L as Leader Broker 1 (Partition 0)

    C->>GC: FindCoordinator(groupId="analytics-group")
    GC-->>C: Coordinator: Broker 3

    C->>GC: JoinGroup(groupId, topics=[user-events], memberId="")
    GC-->>C: JoinGroupResponse(memberId=C1, leader=C1, generationId=1, members=[C1])

    Note over C: C1 is group leader — runs assignment strategy
    C->>GC: SyncGroup(groupId, generationId=1, assignments={C1→[P0,P1,P2]})
    GC-->>C: SyncGroupResponse(assignment=[P0,P1,P2])

    loop Every poll()
        C->>L: FetchRequest(partition=0, fetchOffset=lastCommittedOffset, maxWait=500ms)
        L-->>C: FetchResponse(records[offset 100..199], highWatermark=250)
        Note over C: Process records
        C->>GC: OffsetCommit(partition=0, offset=200)
        GC-->>C: OffsetCommitResponse(NONE)

        C->>GC: Heartbeat(generationId=1)
        GC-->>C: HeartbeatResponse(NONE)
    end
```

**Critical: Poll() drives everything**
- Fetch + heartbeat both happen within `poll()`
- If `poll()` not called within `max.poll.interval.ms` (default 5 min), broker triggers rebalance
- Long message processing between polls risks session timeout → rebalance

---

## Flow 4: Consumer Group Rebalance

```mermaid
sequenceDiagram
    participant C1 as Consumer 1 (existing)
    participant C2 as Consumer 2 (new joiner)
    participant GC as Group Coordinator

    Note over C2: New consumer starts, sends JoinGroup
    C2->>GC: JoinGroup(groupId, memberId="")

    Note over GC: Rebalance triggered — notify existing members
    C1->>GC: Heartbeat
    GC-->>C1: HeartbeatResponse(REBALANCE_IN_PROGRESS)

    Note over C1: Revoke current partitions (commit offsets first)
    C1->>GC: OffsetCommit (final commit before revoke)
    C1->>GC: JoinGroup(groupId, memberId=C1, generationId=2)

    Note over GC: Both members joined — GC picks leader
    GC-->>C1: JoinGroupResponse(leader=C1, members=[C1,C2], generationId=2)
    GC-->>C2: JoinGroupResponse(leader=C1, members=[C1,C2], generationId=2)

    Note over C1: C1 runs partition assignment strategy
    C1->>GC: SyncGroup(generationId=2, assignments={C1→[P0,P1], C2→[P2,P3]})
    C2->>GC: SyncGroup(generationId=2, assignments={})

    GC-->>C1: SyncGroupResponse(assignment=[P0,P1])
    GC-->>C2: SyncGroupResponse(assignment=[P2,P3])

    Note over C1,C2: Both resume consuming with new assignments
```

**Rebalance Cost:**
- All consumers stop consuming during rebalance (stop-the-world)
- Duration: `max.poll.interval.ms` in worst case (waiting for slow member)
- **Cooperative rebalance** (KIP-429): only revoke partitions that must move — reduces pause significantly

---

## Flow 5: Leader Failover

```mermaid
sequenceDiagram
    participant B1 as Broker 1 (Failed Leader)
    participant B2 as Broker 2 (Follower)
    participant C as Controller
    participant P as Producer

    Note over B1: Broker 1 crashes (disk failure / network partition)

    C->>C: Broker 1 session timeout exceeded
    Note over C: Check ISR for partition — B2 is in ISR
    C->>B2: LeaderAndIsrRequest(partition=0, leader=B2, leaderEpoch=5, isr=[B2])
    B2-->>C: LeaderAndIsrResponse(OK)

    Note over C: Update metadata log with new leadership
    C->>P: (indirectly — producer refreshes metadata on error)

    P->>B1: ProduceRequest (stale metadata)
    B1-->>P: (no response — connection refused)
    Note over P: Metadata refresh triggered

    P->>B2: MetadataRequest
    B2-->>P: Partition 0 → Leader: Broker 2

    P->>B2: ProduceRequest (with same batch, idempotent — dedup on re-send)
    B2-->>P: ProduceResponse(baseOffset=..., NONE)
```

**Leader Epoch Fencing:**
When B2 becomes leader at epoch 5, it truncates its log to the last committed HWM at epoch 4. Any records from B1 that were past HWM (not fully replicated) are discarded. This ensures no divergence.

---

## Flow 6: Transactional Producer (Exactly-Once)

```mermaid
sequenceDiagram
    participant P as Transactional Producer
    participant TC as Transaction Coordinator
    participant B1 as Broker (Partition 0)
    participant B2 as Broker (Partition 1)

    P->>TC: InitProducerIdRequest(transactionalId="order-processor")
    TC-->>P: ProducerIdResponse(producerId=200, epoch=1)

    P->>TC: BeginTransaction (local state only)

    P->>B1: ProduceRequest(transactional=true, partition=0, batch)
    P->>B2: ProduceRequest(transactional=true, partition=1, batch)
    B1-->>P: OK
    B2-->>P: OK

    P->>TC: AddPartitionsToTxn(partitions=[P0,P1])
    TC-->>P: OK

    P->>TC: EndTransaction(COMMIT)
    TC->>B1: WriteTxnMarkers(COMMIT, epoch=1)
    TC->>B2: WriteTxnMarkers(COMMIT, epoch=1)
    B1-->>TC: OK
    B2-->>TC: OK
    TC-->>P: TransactionCommitted

    Note over B1,B2: Consumers with isolation=READ_COMMITTED can now read these records
```

**Two-Phase Commit via Transaction Log:**
1. Transaction coordinator persists transaction state to `__transaction_state` topic
2. Phase 1: mark transaction PREPARE_COMMIT in log
3. Phase 2: write COMMIT markers to all participating partitions
4. If coordinator crashes after phase 1, recovery reads `__transaction_state` and completes commit

---

## Flow 7: Tiered Storage Read

```mermaid
sequenceDiagram
    participant C as Consumer
    participant B as Broker
    participant RM as RemoteStorageManager
    participant S3 as Object Storage

    C->>B: FetchRequest(partition=0, offset=50000)
    Note over B: Offset 50000 is in remote tier (local log starts at 1M)
    B->>RM: FetchRemoteLogSegment(topicPartition, segmentMetadata, startOffset=50000)
    RM->>S3: GetObject(bucket, segment_key, range=...)
    S3-->>RM: Segment bytes
    RM-->>B: Records from offset 50000
    B-->>C: FetchResponse(records, highWatermark)
```

---

## Message Lifecycle Summary

```
Producer → [batch] → Broker Leader → [replicate] → ISR Followers
                                   → [advance HWM]
                                   ↓
                            Consumer Fetch
                                   ↓
                         Consumer Processing
                                   ↓
                          Offset Commit
                                   ↓
                     [retention period expires]
                                   ↓
                        Segment Deletion / Compaction
```

---

## Key Timing Parameters

| Parameter | Default | Impact |
|---|---|---|
| `linger.ms` | 0 (send immediately) | Batching trade-off: latency vs throughput |
| `batch.size` | 16 KB | Max batch size per partition before send |
| `max.block.ms` | 60s | Max time producer blocks when buffer full |
| `replica.fetch.max.bytes` | 1 MB | Max bytes per follower fetch |
| `max.poll.interval.ms` | 5 min | Max time between consumer polls before rebalance |
| `session.timeout.ms` | 45s | Heartbeat miss tolerance |
| `heartbeat.interval.ms` | 3s | Heartbeat frequency (should be 1/3 session.timeout) |
| `fetch.min.bytes` | 1 byte | Min data for broker to respond to fetch |
| `fetch.max.wait.ms` | 500ms | Max wait before responding regardless of min.bytes |

---

## Tradeoffs

| Flow | Decision | Cost |
|---|---|---|
| Replication | Pull-based follower fetch | Slight latency vs push; natural rate limiting for slow replicas |
| Rebalance | Stop-the-world rebalance | Consumer pause; cooperative rebalance (KIP-429) mitigates |
| Transactions | 2PC via coordinator | ~2x latency overhead; coordinator is bottleneck for high txn volume |
| Tiered storage | Remote storage for old segments | Extra latency for historical reads; consumer SLA must account for this |

---

## Interview Discussion Points

- **What happens if a consumer crashes mid-processing after fetching but before committing?** Records are re-delivered on restart (at-least-once). Consumer must handle duplicates (idempotent processing or exactly-once via transactions)
- **Why does Kafka use pull for replication (not leader-push)?** Same reason as consumer pull: natural rate control. If follower is slow (GC, disk), pull-based catch-up doesn't overwhelm it. Leader just accumulates in its log
- **What is the rebalance blast radius?** All consumers in a group stop. For a group with 100 consumers and 1000 partitions, a single new joiner causes all 100 consumers to revoke all 1000 partitions and redistribute — O(n) disruption. Cooperative rebalance moves only the necessary subset
- **How does exactly-once prevent duplicate delivery even across broker failover?** Transaction coordinator persists commit state before responding. On failover, new coordinator reads `__transaction_state` and completes any in-flight transactions in deterministic way
- **Can a consumer read faster than producer writes?** Yes — fetch returns empty if nothing new, and long-polling ensures immediate delivery when new records arrive. Consumer is never woken up spuriously
