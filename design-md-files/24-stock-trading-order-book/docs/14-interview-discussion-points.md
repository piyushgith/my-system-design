# 14 — Interview Discussion Points: Stock Trading Order Book

## Objective

Prepare for senior and staff engineer discussions on architecture tradeoffs, scaling limits, and real-world complexity of an exchange-grade matching engine.

---

## Core Concept Questions

### "Walk me through how your matching engine works."

**Strong answer:**
- Each symbol has a dedicated single-threaded matching loop
- Orders arrive via ring buffer (LMAX Disruptor) — lock-free handoff from gateway
- Order book maintained as two sorted structures: bids (TreeMap descending by price), asks (TreeMap ascending by price)
- On new order: walk the book from best opposite-side price, match until filled or no more matches
- Price-time priority: best price wins; ties broken by submission time (FIFO within price level)
- Match result: generate TradeExecuted + OrderFilled events, update book state, all in < 0.1ms

### "Why single-threaded? Why not lock the order book?"

- Locking adds 1-10µs overhead per lock acquisition — kills sub-millisecond latency
- More threads = more lock contention = more latency jitter (unpredictable)
- Single-threaded eliminates all synchronization inside the match loop
- Lock-free ring buffer (Disruptor) handles the only necessary concurrent operation (multi-producer → single-consumer handoff)
- Analogy: a single chef in a kitchen is faster than 3 chefs fighting over the same knife

---

## Tradeoff Questions

### "What breaks first at scale?"

1. **Single-thread CPU ceiling per symbol** — AAPL at flash crash can spike to 500K orders/sec; one thread maxes out
2. **Ring buffer overflow** — if matcher can't keep up, gateway starts blocking → order submission latency spikes
3. **Kafka fan-out throughput** — 500 symbols × high tick rate × 10K subscribers = massive egress
4. **PostgreSQL write throughput** — even with batching, sustained 50K events/sec approaches limits

### "How do you handle a hot symbol problem?"

- Static symbol allocation to pods — AAPL on a dedicated pod, smaller symbols share pods
- No dynamic rebalancing during trading hours (too risky)
- Pre-market analysis of expected volume → allocate accordingly
- Ultimate limit: one symbol, one thread, one machine — this is the hard ceiling

### "What happens if two orders arrive simultaneously for the same symbol?"

- Impossible by design — ring buffer is single-producer per symbol (one gateway thread responsible for AAPL routes all AAPL orders to that ring buffer)
- Symbol router in gateway assigns each symbol to a specific gateway thread
- This is the key insight: the LMAX architecture eliminates the concurrent modification problem by design, not by locking

### "How do you guarantee price-time priority?"

- Submission time captured with nanosecond precision at the gateway
- Time is part of the ring buffer entry — preserved through to the matcher
- Within a price level, orders sorted by submission timestamp
- Clock skew risk: NTP synchronizes gateway clocks to ±1ms; NTP is sufficient because same-gateway orders use monotonic clock (always strictly ordered within one gateway); cross-gateway orders use wall clock (±1ms skew is acceptable — users submitting at exactly the same millisecond is a rounding tie anyway)

---

## Distributed Systems Questions

### "How do you ensure no trade is lost if the matching engine crashes?"

Four-layer answer:
1. Events published to Kafka with `acks=all` — durable on Kafka before consumed
2. Event journal (PostgreSQL) written before match result considered complete
3. Order book snapshots every 5 minutes — recovery base
4. Replay from Kafka from last snapshot sequence — fills the gap

**The key invariant:** a trade is only "committed" once `TradeExecuted` is durably written to both PostgreSQL event journal and Kafka. The matching engine never acknowledges a trade to downstream without this.

### "What's the consistency model? Is it AP or CP?"

- **CP for trading decisions:** Risk check (Redis) is CP — buying power reservation must be atomic and consistent. If Redis is unavailable, system stops rather than risk inconsistency.
- **AP for market data:** Market data feed is AP — stale quotes are acceptable; trading continues
- **CP for event journal:** PostgreSQL write must succeed before trade is finalized

**Nuance:** the entire system doesn't fit neatly into CAP. Different sub-systems have different requirements.

### "How do you handle the FIX gateway and REST gateway producing to the same ring buffer?"

- Each gateway type translates to the same internal `OrderEvent` format
- Multiple producers to the ring buffer are handled by Disruptor's `MultiProducerSequencer`
- Lock-free CAS (compare-and-swap) for slot claiming — no blocking
- Throughput impact: multi-producer adds ~2ns overhead vs single-producer; acceptable

---

## Scaling Questions

### "How would you scale to 10x the current load?"

Current: 500 symbols, 100K orders/sec  
Target: 5,000 symbols, 1M orders/sec

Steps:
1. **More matching pods:** 10x symbols → 10x pods (or larger pods with more threads, one per symbol)
2. **More Kafka partitions:** 500 partitions for 5,000 symbols
3. **Order gateway auto-scaling:** stateless, scales horizontally
4. **PostgreSQL sharding or migration to Cassandra:** 1M events/sec exceeds single PostgreSQL node even with batching — shard by symbol range
5. **Redis Cluster:** more shards for 10x participant load

**Most constrained:** PostgreSQL write throughput. Would need to migrate to either sharded PostgreSQL, TimescaleDB with partitioning, or Apache Cassandra (write-optimized).

### "What's the latency budget and where does it go?"

```
Client → Gateway network:      0.5ms
Gateway validation:            0.1ms
Risk check (Redis):            0.2ms
Ring buffer publish:           0.05ms
Ring buffer to matcher:        0.01ms (cache-line transfer)
Matching:                      0.1ms
Kafka publish (async):         not on critical path
Gateway → Client network:      0.5ms
Total p50:                     ~1.5ms
Total p99:                     ~5ms (GC pauses, network jitter)
```

---

## Senior Engineer Discussion Points

### JVM vs Native for a Matching Engine

- **JVM argument:** ecosystem, developer productivity, battle-tested libraries (QuickFIX/J), GC-free periods with careful allocation discipline, Azul Zing for pauseless
- **Native argument:** C++ matching engines at top-tier exchanges (NYSE, CME); zero GC; kernel bypass (DPDK); sub-10µs latency achievable
- **Decision criteria:** HFT exchange → C++/Rust. Retail exchange (1ms target) → JVM is fine, saves 3-5 months development time

### Why Not Use Redis Sorted Sets as the Order Book?

Common interview mistake — Redis sorted sets seem like a natural fit:
- Each price is a score, members are order IDs
- `ZRANGEBYSCORE` gives orders from best price
- But: one Redis round-trip adds 0.2ms, multiple ops add up quickly
- Cannot perform match atomically without Lua scripts — adds complexity
- In-memory Java TreeMap is 100x faster with zero network overhead
- Redis sorted set is a valid prototype / teaching tool, not production matching engine

### Circuit Breaker Design

- **Level 1 (LULD — Limit Up/Limit Down):** FINRA rule — if stock moves 5% in 5 minutes, pause trading 5 minutes
- **Level 2 (market-wide):** S&P 500 drops 7% → 15 min halt; 13% → 15 min halt; 20% → halt rest of day
- System design: each matching engine thread monitors price movement on every trade; triggers halt without external coordination (no distributed consensus needed — one thread owns one symbol)

---

## Staff Engineer Discussion Points

### Exchange Fairness and Latency Arbitrage

- Co-location: HFT firms pay to place servers physically inside the exchange data center (< 1µs round trip)
- Retail clients connect remotely (1-10ms round trip)
- Is this fair? Regulatory debate — exchanges defend it as "access to infrastructure," not unfair advantage
- System design implication: market data must be distributed simultaneously to all subscribers (no preferential feeds)

### Matching Engine as the Exchange's Core Differentiator

- "Why would a startup build their own matching engine rather than licensing one?"
- Licensed: NASDAQ OMX, SunGard — faster time to market, proven, but $500K-2M/year + vendor lock-in
- Custom: full control, differentiating features (novel order types, custom circuit breakers), no licensing fees at scale
- Interview trap: candidates often jump to "use a third-party" — but for an exchange, the matching engine IS the product

### Regulatory Complexity

- FINRA CAT (Consolidated Audit Trail) — every order event reported within 24 hours with nanosecond timestamp
- SEC Market Access Rule (Rule 15c3-5) — financial risk controls mandatory for every order
- GDPR tension: "right to erasure" vs 7-year trade record retention mandate
- System design: handle this by separating PII (erasable) from trade records (non-erasable, but pseudonymized)

---

## Common Mistakes

| Mistake | Why It's Wrong |
|---------|---------------|
| "Use distributed locking on the order book" | Defeats the purpose — lock contention is the enemy |
| "Store order book in PostgreSQL and query it for matching" | Catastrophically slow — matching needs in-memory, sub-ms access |
| "Use Redis sorted sets as the matching engine" | Network round-trip per operation — not suitable for matching |
| "Scale by running 2 threads per symbol" | Order book cannot be safely modified by 2 threads — data corruption |
| "Cache the order book in Redis as a backup" | Immediate staleness — Redis copy is invalid after first match |
| "Use REST for order submission, poll for fill status" | WebSocket push required — polling is too slow for execution reports |
| "Kafka guarantees ordering globally" | Only within a partition — partitioning strategy must ensure symbol isolation |

---

## "What Would Break First?" Analysis

At 10x current load:

1. **PostgreSQL event journal** (write throughput ceiling) — add sharding or switch to Cassandra
2. **Market data WebSocket server** (10,000 → 100,000 connections) — need distributed WebSocket layer
3. **Kafka consumer throughput** (500 → 5,000 symbols) — add partitions and consumer instances
4. **Order Gateway single point** — already horizontally scaled, but rate limiting per participant needs sharding across Redis nodes
5. **GC pauses** — at very high allocation rates, G1GC pauses can exceed 10ms; ZGC or Azul Zing needed

**Correct answer structure:** identify each bottleneck, quantify when it breaks (what multiple of current load), propose scaling solution.
