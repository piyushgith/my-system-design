# 11 — Failure Scenarios: Stock Trading Order Book

## Objective

Analyze failure modes for each component, define detection strategies, recovery procedures, and the system's behavior during degraded states. Financial exchange failures have regulatory and financial consequences.

---

## Failure Taxonomy

| Severity | Definition | Examples |
|----------|-----------|---------|
| P0 — Trading Halt | Exchange cannot process orders | Matching pod crash, Redis down |
| P1 — Degraded Trading | Orders accepted but something impaired | Market data delayed, slow risk check |
| P2 — Data Quality | Data inconsistency or gap | Read model stale, audit lag |
| P3 — Operational | Background tasks failing | Archival job failed, DLQ growing |

---

## Failure 1: Matching Engine Pod Crash

**Scenario:** JVM OOM, hardware fault, or network partition causes matching pod to terminate. 250 symbols lose their matching engines.

**Detection:** Kubernetes pod readiness probe fails → pod removed from service. Downstream consumers detect gap in sequence numbers. Symbol router detects pod unavailable.

**Impact:** All orders for affected symbols rejected (502) until recovery. Market data for those symbols frozen.

**Recovery procedure:**

1. Kubernetes starts replacement pod immediately (< 30 seconds via readiness probe)
2. Replacement pod:
   a. Loads latest `order_book_snapshots` from PostgreSQL (per symbol)
   b. Replays `order-events` from Kafka from `snapshot.sequenceNumber + 1`
   c. Rebuilds in-memory order book for each symbol
3. Symbol router marks pod as available after health check passes
4. Trading resumes (within 30-second circuit breaker halt window)

**Total downtime:** < 30 seconds with pre-warmed snapshots.

**Risk:** If snapshot is 5 minutes old and 5 minutes of events need replay → replay may take 10-15 seconds. Snapshot frequency tuned to meet recovery SLA.

---

## Failure 2: Redis Unavailable (Risk Engine)

**Scenario:** Redis primary fails. Replica failover takes 15-30 seconds (Redis Sentinel). During failover window, risk checks cannot be processed.

**Detection:** Redis connection refused. Risk Engine catches exception.

**Behavior:** Fail-closed. All order submissions rejected with 503.

**Justification:** The alternative (fail-open, skip risk check) risks accepting orders that exceed buying power — financial liability. Fail-closed is the only safe choice.

**Recovery:** Redis Sentinel promotes replica to primary. Risk Engine reconnects automatically (Spring Data Redis retry). Orders resume accepting after Redis available.

**Mitigation:** Redis Cluster (vs Sentinel) for faster failover. But Cluster adds complexity and split-brain risk. Sentinel with 3 sentinels is simpler and reliable enough for 99.99% uptime.

---

## Failure 3: Kafka Broker Failures

**Scenario:** 1 of 6 Kafka brokers fails. With replication factor 3 and min-ISR 2, remaining 5 brokers can serve all topics.

**Impact:** Zero — transparent to producers and consumers. Kafka handles replica rebalancing automatically.

**Scenario:** 2 brokers in same zone fail simultaneously. With replication factor 3, each partition still has 1 in-sync replica. Production continues.

**Scenario:** 3+ brokers fail (catastrophic). `min.in.sync.replicas=2` cannot be met. Producers block. Trading halts.

**Detection:** Kafka producer gets `NotEnoughReplicasException`. Order Gateway logs error, returns 503.

**Recovery:** Restore failed brokers. Kafka leader election and replica sync. Resume producing. Full recovery: 5-30 minutes depending on broker restart time.

---

## Failure 4: Order Gateway Crash

**Scenario:** One of N order gateway pods crashes.

**Impact:** Clients connected to that pod lose active connections. Orders in-flight (202 sent, not yet published to ring buffer) may be lost.

**Detection:** Load balancer health check fails → pod removed. K8s restarts pod.

**Recovery:** New pod starts (stateless). Clients reconnect and retry orders. `clientOrderId` idempotency ensures retried orders are not double-processed.

**Client requirement:** Client must retry on connection error. Exchange SLA specifies that 202 Accepted means the order was received but not necessarily matched.

---

## Failure 5: PostgreSQL Event Journal Unavailable

**Scenario:** PostgreSQL primary fails. Replica promotion takes 30-60 seconds.

**Impact:** Matching engine cannot write events to journal. Two options:
1. **Halt matching** (fail-closed): stop accepting new orders until journal recovers
2. **Continue matching** (fail-open): buffer events in memory, write when journal recovers

**Decision:** Continue matching with local buffer (fail-open). Rationale: Journal write is already asynchronous — matches have already occurred by the time journal write fails. Stopping matching would halt trading unnecessarily.

**Risk:** If matching pod also crashes during PostgreSQL outage, buffered events are lost. Mitigated by:
- Keep in-memory buffer < 30 seconds of events (small)
- Alert immediately on journal failure
- If journal unavailable > 30 seconds: halt matching (P0 incident)

**Recovery:** PostgreSQL replica promoted. Matching engine flushes buffer to new primary. Operations verify sequence continuity.

---

## Failure 6: Ring Buffer Overflow (Back-Pressure)

**Scenario:** Order submission rate exceeds matching engine processing rate. Ring buffer fills up.

**Detection:** Ring buffer utilization > 80% (Disruptor metric exposed via JMX).

**Impact:** Order Gateway thread blocks on ring buffer publish. If blocking > 100ms, returns 503 to client.

**Causes:**
- Matching engine CPU starved (other processes consuming cores)
- Extremely high order rate for one symbol (market open surge)
- GC pause in matching engine thread

**Mitigation:**
- GC pause management: use Azul Zing (pauseless GC) or tune G1GC
- CPU isolation: matching engine threads pinned to dedicated CPU cores (no sharing)
- Rate limiting at gateway: soft limit at 80% ring buffer capacity

---

## Failure 7: Market Data Delivery Failure

**Scenario:** Market Data Service crashes or Redis Pub/Sub saturated.

**Impact:** WebSocket clients stop receiving updates. Order book display freezes.

**Recovery:** Market Data Service restarts. Clients reconnect WebSocket, request REST snapshot for current state, resume streaming.

**Client behavior:** Client should detect stale market data (no update > 2 seconds) and display warning or request fresh snapshot.

**Market data is best-effort:** unlike orders, missing a market data tick does not cause financial harm. Recovery is self-healing.

---

## Failure 8: Duplicate Order Processing (Split-Brain)

**Scenario:** Network partition between Order Gateway and Matching Engine. Gateway retries publishing to ring buffer. Matching engine processes order twice.

**Prevention:**
- `clientOrderId` unique constraint in idempotency cache (Redis)
- Matching engine checks idempotency before processing: `SET idem:{orderId} EX 3600 NX`
- If already processed: drop duplicate silently

**Why this can happen:** Ring buffer is in-memory on matching engine pod. Gateway publishes via gRPC call to matching pod. If gRPC call times out (network blip), gateway retries. Matching engine may have already received the first attempt.

**Guarantee:** With idempotency check, worst case is one successful process + one silent drop.

---

## Circuit Breaker: Trading Halt

**Scenario:** Price of AAPL moves -10% in under 5 minutes (circuit breaker threshold).

**Trigger:** Matching engine detects price movement on each trade execution.

**Action:**
1. Matching engine immediately stops matching for AAPL
2. Publishes `TradingHalted` event to Kafka
3. All new AAPL orders rejected with 503 + specific halt reason
4. Market data shows HALTED status

**Resume (automatic after 5 minutes):**
1. Circuit breaker timeout expires
2. Pre-open 30-second window: orders accepted but not matched (order book rebuilds depth)
3. Auction-style reopening at single price that maximizes volume
4. Normal continuous trading resumes

---

## Disaster Recovery

| Scenario | RTO | RPO | Strategy |
|----------|-----|-----|---------|
| Single pod failure | < 30s | 0 (events in Kafka) | K8s restart + replay |
| AZ failure | < 5 min | 0 (Kafka multi-AZ) | Failover to remaining AZs |
| Full region failure | < 30 min | < 5 min | Warm standby in DR region |
| Data corruption | < 2 hours | < 1 min | Rebuild from Kafka event replay |

**DR region design:**
- Kafka MirrorMaker 2 replicates all topics to DR region in real-time
- DR matching engines running in cold-standby (reading Kafka but not matching)
- On DR activation: stop MirrorMaker, promote DR matching engines to active
- DNS cutover routes order flow to DR region

**RPO < 5 minutes:** reflects Kafka replication lag across regions (MirrorMaker latency). Zero RPO would require synchronous cross-region write — unacceptable latency.
