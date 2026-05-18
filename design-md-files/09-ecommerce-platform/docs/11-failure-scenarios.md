# 11 — Failure Scenarios: E-Commerce Platform

---

## Objective

Analyze failure modes across the e-commerce stack — from infrastructure failures to business logic edge cases — and define detection, recovery, and prevention strategies.

---

## Failure Categories

1. Infrastructure failures (DB down, Redis down, Kafka down)
2. Downstream service failures (payment gateway timeout, warehouse API down)
3. Business logic failures (oversell, double charge, lost order)
4. Data consistency failures (inventory drift, order in inconsistent state)
5. Cascading failures (thundering herd, retry storms)
6. Security failures (fraud, account takeover)

---

## Scenario 1: Postgres Primary Goes Down

**Trigger**: Hardware failure, OOM kill, network partition.

**Impact without mitigation**:
- All writes fail (order placement, payment updates)
- System effectively down

**Mitigation**:
- AWS RDS Multi-AZ: automatic failover to standby (< 60s)
- Application connection pool retries with exponential backoff
- Write operations return 503 during failover window

**Recovery**:
- RDS promotes standby automatically
- Application reconnects via same endpoint (DNS update)
- Orders attempted during outage: client retries with idempotency key

**Data loss risk**:
- RDS Multi-AZ: synchronous replication — 0 committed transaction loss
- Async replica: possible lag-based loss — do NOT use for failover

---

## Scenario 2: Redis Goes Down

**Impact**:
- Cart operations fail (cart is Redis-only)
- Inventory checks fall back to DB (slower, higher DB load)
- Rate limiting unavailable (fail-open or fail-closed decision needed)

**Mitigation**:
- Redis Cluster: automatic failover to replica (< 30s downtime)
- Redis Sentinel for single-node setups
- Circuit breaker: if Redis unavailable, fall back to DB for inventory reads
- Cart: fail gracefully — user sees "cart temporarily unavailable" or read-only view

**Cart data loss**:
- Redis AOF persistence: at most 1s of cart updates lost
- Cart loss is annoying but not financial loss
- On reconnect: stale/empty cart shown; user re-adds items

**Rate limiting on Redis failure**:
- Fail-open (allow requests): risk of abuse during outage window
- Fail-closed (block requests): safer for payment APIs; unacceptable for catalog
- Recommendation: fail-open for catalog/search; fail-closed for payment and order APIs

---

## Scenario 3: Payment Gateway Timeout

**Trigger**: Stripe/Adyen API latency spike or timeout.

**Impact**:
- User hits "Confirm Order" → spinner for 30s → error
- Payment may or may not have processed (ambiguous state)

**Mitigation**:
- Async payment flow:
  1. Order placed synchronously (status: PAYMENT_PENDING)
  2. Payment initiated asynchronously
  3. Webhook from payment gateway updates order status
  4. User sees: "Order received, payment processing"

- Synchronous flow (simpler but brittle):
  - 30s timeout with retry
  - Idempotency key prevents double charge on retry
  - If ambiguous: poll payment gateway status API before retrying

**Recovery**:
- If gateway timed out but payment succeeded: webhook arrives → order confirmed
- If gateway timed out and payment failed: order stays PAYMENT_FAILED → retry prompt
- If no webhook after 10 minutes: poll payment gateway status → reconcile

---

## Scenario 4: Inventory Oversell

**Trigger**: Race condition — 1 item left, 100 simultaneous checkouts.

**Impact**: 100 orders created for 1 available item.

**Mitigation stack**:

Layer 1 — Redis atomic decrement:
```
Lua script: IF DECR inventory:{productId} >= 0 RETURN 1 ELSE INCR (rollback) RETURN 0
```
- Handles 99% of races atomically

Layer 2 — Database constraint:
```sql
CHECK (inventory_count >= 0)
```
- Last line of defense if Redis diverged

Layer 3 — Inventory reservation:
- Reserve inventory at cart-add time (hold for 15 minutes)
- Release reservation if checkout not completed
- Prevents "add to cart" race vs "checkout" race

**What if oversell happens anyway**:
- Detection: Postgres inventory < 0 alert
- Resolution: Cancel last N orders (by created_at DESC) until inventory = 0
- Notify affected users with apology + coupon

---

## Scenario 5: Order Stuck in PENDING State

**Trigger**: Kafka consumer crashed after order created but before inventory reserved or payment initiated.

**Impact**: Order permanently stuck. User confused. Inventory not reserved.

**Mitigation**:
- Timeout sweeper job:
  ```
  Every 5 minutes: SELECT * FROM orders WHERE status='PENDING' AND created_at < NOW() - INTERVAL '15 minutes'
  → For each: cancel order, release inventory, send notification
  ```
- Outbox pattern guarantees event eventually published
- Idempotent Kafka consumer: safe to replay

**State machine enforcement**:
```
PENDING → PAYMENT_PENDING → CONFIRMED → PROCESSING → SHIPPED → DELIVERED
       → PAYMENT_FAILED
       → CANCELLED
```
- Invalid state transitions rejected at service layer
- No direct jump from PENDING to SHIPPED

---

## Scenario 6: Double Order on Network Retry

**Trigger**: Client times out, retries POST /orders. Two orders created.

**Impact**: Customer charged twice, duplicate fulfillment.

**Mitigation**:
- Idempotency key required on POST /orders
- Key = client-generated UUID, stored in request header `Idempotency-Key`
- Server stores: `idempotency_keys` table with (key, response, created_at)
- Duplicate request → return cached response
- Idempotency key TTL: 24 hours

---

## Scenario 7: Kafka Consumer Lag Explosion

**Trigger**: Kafka consumer falls behind — high message rate, slow processing, consumer crash.

**Impact**:
- Notifications delayed
- Search index stale
- Inventory reservation delayed → checkout appears slow

**Mitigation**:
- Alert: consumer lag > 10,000 messages
- Scale consumers: add more consumer instances (up to partition count)
- Prioritize: order/inventory consumers get more instances than analytics
- Circuit breaker: if inventory reservation too delayed, fail-safe to DB check

---

## Scenario 8: Flash Sale Thundering Herd

**Trigger**: 100,000 users hit checkout simultaneously at flash sale start.

**Impact**: DB connection exhaustion, Redis connection storm, order service OOM.

**Mitigation**:
- Virtual waiting room: issue queue tokens 1 hour before, release in batches of 1000
- Redis-first: all purchase decisions in Redis before touching DB
- Rate limiting: 1 req/sec per user enforced at API gateway
- Pre-scale: Kubernetes HPA set to target pod count 1 hour before sale (pre-warm)
- CDN absorbs: sale page served from CDN, only checkout hits origin

---

## Scenario 9: Elasticsearch Down

**Trigger**: ES cluster unhealthy, index corruption.

**Impact**: Search returns 503. Users can't find products.

**Mitigation**:
- Search degradation: fall back to Postgres full-text search (slower but functional)
- Circuit breaker: after 5 consecutive ES failures, open circuit and fall back
- Product browsing by category still works (DB-backed, not ES)
- Alert: ES cluster health RED/YELLOW

**Recovery**:
- ES auto-recovery from snapshot
- Re-index from Postgres: replay `ecommerce.product.updated` events from Kafka (Kafka retention: 7 days)

---

## Scenario 10: CDN Cache Poisoning / Stale Price

**Trigger**: Product price updated but CDN serves cached old price.

**Impact**: User sees old price on product page. Places order expecting old price. Gets charged new price. Dispute.

**Mitigation**:
- Price always re-validated server-side at checkout (never trust client-submitted price)
- CDN TTL for product pages: 5 minutes (limited exposure window)
- Event-driven CDN purge on price change
- Price change UI shows confirmation with updated price before payment capture

---

## Cascading Failure Prevention

### Circuit Breaker (Resilience4j)

Wrap all external calls:
- Payment gateway: CLOSED → OPEN after 5 failures in 60s → HALF_OPEN after 30s
- Warehouse API: same pattern
- Elasticsearch: same pattern

Circuit OPEN: return fallback (cached data, graceful degradation), not error cascade.

### Bulkhead Pattern

Separate thread pools per dependency:
- Payment calls: pool of 20 threads
- Warehouse calls: pool of 10 threads
- Email service: pool of 5 threads

Payment slowdown does not exhaust shared thread pool → doesn't kill catalog API.

### Timeout Hierarchy

| Call | Timeout |
|---|---|
| Redis | 50ms |
| Postgres | 2s |
| Payment gateway | 10s |
| Warehouse API | 5s |
| Elasticsearch | 500ms |

Timeouts enforced, not optional. Never indefinite blocking.

---

## Recovery Runbooks (Summary)

| Failure | Detection | Recovery |
|---|---|---|
| Postgres down | CloudWatch alarm, 503 spike | RDS Multi-AZ auto-failover |
| Redis down | Redis alert, cart errors | Redis Cluster auto-failover |
| Payment gateway timeout | High payment latency alert | Async flow + idempotent retry |
| Oversell | Inventory < 0 alert | Cancel recent orders, notify users |
| Stuck orders | PENDING > 15 min cron | Sweep job cancels, releases inventory |
| Kafka lag | Consumer lag > 10K alert | Scale consumer instances |
| ES down | Search 503 alert | Fall back to Postgres search |

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Async payment flow | Resilient to gateway timeout | UX: no instant payment confirmation |
| Fail-open for catalog rate limit | No downtime during Redis failure | Brief abuse window |
| DB constraint on inventory | Last-resort oversell protection | Slower than Redis |
| Idempotency key for orders | No double charge | Client must generate + store key |

---

## Interview Discussion Points

- **"What breaks first under 10x load?"** → DB connection pool; then Redis connection pool; then memory
- **"How do you prevent double charging?"** → Idempotency key + dedup table + async payment with webhook
- **"How do you handle Kafka consumer falling behind?"** → Alert on lag, scale instances up to partition count, prioritize critical consumers
- **"What's your RTO/RPO for DB failure?"** → RTO < 60s (RDS Multi-AZ failover); RPO = 0 (synchronous replication)
- **"How do you recover from oversell?"** → Redis atomics prevent it; if drift: cancel newest orders, notify, issue coupons
