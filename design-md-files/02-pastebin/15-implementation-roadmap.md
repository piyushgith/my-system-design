# 15 — Implementation Roadmap: Pastebin / Code Sharing Platform

---

## Objective

Define a phased implementation plan that evolves from a working MVP to a production-grade, globally scalable system. Each phase is independently deployable, adds measurable value, and increases complexity deliberately.

---

## Phase Overview

```
MVP   → Core paste creation and sharing
V1    → Production hardening (auth, cleanup, caching)
V2    → Platform features (versioning, API access, analytics)
V3    → Scale and global distribution
V4    → Enterprise and advanced features
```

---

## MVP (Weeks 1–3): Core Functionality

**Goal:** Functional paste creation and retrieval. No auth. No expiry. No advanced features.

### Features
- Anonymous paste creation (text/code)
- Paste retrieval by short key
- Raw content endpoint
- Basic rate limiting (in-process)
- Language selection (syntax hint stored, not highlighted server-side)
- Hardcoded expiry options (1h, 1d, 1w, 1m, never)
- Simple public/unlisted access levels

### Architecture
```
Single Spring Boot app
PostgreSQL (single instance)
S3 / MinIO for content storage
No Redis, No Kafka
```

### Key Implementation Tasks
1. Short key generation (random Base62, retry on collision)
2. Content routing: inline (<1 KB) vs S3 (≥1 KB)
3. REST API: POST /pastes, GET /pastes/{key}, GET /raw/{key}
4. Flyway migration: create `paste.pastes` table
5. Basic input validation: size limit, language whitelist
6. Local dev setup: docker-compose with PostgreSQL + MinIO

### Success Criteria
- Paste creation works end-to-end
- Paste retrieval returns correct content
- Content > 1 KB goes to S3
- Key collisions handled gracefully

### Risks
- S3 latency for large pastes may be unexpected — profile early
- Short key generation may be slow if naive retry loop — optimize

---

## V1 (Weeks 4–8): Production Hardening

**Goal:** Add authentication, expiry cleanup, Redis caching, and deploy to real infrastructure.

### Features Added
- User registration and login (JWT)
- Private pastes (owner-only access)
- Password-protected pastes (bcrypt)
- Paste deletion by owner
- Expiry cleanup (DB poll job + soft delete)
- Redis caching (paste metadata + content for small pastes)
- Structured logging with correlation IDs
- Basic Prometheus metrics + Grafana dashboard
- Health check endpoints (/actuator/health)
- Rate limiting via Redis sliding window

### Architecture Evolution
```
Spring Boot (2 instances)
PostgreSQL Primary + 1 Read Replica
Redis (single instance → cluster in staging)
S3
No Kafka yet (sync cleanup job)
ALB + auto-scaling
Kubernetes (staging + prod namespaces)
```

### Key Implementation Tasks
1. Identity module: JWT issuance, Spring Security filter chain
2. Expiry schedule table + cleanup job (scheduled task, every 60 seconds)
3. Redis integration: Lettuce client, cache-aside pattern
4. Negative caching for missing paste keys
5. PgBouncer connection pooler in front of PostgreSQL
6. CI/CD: GitHub Actions → ECR → Helm deploy to EKS
7. Flyway migration: add `identity.users` table, indexes
8. Terraform: EKS, RDS, ElastiCache, S3, ALB
9. Prometheus + Grafana setup (EKS monitoring namespace)
10. Alert rules: read availability, create latency, DB connections

### Success Criteria
- Auth works (register → login → create private paste → retrieve)
- Expired pastes return 410 after cleanup processes them
- Redis hit ratio > 80% for popular pastes in staging
- CI/CD pipeline deploys automatically on merge to main
- Zero-downtime rolling deploy verified

### Risks
- JWT secret rotation requires token refresh strategy — plan early
- DB migration on existing data (if needed) — test on staging with real data
- Redis cluster vs single instance: run single for MVP V1, cluster before prod

---

## V2 (Weeks 9–16): Platform Features

**Goal:** Add Kafka event streaming, analytics, abuse detection, API access, and paste versioning.

### Features Added
- Kafka integration (replace sync cleanup with event-driven)
- Outbox pattern for reliable event publishing
- Analytics: view counts, user dashboard (top pastes, views per day)
- Abuse reporting (user-initiated) + basic hash-based detection
- API key management (programmatic access)
- Paste versioning (edit paste, retain history)
- Fork paste
- CDN integration (CloudFront for public pastes)
- CDN cache invalidation on delete/expire
- Content deduplication (SHA-256 hash check)
- Custom alias for authenticated users
- Cursor-based pagination for user paste list

### Architecture Evolution
```
Spring Boot (3-10 instances, HPA)
PostgreSQL Primary + 2 Read Replicas
Redis Cluster (3 shards)
Kafka (3 brokers, MSK or self-hosted)
S3 + CloudFront CDN
Outbox table + outbox poller
Analytics: separate read replica for analytics queries
Moderation: background scan consumer
```

### Key Implementation Tasks
1. Kafka topics: paste.created, paste.deleted, paste.expired, paste.viewed, paste.abuse-flagged
2. Outbox poller: separate thread, reads unpublished events, publishes to Kafka
3. Cleanup module: consume paste.expired → soft delete → publish paste.deleted
4. S3 cleanup consumer: consume paste.deleted → S3 DeleteObject
5. CDN invalidator consumer: consume paste.deleted → CloudFront CreateInvalidation
6. Analytics module: consume paste.viewed → batch aggregate → paste_stats update
7. Moderation: consume paste.created → fetch S3 content → hash blocklist check
8. API key endpoints: generate, list, revoke
9. Spring Security: API key authentication filter
10. paste_versions table + version creation on paste update
11. Schema Registry setup for Avro message schemas
12. DLQ consumer: alert on failed S3 cleanups

### Success Criteria
- Paste deletion triggers S3 cleanup and CDN invalidation within 60 seconds
- Analytics view count accurate within 5 minutes
- Kafka consumer lag < 1,000 for all consumer groups during load test
- API key creation and programmatic paste creation works
- Paste version history works: create → edit → view versions → diff

### Risks
- Outbox poller contention: only one instance should poll at a time (use distributed lock)
- Kafka schema evolution: agree on backward-compatible schema strategy before shipping
- CDN invalidation cost: model cost at expected deletion rate ($90/month at 6M/month)
- Abuse detection latency: async is fine, but ensure SLA for taking down flagged content

---

## V3 (Weeks 17–24): Scale and Global Distribution

**Goal:** Handle 10x traffic growth. Multi-region read availability. Performance optimization.

### Features Added
- Pre-signed S3 uploads (client-direct upload for large pastes)
- Multi-region deployment (us-east-1 write, eu-west-1 + ap-southeast-1 read)
- Counter-based short key generation (replace random with Snowflake-style for distributed nodes)
- In-process Caffeine cache (L0, for extreme hot keys)
- Stale-while-revalidate at CDN layer
- ML-based abuse detection (async, Hugging Face or custom model)
- TimescaleDB or ClickHouse for analytics (replace PostgreSQL read replica)
- Full-text search for user's own pastes (Elasticsearch integration)
- Canary deployments (Istio or weighted Ingress)
- Full distributed tracing (Jaeger / Grafana Tempo)
- SLO dashboards with error budget tracking

### Architecture Evolution
```
3 regions: write primary (us-east-1), read replicas (eu-west-1, ap-southeast-1)
S3 Cross-Region Replication
Kafka MSK (managed)
ClickHouse for analytics (separate cluster)
Elasticsearch for paste search
Istio service mesh (traffic management, mTLS)
OpenTelemetry full stack
```

### Key Implementation Tasks
1. Terraform multi-region: separate VPCs, RDS read replicas, ElastiCache per region
2. Pre-signed S3 upload flow: generate URL endpoint, confirm endpoint
3. Snowflake ID generator (or use a dedicated ID service)
4. L0 Caffeine cache with Resilience4j circuit breaker to Redis
5. ClickHouse: sink Kafka paste.viewed events, replace analytics PostgreSQL queries
6. Canary deployment automation: Prometheus query in CI/CD gate script
7. Jaeger Tempo: OpenTelemetry collector, trace storage
8. SLO alerting: error budget burn rate alerts (Google SRE book model)

### Success Criteria
- Read latency p99 from Tokyo → CloudFront edge: < 20ms for cached pastes
- System sustains 1,000 RPS reads without DB pressure (cache hit > 90%)
- Multi-region: us-east-1 primary failure → eu-west-1 reads unaffected (already reading from replica)
- Canary gate catches a regression in 10 minutes before full rollout

### Risks
- Cross-region DB replication lag: accept eventual consistency for reads (< 200ms typical)
- ClickHouse operational complexity: consider managed service (Altinity, ClickHouse Cloud)
- Snowflake ID migration: existing pastes have old UUID-based IDs — dual format during transition

---

## V4 (Beyond Week 24): Enterprise and Advanced

**Goal:** Enterprise tier, advanced platform features, high-compliance environments.

### Features Added
- Multi-tenancy: organization/team workspaces
- SAML/SSO for enterprise users
- ABAC (Attribute-Based Access Control) for fine-grained paste sharing
- Embed support: iFrame-embeddable paste viewer
- Webhook support: notify user when paste is viewed / expires
- Self-hosted option (on-premise Kubernetes deployment)
- Compliance: SOC2 audit trail, GDPR data export/deletion
- Advanced abuse: ML-based real-time scoring at API gateway level
- Collaborative annotation: comments on pastes
- Paste templates and snippet libraries

### Architecture Evolution
```
True multi-service architecture (if team > 30 engineers)
Dedicated Identity service (Keycloak or custom)
Dedicated Notification service (Kafka → email/webhook/Slack)
Compliance service (data export, retention, deletion)
Enterprise gateway (Kong with plugins)
Self-hosted Helm chart for on-premise deployment
```

---

## Risk and Complexity Summary per Phase

| Phase | Features | Complexity | Team Size | Infra Cost |
|-------|---------|-----------|----------|-----------|
| MVP | Core paste CRUD | Low | 1-2 | ~$50/month |
| V1 | Auth, cleanup, cache | Medium | 2-3 | ~$300/month |
| V2 | Kafka, analytics, CDN | High | 3-5 | ~$800/month |
| V3 | Multi-region, perf | Very High | 5-10 | ~$3,000/month |
| V4 | Enterprise, compliance | Extreme | 10-20 | ~$10,000+/month |

---

## What NOT to Build Before V2

Common over-engineering traps for Pastebin MVP:

| Premature Feature | Why to Wait |
|------------------|------------|
| Microservices | One team, one deployable. Monolith is fine. |
| Kubernetes | Docker Compose or single EC2 is enough for MVP |
| Kafka | Synchronous cleanup job works fine at 4 RPS write |
| Multi-region | Not needed until you have users on 2+ continents |
| ML abuse detection | Hash blocklist is 99% effective and infinitely simpler |
| GraphQL | REST is perfectly sufficient for Pastebin's API shape |
| Event sourcing | Paste is immutable — no event history needed for core |
| gRPC | No internal service-to-service calls in monolith phase |

---

## Interview Discussion Points

- Why would you NOT start with Kafka in the MVP, even though it's in the final architecture?
- How do you decide when to graduate from MVP to V1? What specific signal would trigger that?
- What is the biggest risk of going directly from MVP to V3? (Answer: skipping V1/V2 hardening — system won't survive production load without caching, connection pooling, retry handling)
- If you had a 2-engineer team, which phase would you deliver in 6 months?
- At what point does the modular monolith become a liability, and what is the trigger to extract the first microservice?
