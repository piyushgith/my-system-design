# 09 — Caching Strategy: Payment Gateway / Wallet System

---

## Objective

Define what can be cached (and what absolutely cannot) in a payment system, balancing performance against the correctness guarantees that financial systems demand.

---

## The Fundamental Constraint

Payment systems have the most restrictive caching rules of any system:

**Golden Rule**: If serving stale data can cause financial loss or an incorrect transaction, do NOT cache it.

| Data | Cacheable? | Reason |
|---|---|---|
| User wallet balance (display) | Yes — short TTL | Display-only, not used for authorization |
| User wallet balance (at transaction time) | No — Redis atomic only | Must be authoritative for debit decision |
| Transaction history list | Yes — 30s TTL | Acceptable staleness |
| Transaction detail | No | Must always be fresh |
| Fraud signals / risk score | Yes — 5min TTL | Approximate is OK |
| Payment method list | Yes — 5min TTL | Low update frequency |
| Exchange rates (FX) | Yes — 1min TTL | Rates change infrequently |
| Merchant configuration | Yes — 5min TTL | Low mutation rate |
| Idempotency key result | Yes — 24h TTL | Exact; never stale |

---

## Layer 1: Redis as Balance Cache

### Balance Read (Display)

For showing balance on dashboard/home screen:

```
GET balance:{walletId}
  → If exists: return value (< 1ms)
  → If miss: SELECT balance FROM wallets WHERE id = ? → cache with TTL 30s
```

TTL 30s means user may see balance 30 seconds stale. Acceptable for display. User refreshing will see updated balance within 30s.

### Balance for Transaction Authorization

Never use cached balance for authorizing a debit:

**Redis atomic operation (not cache, but fast truth)**:

```
DECRBY balance:{walletId} {amount}
  → Returns new balance
  → If new balance < 0: INCRBY (rollback) → insufficient funds
```

This is atomic in Redis (single-threaded command processing). No race condition.

After successful DECR in Redis:
- Async write to Postgres via outbox pattern
- Redis IS the transaction authorizer; Postgres IS the durable ledger

**Reconciliation**: every 60 seconds, compare Redis balance to SUM of Postgres ledger entries. Alert on drift > tolerance.

---

## Layer 2: Idempotency Cache

The most critical cache in the payment system:

```
POST /payments with Idempotency-Key: abc-123

First request:
  1. Check Redis: GET idempotency:abc-123 → nil
  2. Acquire Redis lock: SET idempotency:abc-123 "PROCESSING" NX EX 30
  3. Process payment
  4. Store result: SET idempotency:abc-123 {response_json} EX 86400
  5. Return response

Second request (duplicate):
  1. Check Redis: GET idempotency:abc-123 → {response_json}
  2. Return cached response immediately
  → No duplicate payment
```

TTL: 24 hours (clients shouldn't retry after 24h; if they do, treat as new payment).

**If lock exists but no result** (first request still processing): return 202 Accepted with retry-after header.

**Storage**: Redis with AOF persistence. Idempotency key loss = risk of double charge. Must be durable.

---

## Layer 3: Merchant Configuration Cache

Merchant configuration changes infrequently but is read on every API request:

```
GET merchant_config:{merchantId}
  → Returns: webhook URLs, API version, allowed payment methods, risk settings, rate limits
  → TTL: 300s (5 minutes)
  → On merchant config update: DEL merchant_config:{merchantId} (event-driven invalidation)
```

Without this cache: every payment requires a DB lookup for merchant config.

---

## Layer 4: Fraud Signal Cache

Real-time fraud signals aggregated in Redis:

```
User velocity (Sorted Set):
  ZADD velocity:{userId} {timestamp} {transactionId}
  ZCOUNT velocity:{userId} (now - 3600) now  → transactions in last hour
  EXPIRE velocity:{userId} 7200

IP reputation cache:
  GET ip_risk:{ipAddress}
  → If miss: lookup IP reputation service → cache result for 5 minutes

Device fingerprint:
  GET device:{deviceId}:userId  → which userId is this device associated with
  → Cross-account device sharing: fraud signal
```

Fraud signals in Redis allow < 10ms fraud pre-check per payment. Without Redis: 100ms+ DB queries.

---

## Layer 5: Exchange Rate Cache

For multi-currency payments:

```
GET fx_rate:{fromCurrency}:{toCurrency}
  → TTL: 60s
  → Source: external FX rate provider (updated every minute)
  → On TTL expiry: background refresh (don't serve stale FX rate for more than 2 minutes)
```

Stale FX rate by 1 minute: acceptable (rates don't move dramatically in 60s for major currencies).

---

## Layer 6: Session / Auth Cache

JWT validation shortcut:

```
Token introspection cache:
  GET token_valid:{tokenHash}
  → If exists: token is valid (skip DB check)
  → TTL: 5 minutes (shorter than token expiry of 15 minutes)
  → On logout: DEL token_valid:{tokenHash} (immediate revocation)
```

Without cache: every payment API call validates JWT against DB. With cache: 95% skip DB.

---

## What Absolutely Cannot Be Cached

| Data | Why Not |
|---|---|
| Debit authorization decision | Must be authoritative — double-entry integrity |
| Fraud block status (real-time) | Must reflect most recent flag |
| Settlement amounts | Regulatory accuracy required |
| Chargeback status | Must be fresh for dispute resolution |
| Compliance flags | KYC/AML holds must be immediate |

---

## Redis Configuration for Payment Systems

### Persistence Settings

```
appendonly yes              # AOF persistence enabled
appendfsync everysec        # fsync every second (max 1s data loss)
aof-rewrite-incremental-fsync yes

# For balance data: stricter persistence
appendfsync always          # For balance:{walletId} keys: fsync on every write
                            # Achievable via separate Redis instance or keyspace-specific config
```

**Two Redis instances strategy** (payment-grade):
- `redis-balance`: `appendfsync always` — synchronous fsync, max durability, slower
- `redis-general`: `appendfsync everysec` — async fsync, fast, 1s risk acceptable

### Eviction Policy

```
maxmemory-policy noeviction
```

**Never evict** in payment Redis. If memory fills: ALERT immediately. Scale Redis before it reaches capacity. Silent eviction of idempotency keys = double charge risk.

### Cluster Setup

```
3 master nodes + 3 replica nodes
Replication: synchronous within cluster
min-replicas-to-write: 1   # At least 1 replica must ack write
min-replicas-max-lag: 10   # Replica must be within 10s of master
```

---

## Cache Invalidation

| Data | Invalidation Strategy |
|---|---|
| Wallet balance (display) | TTL 30s + event-driven DEL on transaction |
| Merchant config | Event-driven DEL on config update |
| Idempotency key | TTL 24h (no manual invalidation) |
| Exchange rates | TTL 60s + background refresh |
| Token validity | TTL 5min + DEL on logout |
| Fraud signals | TTL-based per signal type |

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Never cache balance for debit | Correctness — no double spend | Every debit hits Redis (fast but not cached) |
| Idempotency in Redis | Sub-millisecond dedup check | Redis persistence must be strict; AOF required |
| Balance in Redis as primary | Low latency debit decisions | Reconciliation job needed; Redis failure = fallback complexity |
| `noeviction` policy | No silent data loss | Must over-provision Redis memory; cost |
| Separate balance Redis | Per-key fsync for balance | Two Redis clusters to manage |

---

## Interview Discussion Points

- **"Can you cache the user's wallet balance?"** → Display only; never for transaction authorization. Authorization uses Redis atomic DECR, not a cache read.
- **"How do you prevent double charging when client retries?"** → Idempotency key cached in Redis for 24h; duplicate request returns cached response immediately
- **"What's your Redis eviction policy?"** → `noeviction` — never silently evict in payment Redis; out-of-memory = alert; scale first
- **"What if Redis goes down during a payment?"** → Fall back to Postgres SELECT FOR UPDATE for balance check (slower but correct); idempotency keys fall back to DB; no data loss if Redis had AOF
- **"How fresh does the FX rate need to be?"** → 60s TTL acceptable for most currencies; for exotic pairs, tighter TTL; always show "rate valid as of" timestamp to user
