# 14 — Interview Discussion Points: E-Commerce Platform

---

## Objective

Prepare for candidate's system design interview discussion on the e-commerce platform. Covers expected follow-up questions, tradeoff discussions, scaling evolution, common mistakes, and senior/staff engineer talking points.

---

## Expected Interviewer Follow-Up Questions

### Requirements Clarification

- "Is this a first-party seller platform (like Amazon retail) or marketplace (like Amazon Marketplace)?"
- "Do we need to support physical and digital goods?"
- "What's our scale? 1K orders/day or 1M orders/day?"
- "Do we need real-time inventory or eventual consistency is acceptable?"
- "What's the payment flow — are we integrating with a payment gateway or building our own?"

**What interviewers want to see**: You drive requirements before designing. You don't assume.

---

### Architecture Questions

**Q: Why not build this as microservices from day one?**

Strong answer:
> Microservices before product-market fit is organizational suicide. A 5-person team maintaining 10 services spends more time on infra than features. Start with modular monolith — well-organized packages, clean domain boundaries, shared DB. Extract to microservices when you have proven load that a single instance can't handle. Inventory and payments are the first candidates — they have distinct scaling and reliability requirements.

**Q: How do you define service boundaries?**

Strong answer:
> DDD bounded contexts. Catalog (product data), Orders (order lifecycle), Inventory (stock management), Payments (financial transactions), Notifications (async comms), Search (read-optimized). Each context has its own data model. Cross-context calls via REST or events, never direct DB joins.

**Q: How would you evolve the architecture from startup to Taking?**

Strong answer (with phases):
> Phase 1: Modular monolith + Postgres + Redis. Phase 2: Extract payments service (compliance boundary). Phase 3: Extract search service (scaling boundary — Elasticsearch). Phase 4: Extract inventory service (flash sale scaling). Phase 5: Multi-region active-passive. Phase 6: CQRS for order reads. Each extraction is driven by a specific problem, not anticipation.

---

### Data and Database Questions

**Q: How do you prevent overselling during flash sales?**

Strong answer:
> Two layers. First: Redis Lua script does atomic check-decrement — if inventory > 0, decrement and allow. Else reject immediately. This handles the thundering herd without touching Postgres. Second: Postgres CHECK constraint on inventory_count >= 0 — last resort if Redis diverges. Periodic reconciliation job compares Redis counter to Postgres and alerts on drift.

**Q: How do you handle the cart → order inventory race condition?**

Strong answer:
> Inventory reservation at cart time with TTL. When user adds to cart, Redis reserves a unit for 15 minutes. This holds the unit off the market. At checkout, reservation confirmed. If user abandons cart, TTL expires, unit released automatically. Reduces race to: two users adding to cart at the same millisecond — handled by Redis atomic decrement.

**Q: When would you shard Postgres?**

Strong answer:
> Not until vertical scaling + read replicas + connection pooling are exhausted. Common mistake: premature sharding. At 1M orders/day, read replicas + PgBouncer + proper indexing handle the load. Shard when write throughput saturates single primary — typically 100K+ writes/minute sustained. Shard key for orders: user_id (gives you order history per user on same shard). Cross-shard queries go to data warehouse.

**Q: How do you handle the product catalog for millions of products?**

Strong answer:
> Postgres for product metadata (seller_id, price, category_id, created_at). Elasticsearch for search (full-text, faceted filters, ranking). CDN for product images and static catalog pages. Redis for hot products (top 10K viewed). The key is not putting search queries on Postgres — Postgres full-text search breaks at millions of products with complex ranking.

---

### Scaling Questions

**Q: Product detail page gets 100K RPS. What do you do?**

Strong answer:
> Layer 1: CDN — 80%+ of requests served from edge. Layer 2: Redis — remaining requests hit Redis cache (sub-millisecond). Layer 3: Postgres read replica — only cache misses. Layer 4: DB — only first request per product per TTL hits DB. Product detail is idempotent, cacheable, and doesn't require auth. This is the ideal CDN use case.

**Q: How do you handle Diwali sale (10x traffic spike) without pre-provisioning?**

Strong answer:
> Kubernetes HPA handles gradual spikes but not instant 10x. For predictable events: pre-scale 30 minutes before. Set HPA minReplicas to 3x normal. Redis Cluster pre-scaled. CDN absorbs catalog reads (no app scaling needed for those). Virtual waiting room limits concurrency at checkout. The 10x hits CDN, Redis, and Kubernetes horizontally — not a single Postgres.

---

### Reliability Questions

**Q: A user reports they were charged but order doesn't show in their history. What happened?**

Diagnosis approach:
1. Check payment service logs for `userId` + `amount` + `timestamp`
2. Check if payment succeeded in gateway but order service failed
3. Check Kafka consumer lag — did order.confirmed event get consumed?
4. Check idempotency table — duplicate request?

Resolution: Order likely in PAYMENT_SUCCEEDED state but notification or read model not updated. Reconciliation between payment records and order records. This is why outbox pattern matters — atomicity between payment update and event publish.

**Q: How do you handle network partitions during checkout?**

Strong answer:
> Two scenarios. (1) Client → Server partition: client retries with idempotency key, server returns cached response for duplicate. (2) Order service → Payment gateway partition: async payment flow — order created in PENDING state, payment retried async with exponential backoff. Customer sees "order received, payment processing." If payment fails after retries, order cancelled and customer notified.

---

### Common Mistakes (What Interviewers Watch For)

| Mistake | Why It's Wrong | Better Approach |
|---|---|---|
| Full text search on Postgres | Performance collapse at scale | Elasticsearch |
| Storing card numbers | PCI DSS violation | Tokenization via payment vault |
| Client-submitted prices | Price manipulation attack | Server-side price validation always |
| Synchronous payment | Gateway timeout = lost order | Async payment with webhook |
| Single Redis node for inventory | SPOF for flash sale | Redis Cluster with replication |
| Microservices from day 1 | Premature complexity | Modular monolith first |
| No idempotency on order API | Double order on retry | Idempotency key required |
| Global mutex on inventory | Deadlock storm | Redis atomic ops per productId |

---

### Senior Engineer Discussion Points

- **Architecture evolution**: articulate the monolith → service extraction path and what triggers each extraction
- **Tradeoff communication**: "We chose eventual consistency for search because stale search results for 5 seconds are acceptable; stale inventory at purchase time is not"
- **Failure mode thinking**: proactively identify what breaks first under load, what data is at risk, what user experience degrades gracefully
- **Make-or-buy decisions**: payment processing (integrate Stripe vs build own), fraud detection (rules engine vs Sift), search (Elasticsearch vs Algolia)
- **Cost awareness**: CDN saves 80% compute; ElastiCache cost vs query load on RDS

---

### Staff Engineer Discussion Points

- **Org implications**: where do service ownership boundaries map to team boundaries? Catalog team, Orders team, Payments team
- **Platform thinking**: what shared infrastructure does every team need? API Gateway, auth, observability, schema registry, feature flags
- **Tech debt risk**: what decisions today create migration pain in 2 years? (premature microservices, wrong shard key, no idempotency)
- **Incident post-mortem thinking**: "The Diwali 2023 outage was caused by... and we changed the architecture by..."
- **Build vs buy at scale**: "At 10M orders/day, Stripe processing fees become significant — when do you build your own payment processor vs tolerate the cost?"

---

### "What Would Break First?" Analysis

| Scale Multiplier | First Failure | Fix |
|---|---|---|
| 2x | Postgres connection pool exhaustion | PgBouncer + read replicas |
| 5x | Catalog API DB reads overwhelm replicas | Redis + CDN caching |
| 10x | Order service single instance CPU | Horizontal scaling + stateless services |
| 50x | Inventory DB write contention | Redis atomic counter as primary |
| 100x | Postgres write throughput ceiling | DB sharding by user_id |
| 1000x | Single-region latency for global users | Multi-region active-passive |

---

### Tradeoff Discussion Guide

**Consistency vs Availability (CAP)**:
- Orders: prefer consistency (CP) — can't show wrong order status
- Search: prefer availability (AP) — stale results acceptable
- Inventory display: prefer availability (AP) — show "~10 left" is fine
- Inventory at purchase: prefer consistency (CP) — can't oversell

**Sync vs Async**:
- Catalog reads: sync (must return data)
- Order placement: sync for order creation, async for payment + notification
- Search indexing: async (eventual consistency acceptable)
- Fraud detection: can be async (post-order) for low-risk; sync check for high-risk

**Push vs Pull (Inventory Sync)**:
- Redis (source of truth for purchase) → Postgres (reconciliation): pull via batch job
- Product update → Elasticsearch: push via Kafka event

---

## Interview Tips

1. **Start with scale**: always clarify 1K vs 1M orders/day before drawing boxes
2. **Draw before talking**: whiteboard high-level first, then drill down
3. **State assumptions explicitly**: "I'll assume payment is sync for simplicity, but in prod I'd make it async"
4. **Proactively identify tradeoffs**: don't wait to be asked "but what about...?"
5. **Know the failure modes**: interviewers at staff level test whether you've operated systems, not just designed them
6. **Use real numbers**: "Redis handles 100K ops/sec" not "Redis is fast"
7. **Admit uncertainty correctly**: "I'd evaluate Kafka vs SQS based on whether we need replay and fan-out — let me design assuming we do"
