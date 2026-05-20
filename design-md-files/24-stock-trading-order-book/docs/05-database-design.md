# 05 — Database Design: Stock Trading Order Book

## Objective

Design the persistence layer for order events, trades, participants, and instruments. The matching engine runs entirely in-memory — the database is the audit trail and recovery source, not the hot path.

---

## Storage Architecture

```
┌─────────────────────────────────────────────────────────┐
│ HOT PATH (in-memory, no DB)                              │
│  Matching Engine → Ring Buffer → Order Book (TreeMap)    │
│  Risk Engine → Redis (buying power, position reserves)   │
└─────────────────────────────────────────────────────────┘
         │
         │ async, batched writes
         ▼
┌─────────────────────────────────────────────────────────┐
│ WARM PATH (PostgreSQL — event journal)                   │
│  order_events table — append-only                        │
│  trades table — execution records                        │
│  order_snapshots table — periodic checkpoint             │
└─────────────────────────────────────────────────────────┘
         │
         │ archival after 90 days
         ▼
┌─────────────────────────────────────────────────────────┐
│ COLD PATH (S3 / Object Storage — 7-year retention)       │
│  Parquet files partitioned by date + symbol              │
└─────────────────────────────────────────────────────────┘
```

**Why PostgreSQL?**
- ACID guarantees for event journal integrity
- Sequential writes (append-only) match PostgreSQL's WAL structure well
- Rich query support for compliance, audit, and historical analysis
- `COPY` command for high-throughput bulk inserts of batched events

**When to introduce additional storage:**
- TimescaleDB: if time-series analytics on trade data becomes a major workload (OHLCV candles)
- Cassandra: if write throughput exceeds PostgreSQL's capacity (>500K events/sec sustained)

---

## Schema Design

### order_events (Append-Only Event Journal)

```sql
CREATE TABLE order_events (
    id              BIGSERIAL,
    event_id        UUID NOT NULL DEFAULT gen_random_uuid(),
    order_id        UUID NOT NULL,
    client_order_id VARCHAR(64),
    participant_id  UUID NOT NULL,
    symbol          VARCHAR(16) NOT NULL,
    event_type      VARCHAR(32) NOT NULL,  -- ORDER_PLACED, ORDER_FILLED, etc.
    side            CHAR(4) NOT NULL,       -- BUY, SELL
    order_type      VARCHAR(16) NOT NULL,
    price           NUMERIC(18,4),
    quantity        BIGINT,
    filled_quantity BIGINT DEFAULT 0,
    fill_price      NUMERIC(18,4),
    status          VARCHAR(24) NOT NULL,
    sequence_number BIGINT NOT NULL,        -- per-symbol monotonic
    occurred_at     TIMESTAMPTZ(9) NOT NULL, -- nanosecond precision
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    raw_payload     JSONB                   -- full event for replay
) PARTITION BY RANGE (occurred_at);

-- Never update or delete — append only
-- Primary key on (symbol, sequence_number) for gap detection
CREATE UNIQUE INDEX idx_order_events_seq ON order_events (symbol, sequence_number);
CREATE INDEX idx_order_events_order_id ON order_events (order_id, occurred_at);
CREATE INDEX idx_order_events_participant ON order_events (participant_id, occurred_at);
```

**Partitioning:** Range partition by `occurred_at` — monthly partitions. Older partitions detached and archived to S3 after 90 days. PostgreSQL partition pruning makes recent queries fast without touching old partitions.

---

### trades (Execution Records)

```sql
CREATE TABLE trades (
    trade_id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol            VARCHAR(16) NOT NULL,
    buy_order_id      UUID NOT NULL,
    sell_order_id     UUID NOT NULL,
    buy_participant_id  UUID NOT NULL,
    sell_participant_id UUID NOT NULL,
    price             NUMERIC(18,4) NOT NULL,
    quantity          BIGINT NOT NULL,
    sequence_number   BIGINT NOT NULL,    -- per-symbol, monotonic
    executed_at       TIMESTAMPTZ(9) NOT NULL,
    settlement_date   DATE NOT NULL,      -- T+2 from executed_at
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
) PARTITION BY RANGE (executed_at);

CREATE UNIQUE INDEX idx_trades_seq ON trades (symbol, sequence_number);
CREATE INDEX idx_trades_buy_participant ON trades (buy_participant_id, executed_at);
CREATE INDEX idx_trades_sell_participant ON trades (sell_participant_id, executed_at);
CREATE INDEX idx_trades_symbol_date ON trades (symbol, executed_at);
```

---

### orders (Current State View — Read Model)

This table is NOT written by the matching engine. It is a materialized view maintained by a separate consumer of the event log. The matching engine is source of truth only through `order_events`.

```sql
CREATE TABLE orders (
    order_id          UUID PRIMARY KEY,
    client_order_id   VARCHAR(64),
    participant_id    UUID NOT NULL,
    symbol            VARCHAR(16) NOT NULL,
    side              CHAR(4) NOT NULL,
    order_type        VARCHAR(16) NOT NULL,
    price             NUMERIC(18,4),
    quantity          BIGINT NOT NULL,
    filled_quantity   BIGINT NOT NULL DEFAULT 0,
    remaining_quantity BIGINT GENERATED ALWAYS AS (quantity - filled_quantity) STORED,
    status            VARCHAR(24) NOT NULL,
    time_in_force     VARCHAR(8) NOT NULL,
    expiry_date       DATE,
    submitted_at      TIMESTAMPTZ(9) NOT NULL,
    last_updated_at   TIMESTAMPTZ NOT NULL,
    version           BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_orders_participant_status ON orders (participant_id, status);
CREATE INDEX idx_orders_symbol_status ON orders (symbol, status) WHERE status IN ('NEW', 'PARTIALLY_FILLED');
CREATE INDEX idx_orders_client_order_id ON orders (client_order_id, participant_id);
```

**CQRS separation:** Matching engine writes `order_events` (write model). Event consumer builds `orders` table (read model). This prevents read queries from locking the event journal.

---

### order_book_snapshots (Crash Recovery)

```sql
CREATE TABLE order_book_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    symbol          VARCHAR(16) NOT NULL,
    snapshot_data   JSONB NOT NULL,        -- full serialized order book
    sequence_number BIGINT NOT NULL,       -- last event included in snapshot
    captured_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_snapshots_symbol_seq ON order_book_snapshots (symbol, sequence_number DESC);
```

Snapshots taken every 5 minutes per symbol. On crash recovery: load latest snapshot, then replay `order_events` from `snapshot.sequence_number + 1` to rebuild current state. Recovery time: ~30 seconds.

---

### participants

```sql
CREATE TABLE participants (
    participant_id  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id     VARCHAR(64) UNIQUE NOT NULL,
    type            VARCHAR(24) NOT NULL,  -- RETAIL, INSTITUTIONAL, MARKET_MAKER
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    cash_balance    NUMERIC(18,4) NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT NOT NULL DEFAULT 0
);
```

---

### positions

```sql
CREATE TABLE positions (
    position_id     BIGSERIAL PRIMARY KEY,
    participant_id  UUID NOT NULL REFERENCES participants(participant_id),
    symbol          VARCHAR(16) NOT NULL,
    quantity        BIGINT NOT NULL DEFAULT 0,  -- positive = long, negative = short
    average_cost    NUMERIC(18,4),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (participant_id, symbol)
);
```

---

### instruments

```sql
CREATE TABLE instruments (
    symbol              VARCHAR(16) PRIMARY KEY,
    name                VARCHAR(256) NOT NULL,
    instrument_type     VARCHAR(16) NOT NULL,   -- EQUITY, ETF
    status              VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    lot_size            INT NOT NULL DEFAULT 1,
    tick_size           NUMERIC(10,4) NOT NULL DEFAULT 0.01,
    circuit_breaker_pct NUMERIC(5,2) NOT NULL DEFAULT 10.00,  -- % move triggers halt
    trading_open        TIME NOT NULL DEFAULT '09:30:00',
    trading_close       TIME NOT NULL DEFAULT '16:00:00',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

---

## Indexing Strategy

| Table | Index | Type | Purpose |
|-------|-------|------|---------|
| order_events | (symbol, sequence_number) | Unique B-tree | Gap detection, replay |
| order_events | (order_id, occurred_at) | B-tree | Order history lookup |
| order_events | (participant_id, occurred_at) | B-tree | Participant audit |
| trades | (symbol, sequence_number) | Unique B-tree | Trade continuity |
| trades | (buy_participant_id, executed_at) | B-tree | Participant trade history |
| orders | (participant_id, status) | Partial B-tree | Open order queries |
| orders | (client_order_id, participant_id) | B-tree | Idempotency lookup |

---

## Partitioning Strategy

| Table | Strategy | Partition Size | Retention |
|-------|---------|---------------|-----------|
| order_events | Range by occurred_at | Monthly | 90 days active, then archive |
| trades | Range by executed_at | Monthly | 90 days active, then archive |
| order_book_snapshots | None | N/A | 7 days (only need latest) |

**Partition management:** pg_partman extension for automated partition creation and maintenance. Cron job detaches old partitions monthly and exports to S3 as Parquet via pg_dump + conversion.

---

## Redis Schema (Risk Engine Cache)

```
# Buying power (available cash for orders)
buying_power:{participantId}  → DECIMAL (SET with expiry refresh)

# Cash reservation per order
cash_reserved:{participantId}:{orderId}  → DECIMAL (TTL = order lifetime)

# Net position (real-time view)
position:{participantId}:{symbol}  → INTEGER (share count, signed)

# Position reservation per open sell order
position_reserved:{participantId}:{symbol}:{orderId}  → INTEGER

# Idempotency cache (clientOrderId → systemOrderId)
idem:{participantId}:{clientOrderId}  → UUID  (TTL = 24h)

# Duplicate detection bloom filter
bloom:orders:{date}  → Bloom filter (approximate, fast reject)
```

---

## Consistency Tradeoffs

| Operation | Consistency Model | Justification |
|-----------|------------------|---------------|
| Order event write | Async batch after match | Latency-critical — async is the only option |
| Read model (orders table) | Eventually consistent | Few seconds lag acceptable for status queries |
| Risk reserve (Redis) | Strongly consistent per participant | Must not double-spend |
| Trade record | Synchronous from matcher perspective | Durability required before ack |
| Order book snapshots | Eventual | Used only for crash recovery |

---

## Data Archival Strategy

```
Day 0–90:   PostgreSQL (hot partitions, indexed, queryable)
Day 91–365: PostgreSQL cold partitions (detached, read-only, no index maintenance)
Year 1–7:   S3 Parquet files (queryable via Athena / Spark for regulatory)
Year 7+:    Glacier (cost-optimized deep archive)
```

**Regulatory requirement:** 7-year retention for all trade records (SEC Rule 17a-4).
