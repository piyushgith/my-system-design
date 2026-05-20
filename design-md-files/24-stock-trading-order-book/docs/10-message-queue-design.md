# 10 — Message Queue Design: Stock Trading Order Book

## Objective

Define Kafka topic architecture, the LMAX Disruptor ring buffer (internal hot path), message schemas, consumer group strategy, and delivery guarantees. These are two distinct queuing concerns: Disruptor for sub-millisecond intra-process handoff, Kafka for durable inter-service communication.

---

## Two Queue Systems

| System | Role | Latency | Durability |
|--------|------|---------|-----------|
| LMAX Disruptor (ring buffer) | Order Gateway → Matching Engine handoff | < 0.01ms | Not durable (in-memory) |
| Apache Kafka | Matching Engine → Downstream services | 5-50ms | Durable (replicated) |

These solve different problems. Disruptor eliminates lock contention on the hot path. Kafka provides durable, replayable event log for downstream consumers.

---

## Part 1: LMAX Disruptor Ring Buffer

### Why Disruptor?

Traditional `BlockingQueue` uses `synchronized` / `ReentrantLock` + `Condition.await()` — context switches, kernel involvement, cache invalidation. Under high load, lock contention causes latency spikes.

Disruptor uses:
- **Pre-allocated ring buffer**: fixed-size array, no garbage, no dynamic allocation
- **Sequence numbers**: producers claim a slot by atomically incrementing a sequence counter (CAS operation only)
- **Memory barriers**: Java `volatile` write/read or `sun.misc.Unsafe` for ordering without full lock
- **Single consumer per ring buffer**: no consumer coordination needed — consumer reads its own sequence counter

### Ring Buffer per Symbol

Each symbol has its own ring buffer. One producer (Order Gateway thread) publishes to it. One consumer (Matching Engine thread for that symbol) reads from it.

```
Ring Buffer (AAPL)
  Capacity: 65,536 entries (must be power of 2)
  Entry: OrderEvent (pre-allocated, mutated by producer)
  Producer strategy: Single-producer (one gateway thread per symbol — routing is pre-assigned)
  Consumer strategy: Single-consumer (one matcher thread per symbol)
```

### Ring Buffer Capacity Calculation

```
Peak order rate per symbol: 10,000 orders/sec
Matching engine processing rate: 100,000 orders/sec
Safety factor: 10x processing capacity vs arrival rate
Ring buffer capacity: 65,536 (next power-of-2 above 10,000 × 0.001s × 10 = ~100 entries minimum)
```

65,536 entries provides ~6.5 seconds of buffer at peak arrival (10,000/sec). If the ring buffer fills, the producer blocks (back-pressure).

### Back-Pressure

If ring buffer is full (matching engine lagging):
- Producer blocks (spinning wait via `BusySpinWaitStrategy` for lowest latency, or `YieldingWaitStrategy` for CPU friendliness)
- Order Gateway returns 503 to client after wait timeout (100ms)
- Alert: ring buffer utilization > 50% triggers alarm

---

## Part 2: Kafka Architecture

### Topic Inventory

| Topic | Partitions | Replication | Retention | Key |
|-------|-----------|-------------|-----------|-----|
| `order-events` | 50 | 3 | 7 days | symbol |
| `trade-executed` | 50 | 3 | 7 days | symbol |
| `order-book-updates` | 50 | 2 | 1 hour | symbol |
| `execution-reports` | 100 | 3 | 7 days | participantId |
| `risk-updates` | 20 | 3 | 3 days | participantId |
| `circuit-breaker-events` | 1 | 3 | 30 days | symbol |
| `audit-events` | 100 | 3 | 90 days | symbol |

**Why 50 partitions for trade/order topics?**
- 500 symbols / 10 symbols per partition = 50 partitions
- Allows 50 parallel consumer instances per consumer group
- Ordering guaranteed within partition (within symbol)

### Partition Key Decisions

| Topic | Key | Rationale |
|-------|-----|-----------|
| `order-events` | symbol | Symbol ordering required — consumers process per-symbol |
| `trade-executed` | symbol | Symbol ordering for market data and settlement |
| `execution-reports` | participantId | Participant ordering — all reports for one participant arrive in order |
| `audit-events` | symbol | Chronological audit per symbol |

---

### Producer Configuration

Matching engine Kafka producer settings:

```
acks=all                    # All ISR replicas must acknowledge (zero data loss)
enable.idempotence=true     # Exactly-once semantics at producer level
max.in.flight.requests=5    # With idempotence, allows pipelining safely
linger.ms=1                 # Small batch delay for throughput
batch.size=65536            # 64KB batch
compression.type=lz4        # LZ4 for speed + compression
retries=Integer.MAX_VALUE   # Infinite retries (idempotence prevents duplicates)
delivery.timeout.ms=30000   # 30s total delivery timeout
```

`acks=all` adds 5-10ms latency per batch. This is acceptable because Kafka publish is asynchronous — the matching engine does not wait for Kafka acknowledgment before processing the next ring buffer event.

### Consumer Groups

| Consumer Group | Topics | Instances | Offset Reset |
|----------------|--------|-----------|-------------|
| `read-model-builder` | `order-events` | 10 | earliest (for replay) |
| `market-data-service` | `order-book-updates`, `trade-executed` | 5 | latest (stale data useless) |
| `risk-update-service` | `trade-executed`, `order-events` | 5 | earliest |
| `settlement-service` | `trade-executed` | 3 | earliest |
| `audit-service` | all topics | 20 | earliest |
| `notification-service` | `execution-reports` | 10 | earliest |
| `surveillance-service` | `trade-executed`, `order-events` | 5 | earliest |

### Consumer Configuration

```
enable.auto.commit=false         # Manual offset commit
max.poll.records=500             # Process 500 records per poll
isolation.level=read_committed   # Only read committed transactions
auto.offset.reset=earliest       # On new consumer group: start from beginning
session.timeout.ms=30000
heartbeat.interval.ms=3000
max.poll.interval.ms=300000      # Allow up to 5 min for slow batch processing
```

---

### Exactly-Once Semantics

For `trade-executed` → `settlement-service`:
- Settlement is financial — duplicate trade processing = financial loss
- Use Kafka transactional API: producer writes to multiple topics atomically
- Consumer: `isolation.level=read_committed` — never reads uncommitted (in-flight) messages

For market data:
- At-most-once acceptable — stale/missing market data is better than duplicate price updates causing false trades

For audit:
- At-least-once — idempotent writes (deduplication by eventId) handle duplicates
- Never at-most-once — missing audit event is regulatory violation

---

### Dead Letter Queue (DLQ)

DLQ pattern for consumers that fail after retries:

```
order-events-dlq        → failed events from read-model-builder
trade-executed-dlq      → failed events from settlement-service
execution-reports-dlq   → failed events from notification-service
```

DLQ consumer:
1. Alerts on any DLQ message (PagerDuty for settlement DLQ — immediate)
2. Manual review interface for compliance team
3. Replay: after fix deployed, DLQ messages replayed in order

**Market data DLQ:** not used — stale market data is worthless, DLQ adds no value.

---

### Consumer Lag Monitoring

Critical metric: consumer group lag per topic partition.

Alerts:
- `market-data-service` lag > 1,000 messages → market data delivery degraded → PagerDuty
- `settlement-service` lag > 10,000 messages → settlement at risk → PagerDuty
- `audit-service` lag > 100,000 messages → compliance risk → PagerDuty
- `read-model-builder` lag > 50,000 messages → order status queries stale → Alert

Kafka consumer lag exposed as Prometheus metric via Kafka JMX exporter → Grafana dashboard.

---

### Kafka Cluster Sizing

```
Brokers:            6 (3 zones × 2 brokers per zone)
Replication factor: 3 (survives 2 broker failures)
Topic partitions:   ~400 total across all topics
Throughput target:  500 MB/sec ingress + 2 GB/sec egress (fan-out)
Storage:            10 TB per broker (7-day retention × peak daily volume)
```

**Min-ISR:** set to 2 (requires at least 2 replicas in-sync for produce to succeed). This means if 2+ replicas fail simultaneously, producers block rather than risk data loss.
