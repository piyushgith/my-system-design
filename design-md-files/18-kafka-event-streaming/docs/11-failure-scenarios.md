# 11 — Failure Scenarios & Recovery: Kafka-like Event Streaming System

---

## Objective

Analyze concrete failure modes — broker crashes, network partitions, disk failures, controller failures, consumer crashes, and corrupt data — with exact recovery procedures, data loss risk analysis, and prevention strategies.

---

## Failure 1: Broker Crash (Leader Partition)

### Scenario
Broker 1 is leader for partition 0. Broker 1 crashes (JVM crash, OOM, power loss).

### Impact Assessment

| Config | Data Loss Risk |
|---|---|
| acks=0, any RF | All unacknowledged messages lost |
| acks=1, RF≥1 | Messages acknowledged to producer but not yet replicated = LOST |
| acks=-1 (all), RF=3, min.isr=2 | No loss — at least 2 ISR members have a copy |
| acks=-1 (all), RF=3, min.isr=3 | No loss — all ISR acked |

### Recovery Sequence

```
T+0s:   Broker 1 crashes
T+0–Xs: Controller detects via heartbeat timeout (zookeeper.session.timeout.ms or broker.session.timeout.ms in KRaft)
T+Xs:   Controller selects new leader from ISR (e.g., Broker 2)
T+Xs:   Controller sends LeaderAndIsrRequest to Broker 2: "You are now leader for P0, epoch=5"
T+Xs:   Broker 2 truncates log to last committed HWM (epoch 4 end)
T+Xs+:  Broker 2 begins serving produce/fetch for P0
T+Xs+:  Clients refresh metadata (triggered by LEADER_NOT_AVAILABLE error)
T+Ys:   Broker 1 restarts, re-registers with controller
T+Ys:   Controller assigns Broker 1 as follower for P0
T+Ys+:  Broker 1 truncates its log to Broker 2's HWM (removes uncommitted writes)
T+Ys+:  Broker 1 fetches from Broker 2, catches up to ISR
```

**RTO:** `controller_socket_timeout + election_time` ≈ 10–30 seconds (configurable).
**RPO:** Zero with acks=all, min.isr=2. Up to ~1 batch with acks=1.

### Leader Epoch Truncation (Data Integrity)
When Broker 1 restarts as follower, it truncates its log to the HWM at the epoch boundary. This removes any messages that were written by Broker 1 as leader but not fully replicated. Prevents divergent data between former leader and new leader.

---

## Failure 2: Network Partition (Split-Brain)

### Scenario
Network partition separates Broker 1 (leader for P0) from controller and Broker 2/3 (ISR). Broker 1 cannot reach controller but can still receive producer requests.

### What Happens

```
Time 0: Partition occurs
        Controller can't reach Broker 1
        Broker 1 can't reach controller/peers

Time 5s: Controller detects: Broker 1's ISR reports all followers stopped fetching
         Controller fences Broker 1 (increments broker epoch to 6)
         Controller elects Broker 2 as new leader (epoch=5 → epoch=6)

Meanwhile: Broker 1 continues accepting acks=1 writes (it doesn't know it's fenced)
           These writes go to Broker 1's log but NOT to any replica

Time 10s: Producers with acks=all start getting KAFKA_STORAGE_ERROR or timeout
          (acks=all waits for ISR that can no longer reach Broker 1)
```

### Why acks=all Prevents Split-Brain Data Loss

With acks=all and min.isr=2:
- Broker 1 (isolated) cannot form a majority ISR — it's alone
- `min.insync.replicas=2` check fails → Broker 1 rejects writes with `NOT_ENOUGH_REPLICAS`
- Producers get error → can retry on new leader (Broker 2) after metadata refresh

With acks=1:
- Broker 1 accepts writes
- After partition heals, Broker 1 truncates to HWM (loses these unacknowledged writes)
- **DATA LOSS** — producers got false success acknowledgments

### Fencing Mechanism
Broker epoch prevents zombie broker from causing damage after network heals:
- Broker 1 has epoch 5; Controller assigned epoch 6 to Broker 2 as new leader
- When Broker 1 tries to replicate or respond to controller after partition heals: controller rejects (epoch 5 < 6)
- Broker 1 is fenced, must re-register, truncate log, become follower

---

## Failure 3: Disk Failure on Broker

### Scenario
Broker's data disk fails (hardware fault, full filesystem, filesystem corruption).

### Types and Impact

| Disk Failure Type | Impact |
|---|---|
| Single disk failure (RAID broker) | Automatic recovery via RAID — no data loss, no broker downtime |
| Single-disk broker with no RAID | Broker stops serving affected partitions → leader election for those partitions |
| Complete disk failure (all data lost) | Broker's partitions promoted from ISR replicas; broker restarts as empty node |
| Corruption without failure | CRC32c check on RecordBatch detects corruption — broker logs error, marks partition offline |

### Recovery for Complete Disk Failure
1. Controller detects broker's LEO at 0 after restart
2. Broker fetches full partition log from leader replica
3. Full log transfer at replication throttle rate (to avoid starving producers/consumers)
4. After caught up: re-added to ISR

**Recovery time depends on data volume:** 1 TB partition at 100 MB/sec throttle = ~2.8 hours to catch up.

**Mitigation:** Multiple log directories (`log.dirs=/data/disk1,/data/disk2`) — each partition assigned to one disk. Disk failure only affects partitions on that disk (reduces blast radius).

---

## Failure 4: Controller Failure (KRaft)

### Scenario
Active controller node crashes. Controller quorum has 3 nodes: C1 (active), C2, C3.

### Recovery Sequence

```
T+0:  C1 crashes
T+0–3s: C2 and C3 detect C1 absence via Raft heartbeat timeout
T+3s: C2 or C3 calls election (Raft term increment)
      Candidate wins if gets majority (2/3) votes
T+5s: C2 elected as new active controller
T+5s+: C2 starts processing pending controller operations
       (any in-flight partition elections from C1's era are re-evaluated)
```

**RTO:** 3–10 seconds for controller failover.
**Impact during outage:** Brokers continue serving data; no new topics, no partition reassignments, no leader elections.

### Broker Impact During Controller Outage
- All **data plane** operations (produce, fetch, offset commit) continue normally
- All **control plane** operations fail (topic creation, partition reassignment)
- If a broker fails during controller outage: no leader election until controller recovers
  - Affected partitions become unavailable (leader lost, no new leader elected)

---

## Failure 5: Consumer Group Crash (All Members)

### Scenario
All consumers in a group crash simultaneously (rolling deploy gone wrong, cluster-wide OOM).

### Impact
- No new offsets committed after crash
- Messages continue to accumulate in partitions (Kafka persists regardless)
- No message loss in Kafka

### Recovery
1. Consumers restart → JoinGroup requests
2. New consumer group formed (or existing group resumed via static membership)
3. Consumers resume from last committed offset
4. All messages accumulated during downtime are processed (in order per partition)
5. Processing lag spikes → consumer lag alert fires → scale up consumers

**At-least-once semantics during recovery:**
If consumer crashed after processing but before committing offset → those records re-delivered on restart → consumer must be idempotent.

### Zombie Consumer Problem

```
Timeline:
T+0:  Consumer C1 fetches records, starts long processing (5 min)
T+3m: C1's heartbeat interval lapses (session.timeout.ms = 3min)
T+3m: Group coordinator triggers rebalance, assigns C1's partitions to C2
T+3m: C2 fetches and processes same partitions
T+5m: C1 finishes processing, tries to commit offset 500
T+5m: Coordinator rejects commit (generationId mismatch — C1 is from old generation)
```

**Fix:** `max.poll.interval.ms` controls max processing time per poll. If exceeded → consumer fenced. Choose `max.poll.interval.ms` > worst-case processing time. Or process asynchronously and commit after.

---

## Failure 6: Corrupt Message in Log

### Scenario
Bit flip or partial write results in corrupted RecordBatch in a segment file.

### Detection
Every RecordBatch contains `crc32c` checksum. On read:
- Broker verifies CRC before serving to consumer
- If CRC fails → `CORRUPT_MESSAGE` error returned to consumer
- Kafka logs: `WARN kafka.log.LogValidator - Found invalid messages in magic ... crc check failed`

### Recovery Options

| Option | Procedure | Use Case |
|---|---|---|
| Replica recovery | Delete corrupted partition replica, let it re-sync from leader | Follower corruption |
| Truncation | Truncate log to last valid offset before corruption | Leader corruption at end of log |
| Skip corrupt records | Tool: `kafka-dump-log.sh --verify-index-only` identifies offsets; consumer set to skip | Corruption in middle of log (last resort) |
| Topic deletion/restore | Delete topic, re-populate from backup | Widespread corruption |

**Key design property:** Corruption on one replica does NOT affect leader. CRC check prevents corrupt data from being served to consumers or replicated further.

---

## Failure 7: Schema Registry Unavailability

### Scenario
Schema Registry goes down for 5 minutes.

### Impact
- **Producers:** Cannot register new schemas. Cannot validate new schema versions. Existing producers with cached schema IDs → **unaffected** (use cached IDs)
- **Consumers:** Cannot fetch schemas for IDs they haven't seen before. Consumers that have already cached all relevant schemas → **unaffected**
- **New consumer deployments:** Will fail to start (schema fetch fails on startup)

### Recovery
1. Schema Registry restarts, loads all schemas from PostgreSQL
2. In-memory cache rebuilt on startup (< 1 sec for most deployments)
3. New consumers retry schema fetch → succeed
4. Zero data loss in Kafka itself

**Mitigation:** Run Schema Registry with multiple instances behind load balancer. All state in PostgreSQL — any instance can serve any request. Failover transparent to clients.

---

## Failure 8: ISR Shrinkage Under Load

### Scenario
Followers fall behind leader under sustained high write load. ISR shrinks to just the leader.

```
Normal: ISR = [Broker1(leader), Broker2, Broker3]
Under load: Broker2 and Broker3 lag > replica.lag.time.max.ms
            Controller removes them from ISR
After: ISR = [Broker1(leader)]

If min.insync.replicas = 2: WRITES FAIL (not enough ISR for acks=all)
If min.insync.replicas = 1: writes succeed but durability degraded
```

### Root Causes
- Follower disk I/O slower than leader write rate
- Follower GC pause causing fetch delay
- Network congestion between leader and follower
- Follower CPU saturated (other workloads on same host)

### Recovery
1. Reduce write throughput (producer rate limiting)
2. Add more brokers to redistribute partitions
3. Upgrade follower hardware (disk IOPS bottleneck)
4. Tune `replica.lag.time.max.ms` (increase tolerance) — but increases data loss window

---

## Disaster Recovery

### RTO and RPO Targets

| Tier | Scenario | RTO Target | RPO Target |
|---|---|---|---|
| Single broker failure | Common | < 30s | 0 (acks=all) |
| Datacenter AZ failure | Uncommon | < 60s | 0 (cross-AZ replicas) |
| Full cluster failure | Rare | < 4 hours | Last remote backup |
| Region failure | Catastrophic | < 24 hours | MirrorMaker lag |

### Cross-Datacenter Replication (MirrorMaker 2)

```
Cluster A (Primary) → MirrorMaker 2 → Cluster B (DR)
                                        ↑ same messages, slightly lagged

MirrorMaker 2 features:
- Consumer offsets translated (topic names differ: "orders" → "A.orders")
- Partition count preserved
- Offset sync: consumer groups can failover with ~same position
- Heartbeats: validate replication is healthy
```

**RPO with MirrorMaker:** Typically 1–10 seconds lag depending on throughput. Not zero.

### Backup Strategy
- Tiered storage (S3): effectively continuous backup of closed segments
- Point-in-time recovery: restore specific segments from S3 at any offset
- Topic export: `kafka-dump-log.sh` or MirrorMaker2 to another cluster

---

## Failure Detection Tuning

| Parameter | Default | Tradeoff |
|---|---|---|
| `zookeeper.session.timeout.ms` | 18s | Lower = faster detection; higher = fewer false positives on GC pauses |
| `replica.lag.time.max.ms` | 30s | Lower = faster ISR shrink; higher = more tolerance for slow followers |
| `session.timeout.ms` (consumer) | 45s | Lower = faster rebalance on crash; higher = fewer spurious rebalances |
| `heartbeat.interval.ms` | 3s | Must be < session.timeout.ms / 3 |
| `request.timeout.ms` | 30s | Client-side timeout for broker responses |

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| ISR-based replication vs 2PC | No blocking transactions; async replication | Data loss window with acks=1 |
| min.insync.replicas=2 | Prevents writes during ISR shrink | Availability sacrifice when 1 replica down |
| Leader epoch truncation | Prevents split-brain data divergence | Log truncation discards acknowledged writes (acks=1 only) |
| MirrorMaker DR | Active DR without shared infrastructure | Eventual consistency; offset translation required |

---

## Interview Discussion Points

- **Can Kafka lose data with acks=all?** Yes — if all ISR members crash simultaneously before the HWM advances. With RF=3 and min.isr=2, you'd need 2 simultaneous crashes AND the 3rd replica not having caught up. Extremely rare but possible
- **What is unclean leader election and when is it dangerous?** `unclean.leader.election.enable=true` allows electing a replica outside ISR as leader. Risks: this replica may be behind the old leader → data loss on leadership transition. Default: false. Use only if availability > durability
- **How does Kafka handle a GC pause on the leader?** Long GC pause (>30s) causes followers to appear "unresponsive" from leader's view. Followers can't fetch → ISR shrinks → acks=all writes stall. GC tuning (G1GC, ZGC) and small heap (6 GB) are essential for leadership stability
- **What happens if both leader and all replicas are in different AZs and one AZ fails?** With rack-aware replica placement: leader in AZ-A, replicas in AZ-B and AZ-C. AZ-A failure → controller elects AZ-B or AZ-C replica as leader → zero data loss with acks=all
- **Why is `min.insync.replicas=1` dangerous with `acks=all`?** With min.isr=1 and RF=3, acks=all only requires the leader to ack. If leader crashes before any follower fetches, the data is gone — same as acks=1 effectively. min.isr=2 or min.isr=3 gives the actual durability guarantee
