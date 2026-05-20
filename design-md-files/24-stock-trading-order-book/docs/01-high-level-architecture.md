# 01 — High-Level Architecture: Stock Trading Order Book

## Objective

Define overall system structure, component responsibilities, and communication patterns. Justify architecture choice for a latency-sensitive, high-throughput matching system.

---

## Architecture Decision: Event-Driven + Single-Writer Per Symbol

### Chosen: Disruptor-Based Single-Writer Architecture (per symbol)

Each trading symbol gets its own dedicated matching loop — a single thread that owns the order book and processes all order events sequentially. No locking needed inside the matching engine because there is no concurrency within one symbol's loop.

Communication between components uses the LMAX Disruptor pattern: a ring buffer that allows multiple producers (order gateways) to hand off to the single consumer (matcher) without locks.

### Why Not Microservices With Shared DB?

- Shared DB serializes writes through lock contention — destroys sub-millisecond latency
- Network hops between services add 0.5–2ms per hop — unacceptable on hot path
- Distributed transactions for order + risk check would require 2PC or Saga — incompatible with microsecond SLA

### Why Not Actor Model (Akka)?

- Actor mailboxes add overhead vs Disruptor's mechanical sympathy (CPU cache-line awareness)
- Akka is appropriate for 10ms-range latency; Disruptor targets sub-millisecond
- Both are valid — Akka is the "less extreme" choice for a 1ms p99 target instead of microseconds

### When NOT to Use This Architecture

- Small exchange or broker (< 10 symbols, < 1000 orders/day) — overkill; Spring Boot + PostgreSQL suffices
- Non-HFT retail platform — standard queue-based approach is maintainable and sufficient
- When operational team lacks JVM tuning expertise — GC pauses will kill latency guarantees

---

## System Components

```mermaid
graph TB
    subgraph Clients
        WC[Web Client<br/>React + WebSocket]
        AC[API Client<br/>REST/FIX]
        MM[Market Maker<br/>FIX Protocol]
    end

    subgraph Gateway Layer
        OG[Order Gateway<br/>Spring Boot]
        MD[Market Data Gateway<br/>WebSocket Server]
        FG[FIX Gateway<br/>QuickFIX/J]
    end

    subgraph Risk Layer
        RC[Pre-Trade Risk<br/>Engine]
        RD[(Redis<br/>Position Cache)]
    end

    subgraph Matching Core
        RB1[Ring Buffer<br/>Symbol: AAPL]
        RB2[Ring Buffer<br/>Symbol: MSFT]
        ME1[Matching Engine<br/>AAPL - Single Thread]
        ME2[Matching Engine<br/>MSFT - Single Thread]
        OBM[(Order Book<br/>In-Memory)]
    end

    subgraph Event Log
        EL[(PostgreSQL<br/>Event Journal)]
        OP[Outbox Publisher]
    end

    subgraph Downstream
        KF[Kafka]
        MD_SVC[Market Data Service]
        RS[Risk Aggregator]
        AN[Analytics / Reporting]
    end

    WC --> OG
    AC --> OG
    MM --> FG
    FG --> OG

    OG --> RC
    RC --> RD
    RC --> RB1
    RC --> RB2

    RB1 --> ME1
    RB2 --> ME2

    ME1 --> OBM
    ME2 --> OBM
    ME1 --> EL
    ME2 --> EL

    EL --> OP
    OP --> KF

    KF --> MD_SVC
    KF --> RS
    KF --> AN

    MD_SVC --> MD
    MD --> WC
```

---

## Component Responsibilities

| Component | Role | Technology |
|-----------|------|------------|
| Order Gateway | Validate, authenticate, route orders | Spring Boot |
| FIX Gateway | Translate FIX protocol messages to internal format | QuickFIX/J |
| Pre-Trade Risk Engine | Check buying power, position limits, duplicate detection | Java + Redis |
| Ring Buffer | Lock-free handoff from gateway to matcher | LMAX Disruptor |
| Matching Engine | Price-time priority matching, order book management | Pure Java, single thread per symbol |
| Order Book (in-memory) | Sorted bid/ask structure | TreeMap keyed by price |
| Event Journal | Append-only log of all order/trade events | PostgreSQL |
| Outbox Publisher | Reliable downstream fan-out from event log | CDC or polling |
| Market Data Service | Distribute Level 1/2 data to subscribers | Redis Pub/Sub + WebSocket |
| Kafka | Decouple matching engine from downstream consumers | Kafka |

---

## Request Flow: New Limit Order

```mermaid
sequenceDiagram
    participant C as Client
    participant OG as Order Gateway
    participant RC as Risk Engine
    participant RB as Ring Buffer
    participant ME as Matching Engine
    participant EL as Event Journal
    participant KF as Kafka

    C->>OG: POST /orders (limit buy 100 AAPL @ $180)
    OG->>OG: Auth + schema validation
    OG->>RC: Pre-trade check (buying power)
    RC->>RC: Atomic reserve $18,000 in Redis
    RC-->>OG: Approved
    OG->>RB: Publish OrderEvent to ring buffer
    OG-->>C: 202 Accepted (orderId: abc123)

    RB->>ME: Consume OrderEvent
    ME->>ME: Attempt match against ask side
    ME->>ME: No match — add to bid side of book
    ME->>EL: Append OrderPlaced event (async, batched)
    ME->>KF: Publish OrderBookUpdated event
    KF->>MarketDataSvc: Update Level 1/2 snapshot
```

---

## Request Flow: Matching Trade

```mermaid
sequenceDiagram
    participant ME as Matching Engine
    participant EL as Event Journal
    participant KF as Kafka
    participant RS as Risk Service
    participant C1 as Buyer Client
    participant C2 as Seller Client

    ME->>ME: Incoming market sell order
    ME->>ME: Match against best bid $180, qty 100
    ME->>ME: Generate TradeEvent (price=180, qty=100, buyer=X, seller=Y)
    ME->>EL: Append TradeExecuted event
    ME->>KF: Publish TradeExecuted, OrderFilled (buyer), OrderFilled (seller)
    KF->>RS: Update net positions
    KF->>C1: ExecutionReport (filled)
    KF->>C2: ExecutionReport (filled)
    KF->>MarketDataSvc: Last trade price update
```

---

## Deployment View

```mermaid
graph LR
    subgraph K8s Cluster
        subgraph Gateway Pods
            OG1[Order Gateway Pod 1]
            OG2[Order Gateway Pod 2]
        end
        subgraph Matching Pods
            MP1[Matching Pod<br/>Symbols: A-M<br/>250 symbols]
            MP2[Matching Pod<br/>Symbols: N-Z<br/>250 symbols]
        end
        subgraph Data Pods
            PG[(PostgreSQL<br/>Event Journal)]
            RD[(Redis Cluster<br/>Risk + MarketData)]
        end
    end
    LB[Load Balancer] --> OG1
    LB --> OG2
    OG1 --> MP1
    OG1 --> MP2
    OG2 --> MP1
    OG2 --> MP2
```

---

## Key Architectural Decisions

### Single-Writer Per Symbol

Each symbol's matching engine is a single thread. This eliminates all locking inside the matcher. The Disruptor ring buffer handles multi-producer → single-consumer handoff with mechanical sympathy (cache-line padding, memory barriers instead of locks).

**Tradeoff:** vertical scaling limit per symbol. If one symbol generates 500K orders/sec alone (flash crash scenario), a single thread may lag. Mitigation: partition order book by price range (experimental, rarely needed).

### Async Persistence, Sync Ack

Gateway returns 202 Accepted before the order is persisted. Matching engine writes events asynchronously in batches. This means a crash between accept and persist can lose an order — mitigated by crash recovery protocol that resends any in-flight orders on reconnect.

**Alternative:** sync write before ack — adds 2-5ms per order, kills latency SLA.

### Pre-Trade Risk in Redis

Buying power check uses Redis DECRBY + check. Atomic and fast (< 0.1ms). If Redis is unavailable, orders are rejected conservatively (fail-closed).

**Risk:** Redis failure = trading halt. Mitigation: Redis Sentinel / Cluster with 3 replicas.

### Market Data via Kafka + Redis

Matching engine publishes to Kafka. Market Data Service consumes Kafka and pushes to Redis Pub/Sub channels. WebSocket servers subscribe to Redis channels and fan out to clients.

**Why not direct WebSocket from matcher?** Matcher must never block on slow clients. Decoupling via Kafka ensures matching continues even if market data delivery is slow.
