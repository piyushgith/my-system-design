# 16 — Advanced Improvements: Stock Trading Order Book

## Objective

Identify advanced capabilities, HFT-grade optimizations, novel order types, and staff-level architectural improvements beyond the V3 baseline. These are areas where a technical interview discussion can differentiate a senior from a staff candidate.

---

## Advanced Matching Features

### Dark Pool / Iceberg Orders

**Iceberg order:** Visible quantity is smaller than actual quantity. When visible portion fills, next tranche becomes visible. Prevents large orders from moving the market.

```
Iceberg: Total 10,000 shares, visible 500
→ Book shows 500 shares at $180
→ First 500 fill, next 500 appears automatically
→ Market never sees full 10,000 intent
```

**Implementation complexity:** Order book must track hidden quantity separately. Sequence number for revealed tranches: each tranche gets a new timestamp (loses priority vs orders that were already in queue).

**Interview angle:** How does iceberg affect price-time priority? Each revealed tranche is treated as a new order — loses time priority. This is a deliberate design choice to prevent the iceberg from unfairly dominating the queue.

### Pegged Orders

Order price is pegged to market (e.g., "buy at best bid - $0.01"). When best bid changes, order price adjusts automatically.

**Challenge:** Matching engine must re-price and re-sort the order on every book update for that symbol. This is expensive — pegged orders can cause N re-pricings per book change.

**Design:** Separate "pegged order tracker" that watches book updates and emits Cancel+Replace events for affected pegged orders. These flow through the normal ring buffer.

### Minimum Quantity / Minimum Fill

Order only executes if the fill is at least N shares. Prevents being partially filled with trivially small amounts.

**Implementation:** During match loop, before committing a match, check if fill quantity >= min_fill_quantity. If not, skip to next order at same price.

---

## HFT-Grade Optimizations

### Co-location and Kernel Bypass

For sub-microsecond latency (HFT clients):

**DPDK (Data Plane Development Kit):**
- Bypasses the Linux kernel's TCP/IP stack
- User-space network I/O — no system calls, no context switches
- Latency: 2-5µs vs 50-100µs for standard Linux TCP
- Requires dedicated NIC and specialized driver
- Operational complexity: significant — separate team to maintain

**RDMA (Remote Direct Memory Access):**
- Client memory mapped directly to exchange memory via InfiniBand
- Effectively zero-copy, zero-kernel-involvement data transfer
- Used by exchanges offering co-location services (NYSE, CME data centers)

**When justified:** Only for exchanges where clients pay $10K+/month for co-location. Retail exchange with 1ms target: not justified.

### Lock-Free Data Structures Beyond Disruptor

**Custom lock-free order book for extreme throughput:**
- Replace TreeMap with custom cache-line-aligned sorted arrays
- Price levels as contiguous memory blocks (CPU prefetcher-friendly)
- Each price level: 64-byte cache line = price (8 bytes) + quantity (8 bytes) + order count (4 bytes) + padding
- Walking the book from best bid/ask = linear scan of contiguous memory (no pointer chasing)

**Throughput improvement:** 2-5x over TreeMap for the hot path. Significant engineering cost.

### Object Pooling (Zero-GC Hot Path)

Pre-allocate a pool of `Order` objects at startup. When order arrives, claim from pool (no new allocation). When order completes (filled/cancelled), return to pool.

```
Object Pool
  Pool size: 1,000,000 pre-allocated Order objects
  Claim: O(1) atomic counter increment
  Return: O(1) atomic counter decrement
  GC pressure: zero (no allocation, no collection)
```

Combined with pre-allocated ring buffer entries: matching engine runs with near-zero GC pressure. ZGC becomes unnecessary (but keep as safety net).

---

## Market Structure Improvements

### Opening and Closing Auction

Instead of continuous matching at market open/close, run a batch auction:

**Opening auction (9:00-9:30 AM):**
1. Accept orders but don't match
2. At 9:30: calculate the single price that maximizes matched volume (uncross price)
3. Execute all matches at that price simultaneously
4. Remaining orders enter continuous trading

**Why auctions?** Price discovery is more efficient — avoids first-second-of-trading volatility from imbalanced order flow. NYSE and NASDAQ both use opening/closing auctions.

**Implementation:** Separate auction mode in matching engine. Linear scan to find uncross price: iterate prices from best bid downward, accumulate cumulative buy/sell volumes, find crossing point.

### Market Wide Circuit Breaker Coordination

Individual symbol circuit breakers are handled locally per matching engine thread. Market-wide halts (e.g., S&P 500 drops 7%) require coordinating all matching engines simultaneously.

**Challenge:** Distributed coordination without a slow consensus protocol.

**Design:**
- Redis `SET market-halt 1` — atomic, O(1)
- Each matching engine thread polls this key every 100ms (or subscribe to Redis keyspace notification)
- On halt detected: immediately stop accepting new matches, publish `MARKET_WIDE_HALT` event
- Response time: 100ms maximum latency for halt propagation

**Why not Zookeeper/etcd for this?** The halt signal is a one-way broadcast, not a consensus decision. Redis is faster and simpler for this use case.

---

## Surveillance and Market Integrity

### Layering Detection

**Layering:** Placing large visible orders to manipulate price, then cancelling them before they fill. Illegal market manipulation.

**Detection algorithm:**
- Track cancel rate per participant: if > 95% of orders are cancelled within 500ms of submission, flag
- Look for patterns: large order on one side appears, price moves, large order cancelled
- Sliding window analysis on order flow using Kafka Streams

**Action:** Alert compliance team, not automatic action (human review required before sanctions).

### Spoofing Detection

Similar to layering — large order placed far from market to mislead other participants, cancelled before price reaches it.

**Detection:** Orders with quantity > 10x typical order size for that participant, cancelled within 100ms.

**Graph analysis:** use Neo4j to build relationship graph of accounts — coordinated spoofing rings often involve multiple accounts controlled by same entity.

---

## Multi-Asset and Derivatives Extension

### Options Order Book

Options add complexity:
- Each option has underlying + expiry + strike + put/call = unique instrument
- 500 underlying equities × 12 monthly expiries × 50 strikes × 2 types = 600,000 instruments
- Cannot run 600,000 matching engine threads — options are lower volume per instrument

**Solution for options:** Group low-volume instruments into shared matching queues with explicit instrument tagging. Only high-volume options (AAPL calls near expiry) get dedicated threads.

### Cross-Asset Spreading

Strategy orders that span multiple instruments (e.g., buy stock + sell call option). Atomic execution across two order books is complex.

**Approaches:**
1. **Legging:** Submit each leg independently — not atomic, leg risk exists
2. **Exchange-defined spreads:** Exchange treats spread as a synthetic instrument with its own order book
3. **Request-for-quote:** Don't use continuous matching; negotiation between parties

This is a staff-level discussion point — most exchange interviews don't go this deep, but knowing it exists demonstrates depth.

---

## Architecture Critique

### Current Weaknesses

| Weakness | Risk | Mitigation |
|----------|------|-----------|
| Single JVM per matching pod | JVM GC pause causes latency spike | ZGC, dedicated GC tuning, Azul Zing |
| Symbol allocation is static | Hot symbol problem at market open | Pre-market analysis and manual rebalancing |
| Redis as risk store | Redis failure = trading halt | Redis Cluster, circuit breaker with cached limits |
| PostgreSQL journal | Write bottleneck at extreme scale | Sharded PostgreSQL or Cassandra migration |
| No true HFT support | Co-location clients get same treatment as retail | DPDK layer for premium clients |

### Scaling Limits

| Component | Hard Limit | Breakdown Point |
|-----------|-----------|----------------|
| Matching thread (single symbol) | ~500K orders/sec | Flash crash on single large-cap |
| PostgreSQL event journal | ~50K rows/sec (with batching) | Sustained market open burst |
| Redis Cluster | ~1M ops/sec per node | 100K participants with active reserves |
| WebSocket server (per pod) | ~10K concurrent connections | Market event-driven connection spike |

### Operational Burdens

- **JVM tuning:** requires deep JVM expertise; GC regression from any code change that increases allocation rate
- **Kafka consumer lag management:** 7+ consumer groups, each with its own lag monitoring and remediation
- **Multi-timezone trading sessions:** if global instruments added, session management becomes complex
- **Regulatory reporting:** CAT reporting (FINRA) requires dedicated operational team and monthly reconciliation

### What a Staff Interviewer Would Challenge

1. **"Your risk check at the gateway — what if two gateways race on the same participant's buying power?"**
   - Answer: Redis `DECRBY` is atomic. Both gateways talk to the same Redis primary. Race condition at Redis level is handled by atomic operation; only one will succeed in decrementing past zero.

2. **"You have JVM GC. Can you guarantee sub-millisecond p999?"**
   - Honest answer: No, not with standard JVM. With Azul Zing (pauseless): yes. With ZGC on Java 21: p999 < 2ms achievable. p999 sub-millisecond requires native code (C++) or purpose-built kernel.

3. **"Your matching engine is stateful. How do you zero-downtime deploy it?"**
   - Answer: You don't (during trading hours). Matching engine deployments happen in the 5.5-hour maintenance window (4 PM – 9:30 AM). This is the same constraint every exchange has. Blue-green deployment at market close.

4. **"What happens if the event journal falls behind by 10 seconds?"**
   - Answer: Matching continues (fail-open for journal). Alert fires immediately. If journal lag > 30 seconds: halt matching (unacceptable data loss risk). If journal catches up: no action needed (events were buffered in-process).
