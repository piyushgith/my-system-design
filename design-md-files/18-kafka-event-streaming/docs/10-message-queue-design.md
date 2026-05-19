# 10 — Message Queue Design: Kafka-like Event Streaming System

---

## Objective

Define the internal queue/buffer design of the broker, covering request queues, producer batching buffers, replica fetch queues, and how backpressure propagates through the system. Also compare with traditional message queue architectures and when to choose each.

---

## Broker Internal Request Processing Architecture

```
                    ┌─────────────────────────────────────┐
                    │           Network Threads             │
                    │  Acceptor → 3 Processors             │
                    │  (non-blocking NIO, epoll)            │
                    └───────────────┬─────────────────────┘
                                    │ Request objects
                                    ▼
                    ┌─────────────────────────────────────┐
                    │        Request Queue                  │
                    │  (LinkedBlockingQueue, per listener)  │
                    │  Capacity: queued.max.requests=500   │
                    └───────────────┬─────────────────────┘
                                    │
                    ┌───────────────▼─────────────────────┐
                    │        Request Handler Pool           │
                    │  (num.io.threads=8 by default)        │
                    │  KafkaRequestHandler threads          │
                    └───────────────┬─────────────────────┘
                                    │
               ┌────────────────────┼────────────────────┐
               ▼                    ▼                     ▼
     ┌──────────────┐   ┌──────────────────┐   ┌───────────────────┐
     │  Log Manager  │   │  Group           │   │  ReplicaManager   │
     │  (Storage)    │   │  Coordinator     │   │  (Replication)    │
     └──────────────┘   └──────────────────┘   └───────────────────┘
               │
               ▼
     ┌──────────────────┐
     │  Purgatory        │
     │  (Delayed Ops)    │
     │  - Fetch wait     │
     │  - Produce ack    │
     └──────────────────┘
               │
               ▼
     ┌──────────────────┐
     │  Response Queue   │
     │  → back to        │
     │  Network Thread   │
     └──────────────────┘
```

---

## Request Queue

**Purpose:** Decouple network I/O threads from business logic threads.

**Implementation:** `LinkedBlockingQueue<Request>` per listener.

**Key Parameters:**

| Parameter | Default | Effect |
|---|---|---|
| `queued.max.requests` | 500 | Max outstanding requests before blocking network threads |
| `num.network.threads` | 3 | Network I/O threads (acceptor + processors) |
| `num.io.threads` | 8 | Request handler threads consuming from queue |

**Backpressure:** When queue reaches `queued.max.requests`, network threads block on enqueue → TCP receive buffer fills → TCP window shrinks → producer backs off naturally. This is OS-level backpressure — no application-level flow control needed.

---

## Purgatory (Delayed Operation Queue)

The most clever piece of broker queue design — handles async waiting without blocking threads.

**What requires delayed operations?**
- `acks=all`: must wait until all ISR replicas have fetched before responding to producer
- Consumer `fetch.min.bytes > 0`: must wait until enough data available
- Consumer `fetch.max.wait.ms`: timer-based wake-up if data never arrives

**Design:**

```
DelayedOperationPurgatory<T extends DelayedOperation> {
  // Two wake-up mechanisms in parallel:
  
  1. Timer Wheel: WheelTimer checks expired operations every 200ms
     - If fetch.max.wait.ms elapsed → complete operation (return empty response)
  
  2. External trigger (watchers map):
     - Key: TopicPartition
     - Value: List<DelayedFetch or DelayedProduce>
     - When log appended (ISR ack received): trigger all watching Delayed ops for that partition
}
```

**Why Timer Wheel over naive `Thread.sleep`?**
- O(1) insertion and deletion (vs O(log n) for `PriorityQueue`)
- Single timer thread serves all delayed operations
- No thread-per-request needed — scales to 100K concurrent delayed operations
- Hierarchical wheel: coarse-grained level for far-future timers, fine-grained for near-term

**Flow for acks=all produce:**
1. Leader appends to log
2. Creates `DelayedProduce`, adds to Purgatory watching partition P
3. Returns to request thread (non-blocking)
4. When follower fetches (advances HWM) → wake DelayedProduce
5. If all ISR acked → complete → enqueue to response queue
6. If timeout expires → complete with timeout error

---

## Producer Client Batching Buffer

### RecordAccumulator

On the producer client side, messages are batched before sending:

```
RecordAccumulator {
  // Per (topicPartition, batch) buffer
  batches: Map<TopicPartition, Deque<RecordBatch>>
  
  // Total memory budget
  free: BufferPool (buffer.memory = 32MB default)
}
```

**Batch Fill Policy:**
- Fill until `batch.size` reached (default 16 KB) — immediate send
- OR `linger.ms` elapsed (default 0ms) — send partial batch
- `linger.ms > 0` enables opportunistic batching for higher throughput

**Sender Thread (background):**
```
Sender {
  loop:
    ready = accumulator.ready()  // partitions with full/timed-out batches
    batches = accumulator.drain(ready, maxSize=maxRequestSize)
    send(batches)  // async, callbacks on completion
}
```

**Buffer Exhaustion Backpressure:**
1. All 32 MB buffer.memory used
2. `send()` call blocks for `max.block.ms` (default 60s)
3. If still no space → throws `TimeoutException`
4. Application code backs off

---

## Consumer Fetch Queue

### Fetch Session (Incremental Fetch, KIP-227)

Traditional fetch: consumer sends full partition list every request → O(partitions) metadata overhead.
Incremental fetch: consumer registers interest once, subsequent requests only include deltas.

```
FetchSession {
  sessionId: int
  sessionEpoch: int
  partitionStates: Map<TopicPartition, FetchPartitionState>
    FetchPartitionState {
      fetchOffset: long
      partitionMaxBytes: int
      currentLeaderEpoch: int
    }
}
```

**Benefits:** For a consumer tracking 1000 partitions, baseline fetch request shrinks from ~30 KB to ~100 bytes (delta only).

### Consumer Internal Fetch Buffer

```
Consumer {
  fetchBuffer: Map<TopicPartition, List<ConsumerRecord>>
  
  poll(timeout) {
    if fetchBuffer not empty:
      return fetchBuffer.drain()
    
    fetch from broker (blocking up to timeout)
    fill fetchBuffer
    return fetchBuffer.drain(max.poll.records)
  }
}
```

**`max.poll.records`** (default 500): limits records returned per poll() call regardless of how much data is fetched. Prevents single poll() from taking too long (which would cause heartbeat timeout).

---

## Dead Letter Queue Pattern

Kafka doesn't have native DLQ — it's a pattern built on top:

```
Topic: orders (main)
Topic: orders.retry.1 (first retry, delay 1min)
Topic: orders.retry.2 (second retry, delay 5min)
Topic: orders.dlq   (dead letter — failed after N retries)
```

**Consumer retry logic:**
```
try {
  process(record)
  commit(offset)
} catch (RetriableException e) {
  if attempts < MAX_RETRIES:
    produce to retry topic (with attempt count in header)
  else:
    produce to DLQ (with error info in headers)
  commit(offset)  // ALWAYS commit — avoid reprocessing
}
```

**DLQ Headers (standard):**
```
x-original-topic: orders
x-original-partition: 3
x-original-offset: 12345
x-exception-message: NullPointerException at OrderProcessor:45
x-first-failure-time: 2024-01-15T10:30:00Z
x-retry-count: 3
```

**Why separate retry topics vs same topic with exponential backoff?**
- Same topic: consumer must re-read all messages, not just retried ones → high latency
- Separate retry topics: dedicated consumer per retry tier, precise timing control, no cross-contamination with healthy messages

---

## Comparison: Kafka vs Traditional Message Queues

| Dimension | Kafka (Log-based) | RabbitMQ (AMQP) | SQS (Cloud MQ) |
|---|---|---|---|
| Message retention | Configurable (time/size) | Deleted on consumption | Up to 14 days |
| Replay | Yes — seek to any offset | No | No (once consumed = gone) |
| Ordering | Per-partition strict | Per-queue approximate | Per MessageGroupId (FIFO) |
| Consumer model | Pull | Push (with credit) | Pull |
| Throughput | 1M+ msg/sec | ~50K msg/sec | Unlimited (SaaS) |
| Consumer parallelism | Bounded by partition count | Competing consumers (any) | Bounded by queue |
| Delivery semantics | At-least-once; exactly-once via txns | At-least-once; at-most-once | At-least-once; exactly-once (FIFO) |
| Use case | Event streaming, audit log, CDC | Task queues, RPC | Serverless, cloud-native tasks |

---

## When NOT to Use Kafka-style Streaming

| Scenario | Better Alternative | Why |
|---|---|---|
| Simple task queue (process-and-forget) | RabbitMQ, SQS | Kafka overhead (partitions, consumer groups) unwarranted |
| Low message volume (< 1000/sec) | SQS, Redis Pub/Sub | Kafka operational complexity not justified |
| Request-reply RPC | gRPC, REST | Kafka is one-way; correlating request/response is complex |
| Short-lived messages (sub-second expiry) | Redis Pub/Sub | Kafka persists even ephemeral data |
| Priority queues | RabbitMQ (per-message priority) | Kafka has no native message priority per partition |
| Per-message TTL (vary by message) | SQS, RabbitMQ | Kafka retention is topic/partition-level only |

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Single request queue per listener | Simple, FIFO, bounded | Priority requests (admin) don't preempt heavy consumer fetches |
| Timer wheel for purgatory | O(1) insert/expire at scale | Implementation complexity vs simpler ScheduledExecutorService |
| Client-side batching | Amortizes per-message overhead | Adds linger.ms latency for low-volume producers |
| Incremental fetch sessions | Reduces metadata overhead | Session management complexity on broker; session invalidation on rebalance |

---

## Interview Discussion Points

- **What happens when the request queue is full?** Network threads block on enqueue → TCP buffers fill → TCP flow control kicks in → producer blocks on send() → application naturally slows down. This is the backpressure chain
- **Why use a timer wheel instead of `PriorityQueue` for purgatory?** O(1) insert/delete in timer wheel vs O(log n) in priority queue. At 100K concurrent delayed operations, O(1) is critical for broker stability
- **What is the difference between `linger.ms=0` and `linger.ms=5`?** `0` sends each batch as soon as one record is added (minimum latency). `5` waits 5ms to accumulate more records per batch (better throughput, slightly higher latency). Recommended: 5–50ms for throughput-oriented workloads
- **Why does Kafka not support per-message priority?** Strict ordering within partition is incompatible with priority. To simulate: use separate topics per priority tier, consumer reads high-priority topic first
- **How does DLQ replay work?** Monitor DLQ topic → when ready to replay, re-produce messages to original topic with retry context cleared. Alternatively: dedicated DLQ consumer that re-processes or routes to original consumer pipeline
