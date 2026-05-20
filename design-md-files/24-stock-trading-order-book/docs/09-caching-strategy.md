# 09 — Caching Strategy: Stock Trading Order Book

## Objective

Define caching layers for latency-critical paths (risk check, market data), read performance (order status), and static data (instruments). Distinguish what should and should not be cached.

---

## What NOT to Cache

| Data | Why Not Cached |
|------|---------------|
| Order book state | Lives in matching engine memory — no external cache is authoritative |
| Trade execution results | Must be persisted first; cache adds no value |
| Risk reserves (buying power) | Redis IS the authoritative store, not a cache |
| Audit log | Append-only, never read-through |
| Settlement instructions | Batch processing, no latency requirement |

The most important caching decision in this system is what NOT to cache. The order book must live exclusively in the matching engine — any external cache is immediately stale.

---

## Cache Layer 1: Risk Engine (Redis — Authoritative Store)

**Not technically a cache** — Redis is the primary store for pre-trade risk data.

### Data Stored

```
buying_power:{participantId}        → DECIMAL (cash available for buying)
position:{participantId}:{symbol}   → INTEGER (current net position in shares)
reserved_cash:{participantId}       → DECIMAL (held against open buy orders)
reserved_qty:{participantId}:{symbol} → INTEGER (held against open sell orders)
```

### Access Pattern

Every new order triggers:
1. `GET buying_power:{participantId}` → check sufficient funds
2. `DECRBY buying_power:{participantId} {amount}` → atomic reserve
3. If DECRBY result < 0: undo with `INCRBY`, reject order

Total: 2 Redis round-trips = ~0.2ms at p99.

### Consistency

- Redis primary handles all writes
- Read replicas serve position queries (slightly stale, acceptable for read-only risk review)
- On `TradeExecuted`: Kafka consumer updates position in Redis (eventual consistency, 1-2 second lag)
- On `OrderCancelled`: immediate release of reserved amounts

### Failure Mode

Redis unavailable → fail-closed: all orders rejected until Redis recovers. This is intentional — selling without risk check risks financial loss.

---

## Cache Layer 2: Market Data (Redis Pub/Sub + In-Memory)

### Level 1 Quote Cache (Redis Hash)

```
quote:{symbol}:bid_price  → "180.48"
quote:{symbol}:bid_qty    → "500"
quote:{symbol}:ask_price  → "180.52"
quote:{symbol}:ask_qty    → "300"
quote:{symbol}:last_price → "180.50"
quote:{symbol}:seq        → "10023457"
```

Updated by Market Data Service on every `OrderBookUpdated` event. Subscribers (WebSocket servers) read this on client connect (initial snapshot), then switch to Pub/Sub for real-time updates.

**TTL:** None (always overwritten, never expires) — stale data is worse with TTL because clients would see blank quotes vs slightly stale quotes.

### Order Book Depth Cache (Redis Hash)

```
depth:{symbol}:bids  → JSON array of top 10 price levels
depth:{symbol}:asks  → JSON array of top 10 price levels
depth:{symbol}:seq   → sequence number
```

Updated on every book change. Clients request initial depth via REST (reads from Redis), then receive incremental updates via WebSocket.

**Update frequency:** can be capped at 100 updates/sec per symbol to prevent overwhelming downstream even during high-volatility moments.

### Pub/Sub Channels

```
market-data:{symbol}        → Level 1 updates
market-data:{symbol}:depth  → Level 2 incremental updates
market-data:{symbol}:trades → Trade ticker
```

WebSocket server pods subscribe to all channels. On update: push to all connected clients subscribed to that symbol.

---

## Cache Layer 3: Static Reference Data (In-Memory per Pod)

Data that changes rarely (instrument config, trading schedule) cached locally in each application pod.

### Instruments Cache

```java
// Caffeine cache, local to each pod
instruments: Map<Symbol, Instrument>
TTL: 5 minutes
Invalidation: "instrument-updated" Kafka event triggers cache eviction
Size: 500 entries — trivially small
```

### Symbol → Matching Pod Routing Map

```
routing:{symbol} → pod_identifier (Redis)
TTL: None
Invalidation: Explicit update when matching pod assignment changes
```

All Order Gateway pods read from Redis on startup and cache locally. Refresh every 30 seconds or on cache miss.

---

## Cache Layer 4: Client Session (API Gateway)

### JWT Validation Cache

JWT tokens validated on every request. Validation involves:
1. Check token expiry (local — no network)
2. Check token signature (local — no network)
3. Check token revocation (optional — depends on security policy)

If revocation list is maintained (for logout / compromised tokens):
```
revoked_tokens:{jti}  → "1" (TTL = token expiry time)
```

Checked on every authenticated request. Redis read = 0.1ms.

### Rate Limit Counter

```
rate_limit:{participantId}:{endpoint}:{window}  → counter (INCR)
TTL: window duration (1 second for order submission)
```

Sliding window implementation:
- `INCR` on each request
- `EXPIREAT` set to end of current window
- Check counter before processing

---

## Cache Layer 5: Idempotency (Redis)

### Order Idempotency Cache

```
idem:{participantId}:{clientOrderId}  → {orderId}:{status}
TTL: 24 hours
```

On new order:
1. `GET idem:{participantId}:{clientOrderId}` — if exists, return cached response
2. If not exists: process order, then `SET idem:... {result} EX 86400`

`SETNX` (SET if Not eXists) used to atomically set first-time and detect duplicates. Race condition handled: two concurrent requests with same clientOrderId — SETNX ensures only one processes.

---

## Caching Anti-Patterns to Avoid

| Anti-Pattern | Why It's Wrong Here |
|-------------|---------------------|
| Cache order book in Redis | Redis copy is immediately stale after any match |
| Cache participant positions for risk check | Risk uses Redis as primary — "caching" positions would mean double-truth |
| Use local cache for risk data | Multiple gateway pods would have inconsistent views; overselling possible |
| Cache trade history in Redis | PostgreSQL read replicas serve this fine; Redis cache adds complexity |
| Aggressive TTLs on market data | Stale quote is misleading; better to serve fresh or nothing |

---

## Cache Warming Strategy

| Cache | Warming Approach | Timing |
|-------|-----------------|--------|
| Instrument config | Loaded on service startup from DB | Before accepting traffic |
| Symbol routing map | Loaded on startup from Redis | Before accepting traffic |
| Buying power / positions | Pre-populated from Participant DB at market open | During pre-market (8:00-9:30 AM) |
| Level 1 quotes | Populated from first tick after market open | Automatic |
| Level 2 depth | Populated from first book event | Automatic |

**Market open sequence:**
1. 8:00 AM: Pre-trade risk data loaded into Redis from authoritative DB
2. 9:25 AM: Matching engines accept orders but don't execute (pre-open session)
3. 9:30 AM: Market opens — first matches executed, market data caches populated
