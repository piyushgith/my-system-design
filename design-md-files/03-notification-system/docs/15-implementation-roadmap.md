# 15 — Implementation Roadmap: Notification System

---

## Objective

Define a phase-wise, realistic implementation plan from MVP to full production scale. Each phase is scoped to deliver a working, deployable system that provides business value while managing technical complexity and team scale.

---

## Guiding Principles

- Ship working software at each phase — no phase ends with a half-built system
- Each phase is independently deployable and rollback-safe
- Defer complexity to the phase where it's actually needed
- Architecture evolves organically — never overengineer Phase 1 for Phase 4 problems

---

## Phase 0: Foundation (Weeks 1–3)

### Goal
Stand up the core infrastructure and basic single-channel notification delivery (email only).

### Features
- Notification submission API (single user, immediate)
- Email delivery via SendGrid only
- Basic template storage (no versioning, no UI)
- Synchronous delivery (no Kafka yet — direct call from API)
- Delivery status stored in PostgreSQL

### Architecture
```
Producer → Notification API → Template Service → SendGrid
                           ↓
                     PostgreSQL (notification_requests + delivery_attempts)
```

### Infra
- 1 Spring Boot application (monolith)
- 1 PostgreSQL instance (no partitioning)
- Docker Compose for local dev
- Basic CI: compile, unit tests, deploy to staging

### Team
- 1 backend engineer (+ infra support)

### Risks
- Direct API call to SendGrid blocks Notification API thread during provider response
- No retry mechanism — failed deliveries are permanently lost
- No idempotency — duplicate submissions create duplicate emails

### Success Criteria
- Internal team can send email notifications programmatically
- Delivery status queryable via API
- Staging environment operational

---

## Phase 1: MVP (Weeks 4–8)

### Goal
Production-grade email delivery with async processing, retries, and basic idempotency.

### Features
- Kafka integration (Outbox pattern)
- Email Dispatcher as separate consumer
- Retry logic with exponential backoff (3 attempts)
- Idempotency key support
- User notification preferences (email on/off per category)
- Basic template versioning (v1, v2 per template)
- Delivery log (PostgreSQL `delivery_attempts`)
- Basic Grafana dashboard: delivery success rate, error rate

### Architecture Evolution
```
Producer → Notification API → Outbox (PG) → Kafka → Email Dispatcher → SendGrid
                           ↓
                     PostgreSQL (partitioned)
                     Redis (preference cache + idempotency keys)
```

### Infra
- Kafka: 1 broker (dev), 3 brokers (prod minimum)
- Redis: single instance (no cluster yet)
- PostgreSQL: monthly partitions enabled
- Kubernetes: basic deployment + HPA
- CI/CD: GitHub Actions → ECR → Argo CD

### Team
- 2 backend engineers + 1 DevOps

### Risks
- Single Redis instance is a SPOF for preference cache
- No circuit breaker → provider outage causes cascading retries
- SMS and push not yet available — teams that need SMS must wait for Phase 2

### Success Criteria
- Email notifications delivered in < 5 seconds for transactional
- Failed deliveries retried up to 3 times
- Duplicate submissions detected and rejected
- Preference opt-out respected within 1 hour

---

## Phase 2: Multi-Channel (Weeks 9–16)

### Goal
Support all four channels (Email, SMS, Push, In-App) with independent dispatchers.

### Features
- SMS Dispatcher (Twilio)
- Push Dispatcher (Firebase FCM + APNs)
- In-App Dispatcher + inbox API
- Multi-channel preference matrix (channel × category)
- Channel fallback: if push fails → SMS
- Quiet hours enforcement
- DLQ (Dead Letter Queue) with ops dashboard
- Circuit breaker on all external provider calls

### Architecture Evolution
```
Fanout Service extracts from monolith:
  notification.request.submitted → Fanout → per-channel dispatch topics → Channel Dispatchers

Separate services:
  email-dispatcher, sms-dispatcher, push-dispatcher, inapp-dispatcher
  preference-service (with Redis cache)
  template-service (with versioning)
```

### Infra
- Redis Cluster (3 nodes, HA)
- Kafka: 30–60 partitions per dispatch topic
- KEDA for dispatcher autoscaling
- Prometheus + Grafana full monitoring stack
- PagerDuty alerting integrated

### Team
- 4–5 backend engineers + 1 DevOps

### Risks
- Fanout service is now a critical bottleneck — single failure drops all channels
- Push notification device token management requires Device Registry service (dependency)
- SMS compliance (DND check for India) must be built before production SMS launch

### Success Criteria
- All 4 channels operational
- Preference matrix respected
- DLQ visible to ops team with manual requeue capability
- OTP fallback: push → SMS → email working
- p99 transactional delivery < 5 seconds across all channels

---

## Phase 3: Scale + Campaigns (Weeks 17–28)

### Goal
Support batch campaigns to 50M+ users with isolated transactional path.

### Features
- Batch Campaign API + Campaign Management UI
- Campaign Fanout Service (isolated from real-time Fanout)
- Campaign throttling (configurable RPS)
- Priority lanes in Kafka (CRITICAL + HIGH vs NORMAL + LOW)
- Template A/B testing (basic: random variant assignment)
- Scheduled notifications (Scheduler Service)
- ClickHouse for delivery analytics
- Campaign progress dashboard
- Webhook callbacks for producers

### Architecture Evolution
```
Campaign API → campaign.batch.submitted → Campaign Fanout
                                       ↓
                          Per-user notification.request.critical
                          Per-user notification.request.batch
                                       ↓
                          Separate Fanout instances per priority lane
```

### Infra
- ClickHouse cluster (2-node minimum)
- Scheduler Service (StatefulSet, leader election)
- Separate Kafka topics for priority lanes
- Increased broker count (9 nodes, 3 AZs)

### Team
- 6–8 engineers (Platform Core, Messaging, Growth, Data)

### Risks
- Campaign Fanout producing 50M events/hour can saturate Kafka disk
- ClickHouse introduces new ops skill requirement
- Priority lane separation requires Fanout Service refactor (potential disruption)

### Migration Risk Mitigation
- Priority lanes deployed as new topics; old single topic maintained in parallel
- Fanout Service uses feature flag to route to new topics for 1% of traffic initially
- Rollback: flip feature flag to 0%

### Success Criteria
- 10M-user campaign delivered without degrading transactional OTP SLO
- Campaign completion observable in real time
- Analytics: per-campaign delivery rate, open rate available within 1 hour

---

## Phase 4: Intelligence + Optimization (Weeks 29–40)

### Goal
Add ML-assisted delivery optimization, compliance automation, and advanced operational tooling.

### Features
- Optimal send time: ML model predicts best time per user (maximize open rate)
- Smart channel selection: route to channel with highest historical engagement per user
- Automated DND registry compliance (India, other regions)
- GDPR right-to-erasure automation (self-service)
- Notification fatigue suppression: don't send more than N notifications/user/day across categories
- Self-service template management UI (marketing team manages templates without engineering)
- Multi-region support (Active-Passive DR)
- Delivery analytics API for producer teams

### Architecture Evolution
```
ML Service → Engagement Score per user per channel
           → Optimal Send Time Window per user
           
Fanout Service queries ML Service (async, low priority):
  "When to send?" → schedule via Scheduler
  "Which channel?" → override default channel selection
```

### Infra
- ML model serving (Python Flask or TensorFlow Serving)
- DR region setup (Kafka MirrorMaker 2, PostgreSQL cross-region replica)
- Self-service ops tooling (internal Backstage portal)

### Team
- 10+ engineers (Platform Core, ML/Data, Security, Compliance)

### Risks
- ML model feedback loop: if it deprioritizes SMS, users with poor push engagement stop getting notifications
- Optimal send time conflicts with urgent transactional notifications (ML bypass required)
- Multi-region adds significant operational complexity — ensure DR runbooks exist before going live

### Success Criteria
- OTP delivery SLO maintained at 99.9%
- Campaign open rate improves measurably (baseline A/B test comparison)
- GDPR erasure requests processed within 24 hours automatically
- DR failover drill completed successfully

---

## V3 Scaling Roadmap (Beyond Week 40)

### Target Scale: 1M+ notifications/sec

| Component | Change |
|-----------|--------|
| Kafka | 18-broker cluster, 120+ partitions on high-volume topics |
| PostgreSQL | Horizontal sharding (4–8 shards by user_id) |
| Fanout | 100+ pod fleet with virtual thread Spring Boot 3.x |
| Email Dispatch | Multi-account pools across 4+ SendGrid sub-accounts |
| Push | Native FCM batch API (500 tokens/call) fully utilized |
| Redis | 9-node Redis Cluster (3 shards × 3 replicas) |
| Multi-region | Active-Active (3 regions), GeoDNS routing |

---

## Phase Summary Table

| Phase | Duration | Key Deliverable | Team Size | Architecture |
|-------|----------|----------------|-----------|-------------|
| 0 | 3 weeks | Email delivery working | 1 engineer | Monolith |
| 1 | 5 weeks | Async email, retries, idempotency | 2 engineers | Monolith + Kafka |
| 2 | 8 weeks | All 4 channels, preferences, DLQ | 5 engineers | Distributed services |
| 3 | 12 weeks | Batch campaigns, priority lanes, analytics | 8 engineers | Full event-driven |
| 4 | 12 weeks | ML optimization, compliance, DR | 10+ engineers | Full production |

---

## Interview Discussion Points

- Why not build all 4 channels in Phase 1?
  - SMS compliance (DND), push device token infrastructure, and in-app inbox are each non-trivial — forcing all 4 channels into Phase 1 creates a 6-month MVP with no shipped value.
- How do you avoid the trap of overbuilding Phase 0?
  - Phase 0 is intentionally simple. The only constraint: must not design Phase 0 in a way that requires rewriting it for Phase 1. Outbox pattern from Phase 1 can be retrofitted; API contract is designed for Phase 3 (priority, category, expiry are all present from day 1).
- What would cause you to skip phases?
  - Business pressure: if a major client requires SMS on day 1, Phase 2 becomes Phase 0. Architecture stays the same — just the delivery order changes.
