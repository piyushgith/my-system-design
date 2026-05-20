# 11 — Failure Scenarios: Chat Application

---

## Objective

Analyze the most impactful failure scenarios in the chat platform. For each failure, define detection, impact, mitigation, recovery path, and what the user experience should be. Apply CAP theorem and distributed systems thinking throughout.

---

## CAP Theorem Position

A real-time chat system must choose its consistency model carefully:

| Operation | Consistency Choice | Rationale |
|-----------|------------------|-----------|
| Message ordering within a conversation | **CP** (Consistency + Partition Tolerance) | Ordering errors in a conversation are visible and embarrassing. Prefer blocking over misordering. |
| Presence status | **AP** (Availability + Partition Tolerance) | Stale presence (showing someone online when they're not) is far better than showing errors |
| Message delivery | **AP** at the delivery layer; **CP** at the storage layer | Messages must be stored durably (CP); delivery can be eventual (AP) |
| Unread counts | **AP** | A slightly wrong unread count is acceptable; blocking access to chat is not |

---

## Failure Scenario 1: WebSocket Server Crash

### Description
One of the 1,000 WebSocket servers crashes (OOM, hardware failure, deployment error). 50,000 users lose their active connections.

### Detection
- Health check endpoint `/health` stops responding → load balancer removes server from pool within 10 seconds
- Connection Registry TTL expires for that server's users within 90 seconds
- Kubernetes liveness probe fails → pod restarted within 30 seconds

### Impact
- 50,000 users temporarily disconnected
- Messages sent to those users during the 10–90 second window: Fan-Out finds no active connection → routes to Notification Service for push
- Messages in-flight at crash moment: may be lost from the delivery queue (not committed to Kafka yet)

### Mitigation
1. **Client reconnect**: All clients implement exponential backoff reconnect (1s, 2s, 4s, max 30s)
2. **Graceful drain**: Before planned restarts, load balancer stops routing new connections to the server; existing connections are allowed to migrate naturally
3. **Outbox relay**: Messages written to Cassandra before the crash are safely stored — Fan-Out sees them via Kafka (Outbox pattern ensures Kafka event is eventually published)

### Recovery Path
1. Client reconnects to any other available WS server
2. Client sends `last_sync_seq` per conversation
3. Server pushes missed messages from Cassandra
4. User sees all messages with brief "reconnecting" indicator

### User Experience
- 2–10 second reconnect delay
- Brief "Connecting..." indicator in UI
- All messages received on reconnect

---

## Failure Scenario 2: Cassandra Partial Failure (Node Down)

### Description
1 of 60 Cassandra nodes goes down (hardware failure, network partition).

### Detection
- Cassandra gossip protocol detects node failure within 5–10 seconds
- Message Service's Cassandra client detects write failures to specific token ranges
- Monitoring: Cassandra node status → alert if DOWN > 30 seconds

### Impact
- With Replication Factor 3 and QUORUM write (2/3 needed):
  - 1 node down: writes still succeed (2 remaining replicas ACK)
  - No user-visible impact in typical failure
- If 2 nodes from the same replica set go down: QUORUM cannot be achieved → writes fail

### Mitigation
- **QUORUM consistency**: designed to survive 1 node failure per token range
- **Read consistency**: LOCAL_ONE for history reads → read from any available replica
- **Hinted Handoff**: Cassandra stores "hints" for the failed node — when it comes back, it replays missed writes

### Recovery Path
1. Failed node comes back up
2. Cassandra repair process (nodetool repair) re-syncs data from other replicas
3. Hinted handoff replays writes that occurred during outage
4. No data loss (RF=3 prevents loss up to 2 simultaneous failures)

### User Experience
- Invisible to users for single node failure
- If QUORUM fails: message send returns error → client shows "Message failed to send" → user can retry

---

## Failure Scenario 3: Redis Cluster Partial Failure

### Description
One Redis Cluster shard master fails. Automatic failover to replica takes 15–30 seconds (Redis Sentinel / Redis Cluster failover).

### Impact During Failover Window

| Feature | Impact |
|---------|--------|
| Presence tracking | Stale — users on this shard appear offline or online incorrectly |
| Connection Registry | Can't route messages to those users' servers → offline notification fallback |
| Sequence numbers | Cannot assign seq for conversations on this shard → message send fails |
| Typing indicators | Lost — acceptable |
| Unread counts | Stale — may not increment |

### Mitigation for Sequence Number Failure

```
If Redis INCR fails for seq:{convId}:
  1. Retry 3× with 10ms delay
  2. If still failing: fallback to Cassandra MAX query
     SELECT MAX(sequence_num) FROM messages WHERE conv_id = ? AND time_bucket = CURRENT_MONTH
  3. Use MAX + 1 as the sequence number (pessimistic — may leave gaps)
  4. Mark message with seq_from_fallback=true for monitoring
```

Gaps in sequence numbers are surfaced in monitoring but are NOT visible to users — clients simply display messages in the order received.

### Recovery
- Redis Cluster promotes replica to master automatically
- WS servers re-publish heartbeats → presence restored within 90 seconds
- Message sends resume normally

---

## Failure Scenario 4: Kafka Broker Failure (Partial)

### Description
1 of 12 Kafka brokers fails. Partitions for which this broker is the leader must elect new leaders.

### Detection
- Kafka controller detects broker failure within 5 seconds (Zookeeper/KRaft heartbeat)
- Leader election for affected partitions completes within 10–30 seconds

### Impact During Leader Election (10–30 seconds)
- Producers publishing to affected partitions → `NotLeaderForPartitionException`
- Message Service: Outbox relay cannot publish → messages queue up in Cassandra Outbox table
- Fan-Out consumers for affected partitions: paused during leader election
- New messages sent during this window: stored in Cassandra, queued in Outbox, not yet fanned out

### Mitigation
- Producer retry: Kafka producer automatically retries after leader election
- Outbox relay: continues polling Cassandra Outbox table → publishes as soon as new leader is available
- Consumer groups: automatically reassign partitions from failed broker

### User Experience
- 10–30 second delay in real-time delivery for affected conversations
- Messages eventually delivered after leader election
- No message loss (Cassandra is the source of truth; Kafka failure doesn't lose messages)

---

## Failure Scenario 5: Fan-Out Service Overload (Group Message Spike)

### Description
A large Slack-style announcement channel sends a message to 10,000 members simultaneously. Fan-Out is overwhelmed by the sudden spike.

### Symptoms
- Kafka consumer lag grows (`chat.message.created` topic)
- Redis Pub/Sub publish rate spikes
- Fan-Out service CPU at 100%

### Impact
- Real-time delivery delayed for all conversations (not just the large group)
- Kafka lag > threshold → alert fires

### Mitigation

**Priority Queues**:
- Separate topics for small groups (< 50) vs large groups (> 50)
- Fan-Out Service has more consumers for small group topic (lower latency)
- Large group fan-out is naturally slower (acceptable for announcement channels)

**Lazy Fan-Out for Very Large Groups**:
- Groups > 500 members: don't publish per-user, publish to a conversation-level Redis channel
- WS servers subscribed to conversation channels handle local delivery
- Reduces Fan-Out's work from O(members) to O(WS servers with members) — typically 10–50× reduction

**Backpressure with Kubernetes HPA**:
- HPA scales Fan-Out pods based on Kafka consumer lag
- Scale-out triggered when lag > 1,000 messages
- Scale-in when lag < 100 messages

---

## Failure Scenario 6: Message Duplication (Client Retry)

### Description
Client sends a message, the server writes it to Cassandra but the response is lost in transit (network timeout). Client retries. Message is sent twice.

### Detection
No monitoring needed — idempotency key prevents this.

### Prevention
```
Client sends: SEND_MESSAGE {idempotency_key: "ik-client-uuid-001", content: "Hello"}

Message Service:
  1. Check Redis: GET msg:idem:ik-client-uuid-001
  2. If key exists → return cached response (original message_id, seq_num)
  3. If not exists → proceed with insert, then SET msg:idem:ik-client-uuid-001 {msg_id, seq_num} EX 86400
```

### Cassandra-Level Deduplication

Even if Redis is unavailable (idempotency key lost), the `idempotency_key` is stored in the message record. Cassandra's Lightweight Transaction (CAS) can detect exact duplicate:
```sql
INSERT INTO messages (..., idempotency_key)
VALUES (...)
IF NOT EXISTS;
```
LWT has ~5× write latency — used as fallback only.

---

## Failure Scenario 7: Split-Brain WebSocket Routing

### Description
A user has two devices. One device reconnects and the Connection Registry is updated to a new WS server. Meanwhile, the old server still has the old connection open and receives a fan-out message — it tries to push to a ghost connection.

### How It Happens
1. Bob's phone (device A) is on WS Server 1
2. Network glitch → connection drops → Server 1 doesn't know yet (heartbeat timeout not reached)
3. Bob's phone reconnects to WS Server 2
4. Connection Registry updated: Bob:deviceA → Server 2
5. Fan-Out publishes to Server 2 — correct delivery
6. Old Server 1 timeout fires → tries to deliver to closed connection → fails gracefully

### Impact
Transient delivery attempt to a dead connection. TCP close propagates quickly (<< 1 second). Server 1 catches the failed write, marks connection as closed, removes from local state.

### Mitigation
- WS Servers validate connection state before pushing
- Heartbeat timeout (90 seconds) ensures stale connections are cleaned up
- Connection Registry has TTL per device — new registration overwrites old

---

## Failure Scenario 8: Elasticsearch Failure (Search Unavailable)

### Description
Elasticsearch cluster becomes unavailable or severely degraded.

### Impact
- Message search returns errors
- All other features (real-time messaging, history, presence) are completely unaffected

### Mitigation
- Circuit breaker around Elasticsearch client (Resilience4j)
- On circuit open: return `503 Service Unavailable` for search requests with user-visible message "Search temporarily unavailable"
- Indexing consumers: stop committing Kafka offsets → messages queue up in Kafka
- On Elasticsearch recovery: Kafka consumer resumes from last committed offset → re-indexes missed messages

**No data loss**: Kafka retention of 7 days means up to 7 days of un-indexed messages can be re-indexed after recovery.

---

## Failure Scenario 9: Complete Datacenter Outage (Disaster Recovery)

### Description
All services in US-East go down (power failure, major cloud incident).

### Recovery Strategy (Multi-Region Active-Active)

```
Normal:     US-East (primary write region for US users)
Failover:   EU-West or US-West acts as standby

Step 1:     DNS failover → route US users to US-West (< 30 seconds with Route53)
Step 2:     Cassandra multi-region replication means US-West already has all messages
            (eventual consistency lag: < 1 second)
Step 3:     WebSocket servers in US-West accept connections
Step 4:     Connection Registry re-populated as users reconnect
Step 5:     Kafka MirrorMaker 2 was replicating events → Fan-Out in US-West catches up

RTO: < 5 minutes (DNS TTL + client reconnect)
RPO: < 1 second (Cassandra replication lag)
```

### Data Loss Estimation
- Messages written to US-East Cassandra in the last < 1 second before outage may not yet be replicated to US-West
- Affected users: those who sent messages in the final milliseconds before outage
- Mitigation: Cassandra QUORUM_EACH write (requires ACK from US-East AND US-West) — eliminates data loss but adds ~100ms latency penalty on every write (tradeoff decision)

---

## Failure Recovery Runbooks Summary

| Failure | Detection | Auto-Recovery | Manual Action |
|---------|----------|--------------|--------------|
| WS Server crash | K8s liveness probe | Pod restart, client reconnect | None |
| Cassandra node down | Gossip protocol alert | Hinted handoff, RF=3 | nodetool repair on recovery |
| Redis shard failover | Sentinel/Cluster alert | Replica promotion | Monitor unread count sync |
| Kafka broker failure | Kafka controller alert | Partition leader election | Verify consumer lag recovery |
| Fan-Out overload | Consumer lag alert | HPA scale-out | Check for hot topic pattern |
| Elasticsearch down | Health check alert | Circuit breaker | Re-index from Kafka on recovery |
| Full datacenter outage | PagerDuty | DNS failover | Verify Cassandra sync, check MirrorMaker lag |

---

## Chaos Engineering Recommendations

Before production launch, run these chaos experiments:

1. **Kill 1 WS Server pod**: Verify client reconnect within 10 seconds, no message loss
2. **Kill 1 Cassandra node**: Verify zero user impact with RF=3
3. **Kill Redis shard master**: Verify < 30 second recovery, presence re-established
4. **Slow Kafka consumer**: Inject artificial consumer lag → verify HPA scales Fan-Out pods
5. **Network partition between regions**: Verify DNS failover, message availability from replicated data
6. **Large group message**: Send to 1,000-member group → measure delivery latency under load
7. **Client retry flood**: Simulate 10K clients retrying simultaneously → verify idempotency key prevents duplicates
