# 16 — Advanced Improvements: E-Commerce Platform

---

## Objective

Define advanced architectural improvements, staff-level engineering decisions, and future-state capabilities that move the e-commerce platform from functional to exceptional — across personalization, financial efficiency, global scale, and operational maturity.

---

## 1. Event Sourcing for Order History

### Current State (V2)

Orders stored as mutable rows — status updated in place. Audit trail: separate audit_events table.

### Advanced Improvement

Full event sourcing for the Order bounded context:

```
Events stored:
  OrderCreated { orderId, userId, items, amounts }
  PaymentCaptured { orderId, paymentId, amount }
  InventoryReserved { orderId, items }
  OrderShipped { orderId, trackingNumber, carrier }
  OrderDelivered { orderId, deliveredAt }
  ReturnRequested { orderId, reason }
  RefundIssued { orderId, amount }
```

**Benefits**:
- Complete temporal query: "What was the order state at 3:00 PM on Diwali?"
- Perfect audit trail for disputes and compliance
- Replay events to rebuild read models
- Debug production issues by replaying exact event sequence

**Costs**:
- Increased read complexity (must aggregate events to get current state)
- Eventual consistency in read models
- Schema migration for events is harder (events are immutable)

**When to adopt**: When dispute resolution complexity or regulatory audit requirements justify the operational cost.

---

## 2. CQRS at Full Scale

### Current State

Single Postgres handles catalog reads + order writes + inventory reads + inventory writes.

### Advanced Improvement

Dedicated read models per use case:

| Read Model | Technology | Optimized For |
|---|---|---|
| Product catalog | Elasticsearch + Redis | Full-text search, faceted filters |
| Order history | Postgres read replica (denormalized) | User's past orders, fast list |
| Inventory (display) | Redis counter | "N items left" — millisecond latency |
| Seller analytics | ClickHouse / BigQuery | Aggregations, time-series |
| Personalized feed | Redis sorted sets | Homepage product ranking |
| Fraud signals | Redis + DynamoDB | Sub-5ms fraud signal lookup |

Write commands → Postgres (source of truth) → Events → Read model projectors.

**Key insight**: Each query type gets the storage engine it deserves. No one engine is optimal for all.

---

## 3. Machine Learning Integration

### Personalized Recommendations

**Problem**: Generic "bestsellers" list has 2-3% click-through. Personalized recommendations: 8-15%.

**Approach**:
1. Collaborative filtering (users who bought X also bought Y)
2. Content-based filtering (products similar to what user viewed)
3. Hybrid: combine signals with weighted ensemble

**Data pipeline**:
```
User actions (view, click, add-to-cart, purchase) → Kafka → Feature Store (Redis + S3)
                                                           → Model training (Spark + Python)
                                                           → Model serving (TensorFlow Serving / SageMaker)
                                                           → Recommendation API (< 50ms SLA)
```

**A/B testing**: 50% users get ML recommendations, 50% get rule-based. Measure conversion lift.

### ML-Based Fraud Detection

Beyond rule-based scoring:

**Features fed to model**:
- User tenure (days since account created)
- Historical purchase pattern (avg amount, frequency)
- Current session behavior (time on page, navigation pattern)
- Device fingerprint risk score
- IP reputation (proxy/VPN detection)
- Graph features: shared device/card across accounts

**Model**: Gradient boosted tree (XGBoost) — fast inference, explainable decisions

**Output**: Fraud probability 0.0 to 1.0 → auto-approve / 3DS / manual review / auto-reject

### Dynamic Pricing

Adjust prices based on demand signals:
- Flash sale scarcity (price rises as stock depletes)
- Competitor price monitoring (crawler + ML)
- Demand forecasting (price promotions pre-announced events)

**Risk**: Price gouging perception; regulatory constraints in some markets.

---

## 4. Advanced Search

### Current (V2): Elasticsearch with basic relevance

### Advanced Improvements

**Vector Search (Semantic Search)**:
- Query "comfortable running shoes" should match "athletic footwear with cushioned sole"
- Elasticsearch dense_vector field + k-NN search
- Embedding model: sentence-transformers to encode product description + query
- Hybrid: BM25 (keyword) + k-NN (semantic) combined score

**Learning to Rank**:
- User clicks on result position 5 more than position 1 for a query → model learns
- LambdaMART or similar learning-to-rank model
- Training data: implicit feedback (clicks, purchases) from Kafka events

**Query Understanding**:
- Spell correction: "samsong phone" → "samsung phone"
- Query expansion: "TV" → also match "television", "smart TV"
- Entity recognition: "iPhone 15 blue 256GB" → extract brand, model, color, storage

---

## 5. Global Inventory Distribution

### Problem at Scale

Single warehouse → orders from far regions have 3-5 day shipping.

### Solution

Multi-warehouse routing:

```
Order placed for product X:
  → Inventory routing service checks:
      - Which warehouses have stock?
      - What's shipping cost from each?
      - What's estimated delivery time?
      - Which minimizes cost + meets SLA?
  → Select optimal warehouse
  → Reserve inventory in that warehouse
  → Route fulfillment order to that warehouse
```

**Complexity added**:
- Inventory now fragmented across warehouses → harder to aggregate "total available"
- Transfer orders: move inventory between warehouses based on demand forecast
- Demand forecasting: predict which products to pre-position in which warehouses

---

## 6. Seller Financial Services

### Problem

Small sellers have cash flow issues between when they ship and when they get paid (settlement T+7).

### Advanced Feature: Seller Capital

- Analyze seller's sales history (12 months)
- Offer short-term working capital loan (against expected settlements)
- Risk model: default probability based on seller performance, return rate, GMV trend
- Repayment: automatically deducted from future settlements

**Why this matters for platform**: massive monetization opportunity; builds seller loyalty.

---

## 7. Chaos Engineering

### Goal

Build confidence that the system recovers from failures automatically, not just theoretically.

### Implementation

**Chaos Monkey (Simian Army)**:
- Randomly terminate pods in production during business hours
- Verify: did auto-scaling recover? Did health checks route traffic away?

**Chaos experiments (Gremlin / Chaos Mesh)**:
- Kill Postgres replica → verify reads fall back to primary
- Inject 500ms latency in payment gateway calls → verify circuit breaker opens
- Kill 1 Kafka broker → verify replication handles it
- Saturate Redis CPU → verify inventory falls back to DB

**Practice**: monthly chaos game days. SRE + engineering run scheduled experiments.

---

## 8. Data Mesh for Analytics

### Current State

Centralized data warehouse (Redshift/BigQuery) owned by data team.

### Problem at Scale

- Each team needs analytics on their domain
- Data team becomes bottleneck
- Schema changes in source → break centralized warehouse

### Advanced Improvement: Data Mesh

Each domain team owns their own data product:
- Orders team: publishes `order_analytics` dataset
- Catalog team: publishes `product_performance` dataset
- Payments team: publishes `payment_analytics` dataset

Data products: versioned, documented, SLA-backed.

Consumers (business, data science, marketing) subscribe to data products, not raw tables.

---

## 9. Progressive Web App (PWA) and Mobile Performance

Advanced front-end improvements with backend implications:

**App Shell + Service Worker**:
- Static app shell cached in browser
- Product pages: server-side rendering for SEO + fast first paint
- API: GraphQL with persisted queries (reduce payload size)

**Image Optimization**:
- WebP/AVIF format served based on browser Accept header
- Responsive images: different resolution per device width
- Lazy loading: only load images in viewport

**Backend implications**:
- BFF (Backend for Frontend) layer aggregates multiple API calls into one
- Reduces mobile data usage
- GraphQL subscriptions for real-time order tracking (WebSocket fallback)

---

## 10. Architecture Self-Critique

### Weaknesses

| Weakness | Consequence | Mitigation |
|---|---|---|
| Kafka adds operational complexity | Consumer lag, DLQ management | Managed MSK; strong SRE culture |
| Elasticsearch eventual consistency | Stale search results (seconds) | Acceptable; document the SLO |
| Redis as inventory truth | Data loss risk on Redis failure | AOF persistence + replication |
| Multi-service saga complexity | Hard to debug distributed failures | Distributed tracing + correlation IDs |
| ML pipeline adds latency | Recommendation API > 100ms | Pre-compute and cache; not real-time |

### Scaling Limits

| Component | Limit | Next Step |
|---|---|---|
| Postgres single primary | ~50K writes/min | Shard by user_id or seller_id |
| Elasticsearch | ~10B docs, ~10 nodes | Dedicated indexing nodes; tiered storage |
| Redis Cluster | ~300 GB memory | Tiered: hot data in Redis, warm in disk-backed store |
| Single Kafka cluster | ~1M msg/sec | Multi-cluster, MirrorMaker replication |

### Tech Debt Risks

1. **No event sourcing early**: retrofitting event sourcing onto existing order system is painful migration
2. **Shared DB across modules**: if skipped module isolation, cross-module queries make service extraction hard
3. **Missing idempotency**: adding idempotency to existing high-traffic endpoints requires careful migration
4. **Hardcoded pricing logic**: pricing complexity grows; needs dedicated Pricing Service with rules engine

### FAANG Interviewer Challenges

- *"Redis for inventory is risky — what if Redis loses data?"* → AOF persistence, sync replication, periodic Postgres reconciliation; Redis losing data = stock drift, not orders lost
- *"Eventual consistency in search means users see wrong stock status"* → Correct; document as acceptable; display shows "limited stock" not exact number; purchase path is always accurate (Redis atomic)
- *"Your Saga for order → payment → inventory is complex — what's the alternative?"* → 2PC (two-phase commit) across services; worse — blocking, single point of failure; Saga with compensation is correct tradeoff
- *"How do you test distributed failure scenarios?"* → Chaos engineering, contract testing, consumer-driven contract tests (Pact)
