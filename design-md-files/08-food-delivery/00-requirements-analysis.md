# 00 — Requirements Analysis: Food Delivery Platform

---

## Objective

Define the complete functional and non-functional requirements for a food delivery platform at the scale of Swiggy, Zomato, or DoorDash. Establish scale estimates, traffic patterns, capacity planning, and key constraints before any architectural decision is made. This document serves as the foundation — every architectural choice made later must trace back to a requirement defined here.

---

## 1. Functional Requirements

### 1.1 Customer-Facing Features

| # | Feature | Priority | Notes |
|---|---------|----------|-------|
| F1 | Browse restaurants by city, cuisine, rating, delivery time | P0 | Elasticsearch-backed |
| F2 | Search restaurants and menu items by keyword | P0 | Full-text search |
| F3 | View restaurant details: menu, hours, ratings, ETA | P0 | Cached reads |
| F4 | Add items to cart, apply coupon, see total | P0 | Cart is session-scoped |
| F5 | Place order with payment (card, wallet, UPI, COD) | P0 | Idempotent, saga-orchestrated |
| F6 | Real-time order tracking with delivery partner location | P0 | WebSocket / SSE |
| F7 | Order history and reorder functionality | P1 | Read-heavy |
| F8 | Rate and review restaurants and delivery partners | P1 | Async ingestion |
| F9 | Apply promotions, coupons, loyalty points | P1 | Fraud-prone — needs controls |
| F10 | Save multiple delivery addresses | P1 | — |
| F11 | Scheduled delivery (order now, deliver at a time) | P2 | Complex saga |
| F12 | Group ordering (multiple users, one order) | P2 | Complex coordination |

### 1.2 Restaurant Partner Features

| # | Feature | Priority | Notes |
|---|---------|----------|-------|
| R1 | Restaurant onboarding (registration, document upload, zone mapping) | P0 | Admin-verified |
| R2 | Menu management (add/edit/delete items, categories, photos, prices) | P0 | Invalidates search cache |
| R3 | Accept or reject incoming orders within a time window | P0 | Timeout = auto-reject |
| R4 | Mark order as prepared and ready for pickup | P0 | Triggers delivery assignment |
| R5 | Set restaurant availability (open/closed, estimated prep time) | P0 | — |
| R6 | View earnings, payouts, order history | P1 | Analytics service |
| R7 | Manage promotions and discounts | P2 | — |

### 1.3 Delivery Partner Features

| # | Feature | Priority | Notes |
|---|---------|----------|-------|
| D1 | Accept/reject delivery assignments | P0 | SLA: 30s window |
| D2 | Real-time location sharing with platform | P0 | Every 5s push to Redis GEO |
| D3 | Mark food picked up from restaurant | P0 | Order state transition |
| D4 | Mark order delivered | P0 | Triggers payment settlement |
| D5 | View earnings, trips, ratings | P1 | — |
| D6 | Delivery zone preferences | P2 | — |

### 1.4 Platform / Admin Features

| # | Feature | Priority |
|---|---------|----------|
| A1 | Fraud detection for payments and coupons | P0 |
| A2 | Restaurant approval and compliance management | P1 |
| A3 | Dynamic surge pricing configuration | P1 |
| A4 | City-level analytics and operations dashboard | P1 |
| A5 | Manual dispatch override | P1 |

---

## 2. Non-Functional Requirements

### 2.1 Performance

| Requirement | Target | Notes |
|------------|--------|-------|
| Order placement latency | < 2 seconds (P99) | End-to-end saga initiation |
| Restaurant search latency | < 500 ms (P99) | Elasticsearch + cache |
| Real-time tracking update lag | < 5 seconds | Driver location push interval |
| Menu page load | < 1 second (P99) | Heavily cached |
| Payment processing | < 3 seconds | Gateway-dependent |
| Order status update propagation | < 2 seconds | Kafka consumer lag bounded |

### 2.2 Availability

| Service | Target | Notes |
|---------|--------|-------|
| Order placement | 99.99% | < 52 min downtime/year |
| Search / browse | 99.9% | < 8.7 hours/year |
| Real-time tracking | 99.9% | Degraded = show last known location |
| Payment service | 99.99% | Critical — PCI DSS scope |
| Restaurant portal | 99.9% | — |
| Notification service | 99.5% | Best-effort |

### 2.3 Consistency

- **Order state**: Strong consistency required. An order must never be in two states simultaneously.
- **Payment state**: Exactly-once semantics. Double charge is a catastrophic failure.
- **Driver location**: Eventual consistency acceptable. 5-second staleness is fine.
- **Menu catalog**: Eventual consistency acceptable. Cache TTL of 5 minutes tolerable.
- **Search index**: Eventual consistency. Index lag up to 30 seconds tolerable.

### 2.4 Durability and Reliability

- Zero order data loss. Orders must survive any single-node failure.
- Payment events must be durable — Kafka with replication factor 3, min ISR 2.
- Active orders must survive an Order Service restart (state stored in DB, not memory).

### 2.5 Scalability

- Must handle 10x normal load during peak lunch (12–2 PM) and dinner (7–10 PM) windows.
- Must support multi-city expansion without service redesign.
- Must scale Elasticsearch independently from order processing.

---

## 3. Assumptions

- Platform operates in a single country initially; multi-country is a V3 concern.
- All monetary values are stored in minor units (paise, cents) to avoid floating-point errors.
- Delivery partner location updates are initiated by the partner app — not polled by server.
- A restaurant can only service orders within a configurable delivery radius.
- Cart state is ephemeral (Redis) and not persisted beyond session; order is the durable record.
- Restaurant menus are relatively stable — updates happen a few times per day, not per minute.
- COD (Cash on Delivery) is a supported payment method — this has implications for order flow (no payment saga for COD).

---

## 4. Constraints

- Payment processing must comply with PCI DSS — raw card data never touches platform servers.
- Delivery partner location data is sensitive — cannot be exposed directly to customers beyond coarse position.
- Restaurant financial data must be isolated — restaurant A cannot access restaurant B's data.
- Regulatory: food safety certifications must be stored per restaurant.
- Mobile-first platform: APIs must be bandwidth-efficient.

---

## 5. Scale Estimates

### 5.1 User and Entity Counts

| Entity | Count |
|--------|-------|
| Registered users | 100M |
| Monthly active users | 30M |
| Daily active users | 10M |
| Registered restaurants | 500K |
| Active restaurants (daily) | 150K |
| Delivery partners (registered) | 1M |
| Active delivery partners (peak) | 200K |
| Menu items (total) | 50M (avg 100 items × 500K restaurants) |

### 5.2 Order Volume

| Metric | Value | Calculation |
|--------|-------|-------------|
| Orders per day | 5M | Industry estimate |
| Average order duration (placement to delivery) | 35 minutes | — |
| Peak lunch window (12–2 PM, 2 hours) | 1.5M orders | 30% of daily volume |
| Peak lunch RPS (order placement) | ~210 RPS | 1.5M / 7200s |
| Normal order placement RPS | ~58 RPS | 5M / 86400s |
| Peak surge multiplier | 10x | Including retries and browsing |

### 5.3 Read Traffic

| Operation | Daily Requests | Peak RPS |
|-----------|---------------|---------|
| Restaurant search | 50M | 5,000 |
| Menu page views | 80M | 8,000 |
| Order status polling | 100M (avg 20 polls/order) | 12,000 |
| Driver location updates (writes from driver app) | 200K partners × 12 updates/min × 120 min active = 288M/day | 50,000 writes |
| Driver location reads (customer app) | ~5M active orders × 12 reads/min = 60M reads/hr at peak | 100,000 reads |

### 5.4 Storage Estimates

#### PostgreSQL (Orders)

| Table | Row Size | Daily Volume | Monthly | 5-Year |
|-------|----------|-------------|---------|--------|
| orders | 2 KB | 5M rows = 10 GB/day | 300 GB | 18 TB |
| order_items | 0.5 KB | 20M rows (avg 4 items) = 10 GB/day | 300 GB | 18 TB |
| payments | 1 KB | 5M rows = 5 GB/day | 150 GB | 9 TB |

Total transactional DB: ~3–5 TB active, ~50 TB with 5-year history (partitioned + archived).

#### Elasticsearch (Search Index)

| Index | Documents | Avg Doc Size | Total Size | With Replicas (2x) |
|-------|-----------|-------------|------------|-------------------|
| restaurants | 500K | 10 KB | 5 GB | 10 GB |
| menu_items | 50M | 2 KB | 100 GB | 200 GB |
| Total | — | — | 105 GB | 210 GB |

#### Redis

| Key Space | Cardinality | Entry Size | Total |
|-----------|------------|------------|-------|
| Active order state cache | 500K concurrent | 2 KB | 1 GB |
| Driver GEO (city-level GEO sets) | 200K drivers | 50 bytes | 10 MB |
| Session tokens | 10M active | 500 bytes | 5 GB |
| Restaurant catalog cache | 150K active | 20 KB | 3 GB |
| Menu cache | 150K restaurants × 1 menu | 100 KB | 15 GB |

Total Redis: ~25–30 GB across all namespaces (manageable in a single large Redis cluster or split by namespace).

---

## 6. Read/Write Patterns

```
┌─────────────────────────────────────────────────────────────────┐
│                    TRAFFIC CHARACTERIZATION                      │
│                                                                  │
│  Read-Heavy (>90% reads):                                        │
│  ├── Restaurant catalog browsing                                 │
│  ├── Menu page views                                             │
│  ├── Search queries                                              │
│  └── Order status reads (polling / SSE)                          │
│                                                                  │
│  Write-Heavy / Mixed:                                            │
│  ├── Driver location updates (high-frequency writes to Redis)    │
│  ├── Order state transitions (critical, low-frequency)           │
│  └── Review submissions (moderate)                               │
│                                                                  │
│  Write-Once, Read-Many:                                          │
│  ├── Orders (created once, read many times)                      │
│  ├── Payments (created once, rarely modified)                    │
│  └── User profiles (rarely updated)                              │
└─────────────────────────────────────────────────────────────────┘
```

---

## 7. Traffic Pattern Analysis

### 7.1 Temporal Patterns

```
Orders per hour (normalized, peak = 10x baseline)

12AM  |██░░░░░░░░░░|  10%
 3AM  |█░░░░░░░░░░░|   5%
 6AM  |██░░░░░░░░░░|  10%
 9AM  |████░░░░░░░░|  30%  (breakfast)
12PM  |██████████░░| 100%  (LUNCH PEAK)
 2PM  |████████░░░░|  70%
 5PM  |████░░░░░░░░|  35%
 7PM  |███████████░|  90%  (DINNER PEAK)
 9PM  |██████░░░░░░|  50%
11PM  |███░░░░░░░░░|  25%
```

### 7.2 Geographic Distribution

- ~60% of orders come from top 10 cities.
- Each city has independent restaurant and delivery partner pools.
- City-level sharding is a natural partitioning boundary.

---

## 8. Latency Expectations by Operation

| Operation | Expected Latency | Why Strict? |
|-----------|-----------------|-------------|
| Search restaurant | < 500 ms | Direct conversion impact |
| Place order (API response) | < 2 seconds | User drops off above 2s |
| Payment confirmation | < 3 seconds | User anxiety; SLA with payment gateway |
| Restaurant notification | < 5 seconds | Order acceptance window management |
| Driver assignment | < 30 seconds from order confirmation | SLA breach = bad NPS |
| Order tracking update | < 5 seconds | Real-time expectation |
| Cancel order response | < 2 seconds | — |

---

## 9. Availability Targets and SLA Decomposition

Using the failure budget model:

- **Overall system availability**: 99.9% = 8.76 hours downtime/year
- **Order service**: Must be 99.99% (< 52 min/year) — it is the revenue path
- **Search/browse**: 99.9% is acceptable — users can retry, downtime is visible but not catastrophic
- **Payment**: 99.99% — any downtime is direct revenue loss and trust damage

### 9.1 Dependency Chain Risk

If Order Service calls Payment, Restaurant, and Delivery synchronously, the composite availability degrades:

```
Composite = 99.99% × 99.9% × 99.9% × 99.9% = ~99.67%
```

This is why **async saga orchestration** is chosen — it decouples the availability chain.

---

## 10. Constraints Summary

| Constraint | Impact on Design |
|-----------|-----------------|
| No raw card data on platform | Tokenization via payment gateway; PCI DSS scope reduction |
| Order state must be strongly consistent | PostgreSQL with optimistic locking; not eventual |
| Driver location must be fast | Redis GEO, not PostgreSQL geography columns |
| Menu search must support fuzzy matching | Elasticsearch, not PostgreSQL LIKE queries |
| System must survive restaurant going offline | Saga timeout + compensating cancellation flow |
| Peak load = 10x normal | HPA on Kubernetes; Redis caching of catalog |

---

## Interview-Level Discussion Points

1. **Why separate functional from non-functional requirements?** NFRs often drive the majority of architectural complexity. A food delivery system with 10 orders/day could be a monolith. At 5M/day, it cannot be.

2. **Why does the availability target for payments differ from search?** Payment downtime = direct financial loss + trust erosion. Search downtime = poor UX but no direct revenue loss per incident.

3. **Why are driver location updates the highest write volume?** 200K partners × 12 updates/min = 144M writes/hour. This dominates all other write traffic and requires a purpose-built store (Redis GEO), not a relational DB.

4. **What is the capacity planning implication of 5-year order history?** 50 TB of data requires a tiered storage strategy: hot (PostgreSQL, 90 days), warm (object store + Parquet, 1 year), cold (S3 Glacier equivalent, 5+ years).

5. **How does COD vs prepaid affect the saga design?** COD eliminates the payment step from the saga but introduces a cash reconciliation problem on delivery. The saga must have a branch for payment type.
