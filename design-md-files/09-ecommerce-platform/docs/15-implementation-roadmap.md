# 15 — Implementation Roadmap: E-Commerce Platform

---

## Objective

Define a phased implementation plan from MVP to Taking-scale, with feature scope, architecture evolution, infrastructure requirements, risks, and team scaling considerations at each phase.

---

## Phase 0 — MVP (Weeks 1-6)

### Goal

Prove the product works. Small catalog, small team, small traffic. Speed of iteration > engineering perfection.

### Features

- Product catalog (list, detail)
- Basic search (Postgres full-text)
- User registration and login
- Shopping cart (session-based)
- Order placement (COD only)
- Order history
- Admin panel: add/edit products, view orders

### Architecture

- Single Spring Boot monolith
- Postgres (all data)
- Redis (sessions only)
- No Kafka
- No Elasticsearch
- Deployed on single EC2 or Railway/Render (simplest hosting)

### API

- REST, no versioning yet
- No rate limiting
- Basic JWT auth

### Infrastructure

- Single Postgres instance (no replica)
- Basic application monitoring (logs to stdout)
- Manual deployment (no CI/CD yet)

### Team

- 2 backend engineers
- 1 frontend engineer
- 1 designer

### Risks

- No inventory management → manual stock tracking
- No payment integration → cash on delivery only
- No redundancy → downtime during deploys

### Success Criteria

- 100 products, 50 orders/day, 5-second checkout, no data loss

---

## Phase 1 — V1: Core Commerce (Months 2-4)

### Goal

Real product for real customers. Payment online. Inventory tracked. Basic reliability.

### Features Added

- Online payment (Stripe/Razorpay integration)
- Real inventory management (track stock, low-stock alerts)
- Order status tracking (placed → shipped → delivered)
- Email notifications (order confirmation, shipping)
- Seller portal (multi-seller support, basic)
- Product images (S3 storage)
- Basic fraud check (address + velocity rules)
- Returns and refunds (manual process)

### Architecture Changes

- Modular monolith: separate packages for Catalog, Orders, Inventory, Payments, Notifications
- Redis for: sessions + cart (move cart out of DB)
- S3 + CloudFront for product images
- Outbox pattern for payment → order status sync
- Basic CI/CD (GitHub Actions → EC2)
- Postgres read replica (for analytics queries)

### Infrastructure

- 2 app instances behind ALB
- Postgres: primary + 1 read replica
- Redis: single node (ElastiCache)
- S3 + CloudFront
- Basic CloudWatch alerting

### Team

- 4 backend engineers
- 2 frontend engineers
- 1 DevOps

### Risks

- Payment integration complexity (webhook handling, idempotency)
- Inventory race conditions (single DB, no Redis atomics yet)
- Seller onboarding manual overhead

### Success Criteria

- 1,000 orders/day, payment success rate > 99%, inventory accurate within ±1 unit

---

## Phase 2 — V2: Scale Foundations (Months 5-9)

### Goal

Handle 10x growth. Extract bottlenecks. Introduce async processing.

### Features Added

- Elasticsearch-powered search (full-text, filters, autocomplete)
- Reviews and ratings
- Wishlist
- Discount codes and promotions
- Seller analytics dashboard
- Real-time order tracking (polling-based)
- Return automation
- Product recommendations (basic — "customers also bought")
- Flash sale support (basic — no virtual queue yet)

### Architecture Changes

- Extract Payments Service (compliance boundary, separate DB)
- Extract Notification Service (Kafka-backed, async)
- Kafka introduced: order events → notification service, search indexer
- Elasticsearch: product indexing via Kafka consumer
- Redis Cluster: inventory counters (atomic DECR for purchases)
- CQRS for order reads (separate read model, denormalized)
- PgBouncer: connection pooling
- API versioning: `/api/v2`

### Infrastructure

- Kubernetes (EKS) — replace EC2 instances
- 3 Postgres shards by seller_id (if write throughput demands it; evaluate first)
- Redis Cluster (3 nodes)
- Kafka (MSK, 3 brokers)
- Elasticsearch Cluster (3 nodes)
- CloudFront CDN for catalog pages (not just images)
- Separate monitoring namespace: Prometheus + Grafana

### Team

- 8 backend engineers (Catalog, Orders, Payments, Platform teams)
- 3 frontend engineers
- 2 DevOps/SRE
- 1 Data engineer (analytics pipeline)

### Risks

- Kafka operational complexity (consumer lag, schema evolution)
- Eventual consistency in search (5-10s lag for product updates)
- Distributed transaction complexity (payment + inventory + order)
- Team coordination overhead across services

### Success Criteria

- 10,000 orders/day, p99 checkout < 2s, search p95 < 500ms, zero oversells

---

## Phase 3 — V3: Flash Sales & Advanced Features (Months 10-18)

### Goal

Handle viral moments. Compete on product experience. Advanced personalization.

### Features Added

- Flash sale engine (virtual waiting room, token bucket)
- ML-based fraud detection (replace rule engine)
- Personalized recommendations (collaborative filtering)
- Seller fulfillment centers (multi-warehouse)
- Dynamic pricing
- Loyalty and rewards system
- Real-time order tracking (WebSocket-based)
- Mobile app APIs (GraphQL/BFF layer)
- A/B testing framework for product ranking

### Architecture Changes

- Flash sale service: Redis-primary purchase flow, Kafka-backed order creation
- Virtual waiting room: queue token system
- BFF (Backend for Frontend) layer for mobile apps
- GraphQL API alongside REST
- ML service: separate Python service for recommendations + fraud scoring
- Multi-warehouse inventory: routing logic by geography
- Feature flag service (LaunchDarkly or self-built)
- Multi-region: active-passive with Route 53 failover

### Infrastructure

- Multi-region Kubernetes (2 regions: primary + DR)
- 64-partition Kafka topics for flash sale throughput
- Elasticsearch: 6-node cluster with dedicated coordinating nodes
- Redis: 6-node cluster (3 masters + 3 replicas)
- Separate ML inference cluster (GPU instances for recommendations)

### Team

- 20 engineers (platform, catalog, orders, payments, recommendations teams)
- 4 SRE
- 2 ML engineers
- 1 Security engineer

### Risks

- Flash sale virtual queue UX complexity
- ML model latency (recommendation must be < 100ms)
- Multi-region data consistency
- Operational complexity at this scale

### Success Criteria

- 100,000 orders/day, flash sale: 100K concurrent users → zero oversells, < 5s to grant/deny

---

## Phase 4 — Taking Scale (Year 2+)

### Goal

Global platform. Multi-region active-active. Self-healing infrastructure.

### Features Added

- International expansion (multi-currency, multi-language, cross-border shipping)
- Advertising platform (sponsored products)
- Same-day delivery optimization
- Seller financial services (loans, early payouts)
- Marketplace insurance

### Architecture Changes

- Active-active multi-region (3+ regions)
- Global distributed inventory
- Event sourcing for order history (complete audit trail, temporal queries)
- Data mesh for analytics (each team owns their data product)
- Service mesh (Istio) for mTLS, observability, traffic management
- Chaos engineering (scheduled failure injection in staging)

### Infrastructure

- 3-5 AWS regions
- 500+ Kubernetes pods
- Distributed Kafka clusters with cross-region replication (MirrorMaker 2)
- Dedicated cache clusters per region

### Team

- 100+ engineers
- Dedicated SRE, security, platform, data teams
- On-call rotation: 24/7 global

---

## MVP to Taking: Architecture Evolution Summary

```
MVP → V1 → V2 → V3 → Taking
Monolith → Modular Monolith → Service Extraction → Multi-Region
Postgres only → + Redis + S3 → + Kafka + ES → + ML + Multi-region → Event Sourcing
Manual deploy → CI/CD → Kubernetes → Multi-region Kubernetes → Service Mesh
```

---

## Tradeoffs Per Phase

| Phase | Speed | Reliability | Scalability | Operational Complexity |
|---|---|---|---|---|
| MVP | High | Low | Low | Low |
| V1 | High | Medium | Low | Low |
| V2 | Medium | High | Medium | Medium |
| V3 | Low | High | High | High |
| Taking | Low | Very High | Very High | Very High |

---

## Interview Discussion Points

- **"Where would you start if you joined as the first backend engineer?"** → MVP: monolith, Postgres, get orders flowing. Don't architect for 1M users on day 1.
- **"When do you extract a service?"** → When you have a compliance boundary (payments), a scaling boundary (search), or an org boundary (separate team)
- **"How does team size drive architecture?"** → Conway's Law: services match team structure. 3 teams → 3-5 services maximum. More services than teams = chaos.
- **"What's the biggest tech debt risk?"** → Skipping idempotency in early phases; adding it later to a running system is painful
- **"What would you do differently in hindsight?"** → Implement outbox pattern from day 1; inventory Redis atomics from day 1; both are load-bearing and painful to retrofit
