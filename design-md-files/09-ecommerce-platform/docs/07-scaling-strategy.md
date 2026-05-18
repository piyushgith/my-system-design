# 07 — Scaling Strategy: E-Commerce Platform

---

## Objective

Define horizontal/vertical scaling, caching layers, load balancing, rate limiting, and performance bottleneck analysis for the e-commerce platform across traffic tiers — with special focus on flash sales, catalog reads, and order throughput.

---

## Scaling Tiers

| Tier | Daily Orders | Peak RPS | Architecture |
|---|---|---|---|
| Startup | 1K/day | 50 RPS | Monolith, single Postgres, Redis |
| Growth | 50K/day | 2,000 RPS | 3 app instances, Postgres primary+replica, Redis cluster |
| Scale | 500K/day | 20,000 RPS | Domain services, Kafka, ElasticSearch, CDN |
| FAANG | 5M+/day | 200,000 RPS | Multi-region, active-active, CQRS, sharded DB |

---

## Read vs Write Traffic Profile

E-commerce is extremely read-heavy:

| Operation | Read:Write Ratio | Scaling Priority |
|---|---|---|
| Product catalog browse | 1000:1 | CDN + Redis |
| Search | 500:1 | Elasticsearch |
| Cart operations | 10:1 | Redis-first |
| Order placement | Write-heavy | DB write path |
| Order status check | 50:1 | Cache + replica |
| Inventory check | 100:1 | Redis counter |

---

## The Five Scaling Levers

### 1. CDN for Static and Semi-Static Catalog

Product images, descriptions, and category pages rarely change.

- All product images → S3 + CloudFront
- Product detail pages → CDN edge cache with 5-minute TTL
- Category listings → CDN with 1-minute TTL
- Cache invalidation on product update via CDN purge API

**Impact**: 80%+ of catalog reads never touch application servers.

### 2. Elasticsearch for Search

PostgreSQL full-text search breaks at scale:
- Elasticsearch handles 500M product index with sub-100ms response
- Separate read cluster dedicated to search queries
- Event-driven sync: product update → Kafka → ES indexer
- No impact on transactional DB from search load

### 3. Redis for Inventory and Cart

**Inventory counters in Redis:**
```
DECR inventory:{productId}:{warehouseId}
```
- Atomic decrement prevents oversell
- Persist to Postgres async via background job
- Redis as the "fast truth" for availability checks

**Cart in Redis:**
- Cart is session data — Redis TTL-based storage
- No DB write until checkout
- Cart merge on login (guest → authenticated)

### 4. CQRS for Order Reads

Order history queries are expensive on write DB:
- Write path: Postgres (strong consistency, ACID)
- Read path: Read replica or separate read model in Postgres/MongoDB
- Events: OrderPlaced → denormalized order summary → read store
- Order status endpoint hits read store, not write DB

### 5. Horizontal App Scaling + Stateless Services

- All application services stateless (no in-memory session)
- Session/cart state in Redis
- Kubernetes HPA scales pods on CPU/RPS thresholds
- Separate pod pools for: catalog API, order API, payment processing

---

## Flash Sale Architecture

Flash sales are the hardest scaling problem in e-commerce.

### Problem

- 10,000 concurrent users → 1 product → 100 units available
- Naive approach: DB update with lock → deadlock storm
- Result: 0 orders processed, database meltdown

### Solution: Token Bucket + Redis Atomic Operations

```
Phase 1 — Pre-sale queue (1 hour before):
  - Accept registrations → Redis Set of eligible user IDs
  - No DB writes

Phase 2 — Sale start:
  - Lua script atomic check: IF inventory > 0 THEN DECR RETURN 1
  - Granted users → order reservation queue (Kafka)
  - Rejected users → "sold out" response immediately

Phase 3 — Async order creation:
  - Kafka consumers create actual orders in Postgres
  - Payment capture async
  - Inventory reconciled to Postgres after Redis batch
```

**Key insight**: Redis handles the thundering herd. Postgres only sees 100 successful writes (one per available unit), not 10,000 concurrent attempts.

### Traffic Shaping

- Rate limit: 10 req/sec per user during flash sale via Redis token bucket
- Queue admission: Virtual waiting room (like Ticketmaster) — issue queue tokens, release in batches
- CDN blocks sale start requests until T+0 to prevent pre-sale hammering

---

## Load Balancing Strategy

```
Internet → CloudFront CDN
              ↓ (cache miss)
         ALB (Layer 7, path-based routing)
              ↓
    ┌─────────────────────────────┐
    │  /api/catalog/*  → Catalog pods  │
    │  /api/orders/*   → Order pods    │
    │  /api/search/*   → Search pods   │
    │  /api/cart/*     → Cart pods     │
    └─────────────────────────────┘
```

- Sticky sessions not needed (stateless services)
- Health checks on `/actuator/health`
- Circuit breaker at ALB level via target group health

---

## Rate Limiting

| API | Limit | Window | Enforcement |
|---|---|---|---|
| Product browse | 1,000 req | per minute per IP | Redis token bucket |
| Search | 100 req | per minute per user | Redis |
| Cart add | 60 req | per minute per user | Redis |
| Order place | 10 req | per minute per user | Redis |
| Payment | 5 req | per minute per user | Redis + DB flag |

---

## Database Scaling

### Postgres Read Replicas

- 1 write primary + 2 read replicas minimum
- Catalog reads → replica
- Order history reads → replica
- Order placement → primary only

### Connection Pooling

- PgBouncer in transaction mode (not session mode)
- Max 100 DB connections from PgBouncer
- App pods → PgBouncer (many connections)
- PgBouncer → Postgres (limited connections)
- Without pooling: 100 pods × 10 connections = 1,000 Postgres connections → OOM

### Sharding (when needed)

- Shard key: `seller_id` for seller catalog
- Shard key: `user_id` for order history
- Cross-shard queries (admin reports) → analytics replica or data warehouse
- Do NOT shard prematurely — vertical scaling + read replicas handles 90% of cases

---

## Performance Bottlenecks

| Bottleneck | Symptom | Fix |
|---|---|---|
| N+1 on order items | Order list slow | Batch fetch + JOIN |
| Catalog without cache | High DB CPU | Redis + CDN |
| Inventory check on DB | Checkout timeout | Redis atomic counter |
| Elasticsearch sync lag | Stale search results | Tune consumer lag |
| Large cart serialization | Redis memory spike | Cap cart at 100 items |
| Image serving from app | High bandwidth cost | S3 + CDN |

---

## Vertical vs Horizontal Scaling Decision

| Phase | Decision | Reason |
|---|---|---|
| 0-10K users | Vertical (bigger DB) | Simpler, no distributed complexity |
| 10K-100K | Add read replicas + Redis | Read traffic dominates |
| 100K-1M | Horizontal app scaling + Kafka | Write throughput needed |
| 1M+ | Service decomposition + sharding | Bounded contexts overwhelm single DB |

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Redis for inventory | No oversell, fast | Redis persistence risk |
| Elasticsearch separate | Fast search | Eventual consistency in search index |
| CQRS for orders | Read scale | Complexity, sync lag |
| CDN for catalog | Massive read offload | Stale data window |

---

## Interview Discussion Points

- **"How do you handle flash sales without crashing the DB?"** → Redis atomic decrement + virtual queue, DB only sees successful reservations
- **"How do you prevent overselling?"** → Lua script on Redis: atomic check-and-decrement
- **"How do you scale search?"** → Elasticsearch cluster, event-driven sync via Kafka, no DB for search queries
- **"When would you shard Postgres?"** → Not until replicas + connection pooling exhausted; premature sharding is biggest trap
- **"What breaks first at 10x load?"** → Database connection pool → PgBouncer is first mitigation, then read replicas
