# 00 — Requirements Analysis: Stock Trading Order Book

## Objective

Define functional and non-functional requirements for a matching engine that accepts buy/sell orders and matches them by price-time priority. System must sustain exchange-grade throughput with deterministic, auditable execution.

---

## Functional Requirements

### Core

| # | Requirement |
|---|-------------|
| F1 | Accept limit, market, stop-limit, IOC (Immediate-Or-Cancel), FOK (Fill-Or-Kill) orders |
| F2 | Match buy/sell orders by price-time priority (best bid meets best ask) |
| F3 | Partial fills — unmatched remainder stays on book for limit orders |
| F4 | Trade execution: generate trade records with fill price, quantity, buyer, seller |
| F5 | Order lifecycle: NEW → PARTIALLY_FILLED → FILLED / CANCELLED / REJECTED |
| F6 | Cancel and modify (replace) open orders |
| F7 | Real-time market data feed: best bid/ask (Level 1), full order book depth (Level 2), trade ticker |
| F8 | Pre-trade risk checks: buying power, position limits, duplicate order detection |
| F9 | Support multiple instruments (symbols) simultaneously |
| F10 | Post-trade: generate execution reports, trade confirmations |

### Secondary

- Circuit breaker: halt trading when price moves beyond threshold in N seconds
- Market open/close scheduling per instrument
- Trading sessions: pre-market, regular, after-hours
- Order expiry: Day, GTC (Good-Till-Cancelled), GTD (Good-Till-Date)

---

## Non-Functional Requirements

| Attribute | Target | Rationale |
|-----------|--------|-----------|
| Matching latency | < 1ms p99 (exchange), < 10ms p99 (retail broker) | Competitive fairness, SLA |
| Throughput | 100,000 orders/sec per symbol, 1M+ across exchange | Peak market open |
| Order book depth | 10,000+ orders per side per symbol | Institutional liquidity |
| Availability | 99.99% during trading hours | Revenue-critical |
| Durability | Zero trade loss — every execution persisted before ack | Regulatory |
| Audit trail | Every order event immutably logged with nanosecond timestamp | FINRA, SEC |
| Market data fan-out | < 5ms from match to downstream consumers | Fairness regulation |
| Recovery | Resume matching from checkpoint after crash within 30s | Trading halt SLA |

---

## Assumptions

- Single-currency exchange (USD) — FX not in scope
- Equities only (no options, futures in MVP)
- No payment/settlement integration in MVP — net positions tracked, settlement external (T+2)
- Regulatory compliance requirements modeled but external auditor integration out of scope
- Co-location / kernel bypass (DPDK) is advanced — baseline uses standard JVM networking

---

## Constraints

- Java JVM with latency-sensitive tuning (GC pause management critical)
- PostgreSQL for persistence (event log, audit) — not on the hot path
- Redis for market data distribution
- Kafka for downstream consumer fan-out (trade reports, risk, analytics)
- No shared mutable state between symbols — each symbol runs isolated matching loop

---

## Scale Estimation

### Order Volume

```
Active symbols:          500 (liquid equities)
Orders per symbol/day:   50,000
Total orders/day:        25,000,000
Peak hour concentration: 40% at open (9:30-10:30 AM)
Peak orders/hour:        10,000,000
Peak orders/sec (avg):   2,778
Burst (10x):             ~28,000 orders/sec
```

### Trade Volume

```
Fill rate:               ~30% of orders result in trades
Trades/day:              7,500,000
Trade records size:      ~500 bytes each
Trade storage/day:       ~3.75 GB
Trade storage/year:      ~1.37 TB
```

### Order Book State

```
Avg open orders/symbol:  500 bids + 500 asks = 1,000 orders
500 symbols:             500,000 open orders in memory
Memory per order:        ~200 bytes (price, qty, side, id, timestamp)
Total in-memory:         ~100 MB — trivially fits in heap
```

### Market Data Fan-out

```
Level 1 updates/sec:     500 symbols × 100 ticks/sec = 50,000 messages/sec
Level 2 updates/sec:     500 symbols × 10 book updates/sec = 5,000 messages/sec
WebSocket subscribers:   10,000 concurrent
Fan-out messages/sec:    50,000 × 10,000 = 500M — requires pub/sub, not direct
```

### Storage

```
Order event log:         ~1 KB/order × 25M/day = 25 GB/day
Compressed archive:      ~5 GB/day
Audit retention:         7 years (regulatory) = ~12.7 TB
```

---

## Read/Write Patterns

| Operation | Pattern | Frequency |
|-----------|---------|-----------|
| New order submission | Write-heavy, latency-critical | Very high |
| Order book query (Level 1) | Read, pre-computed | Very high |
| Order book depth (Level 2) | Read, snapshot | High |
| Trade history query | Read, paginated | Medium |
| Account order history | Read, paginated | Medium |
| Risk check (buying power) | Read + atomic decrement | Every order |
| Cancel order | Write + read | Medium |

---

## Latency Budget (per order)

```
Network (client → gateway):   0.5 ms
Pre-trade risk check:         0.1 ms (Redis lookup)
Matching engine processing:   0.1 ms
Persistence (async write):    0 ms (off critical path)
Trade notification:           0.1 ms (async)
Network (gateway → client):   0.5 ms
---
Total round-trip:             ~1.3 ms p50 | ~5 ms p99
```

---

## Availability Targets

| Window | Target | Tolerance |
|--------|--------|-----------|
| Trading hours | 99.99% (< 53 min downtime/year) | Zero data loss |
| After hours | 99.9% | Degraded OK |
| Planned maintenance | Saturday 2-6 AM | Full window |
| Circuit breaker halt | < 30s detection + halt | Automated |
