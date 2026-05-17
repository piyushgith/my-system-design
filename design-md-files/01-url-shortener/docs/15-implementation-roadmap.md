# 15 — Implementation Roadmap: URL Shortener

---

## Objective

Define a phased implementation plan from MVP to production-scale, with team scaling, infrastructure evolution, and risk management at each phase.

---

## Phase 0: MVP (Weeks 1–4) — 1 Engineer

### Goal

A working URL shortener that can be demoed and used internally. No analytics, no authentication, no production SLAs.

### Features

- POST `/api/v1/urls` — create short URL
- GET `/{shortCode}` — redirect (302)
- Manual URL deletion (admin DB query)
- No analytics
- No rate limiting

### Architecture

```
Browser → Spring Boot (single instance) → PostgreSQL (single node)
                    ↓
                Redis (single node, optional)
```

### Infrastructure

- Single EC2 instance or Docker on local machine
- PostgreSQL: managed RDS (t3.micro)
- Redis: Elasticache (cache.t3.micro)
- No Kubernetes, no Kafka, no monitoring

### Deliverables

- Spring Boot application with `url`, `redirect` modules
- PostgreSQL schema: `short_urls` table
- Basic Redis integration for redirect caching
- Postman collection for API testing
- Docker Compose for local development

### Risks

- No authentication — any user can create URLs
- No rate limiting — abuse possible
- No monitoring — failures not detected

### Complexity: Low

---

## Phase 1: V1 — Production-Ready (Weeks 5–12) — 2–3 Engineers

### Goal

A production-ready service with authentication, basic analytics, rate limiting, and monitoring. Can handle 1M redirects/day.

### Features Added

- User registration and JWT authentication
- API key generation
- Custom alias support
- URL expiration (TTL-based)
- URL deletion (authenticated, owner-only)
- URL listing (dashboard)
- Basic analytics: click count per URL
- Rate limiting (per IP, per user)
- Health checks and basic monitoring

### Architecture

```
Browser → CloudFront CDN → AWS ALB
                              ↓
              Spring Boot (3 pods, Rolling Deploy)
                    ↓           ↓
              PostgreSQL     Redis Cluster
              (Multi-AZ)     (3 nodes)
                    ↓
              ClickHouse (single node, async via Kafka)
```

### Infrastructure

- EKS cluster (2 t3.large nodes minimum)
- RDS PostgreSQL Multi-AZ (db.t3.medium)
- ElastiCache Redis (cache.r6g.large, 3-node cluster)
- MSK Kafka (2 brokers)
- CloudFront distribution
- Basic Grafana + Prometheus monitoring

### New Modules

- `identity` module: User, JWT auth, API key management
- Analytics ingestion pipeline: Kafka → ClickHouse consumer
- Rate limiter: Redis token bucket via Lua script
- Expiration batch job: Spring `@Scheduled` task

### Deliverables

- Full REST API per 04-api-design.md
- Flyway migrations for all tables
- Kafka topic setup scripts
- Grafana dashboard: redirect SLA, cache hit rate
- Basic alerting: service down, error rate > 5%
- CI/CD pipeline (GitHub Actions → ECR → EKS)
- Environment separation: dev, staging, prod

### Architecture Evolution from MVP

- Added CDN layer
- Moved from single Redis to Redis Cluster
- Added Kafka for async analytics
- Added authentication module
- Added monitoring stack

### Risks

- Kafka operational complexity for small team
- ClickHouse administration learning curve
- EKS cost (~$200–400/month for small cluster)

### Complexity: Medium

---

## Phase 2: V2 — Scale (Months 4–9) — 4–6 Engineers

### Goal

Handle 100M redirects/day with < 50ms p99 latency globally. Add advanced features: geo-routing, A/B testing, detailed analytics, bulk API.

### Features Added

- Geo-routing (country-based redirect target)
- A/B testing split traffic support
- Advanced analytics dashboard (time-series, by country, by device, by referrer)
- Bulk URL creation API (async)
- URL tags and organization
- QR code generation
- Webhook notifications (on URL expiry, click threshold)
- Password-protected short URLs
- API rate limit tiers (Free, Pro, Enterprise)
- Multi-region deployment (US, EU)
- Trust & Safety: malware/phishing scan integration

### Architecture

```
Global Traffic
    ↓
Route 53 (geo-routing)
    ↓
US / EU / AP CloudFront

Per Region:
  ALB → Redirect Pods (10+ per region)
      → API Pods (3–5 per region)
      → Analytics Consumer Pods (KEDA)

Global:
  PostgreSQL Primary (US) → Read Replicas (EU, AP)
  Redis Cluster (per region)
  Kafka Cluster (per region, with MirrorMaker2)
  ClickHouse Cluster (3 shards, 2 replicas)
```

### Infrastructure Evolution

- Multi-AZ → Multi-Region (2 regions: US, EU)
- PostgreSQL read replicas in EU
- Regional Redis clusters
- Kafka MirrorMaker2 for cross-region replication
- ClickHouse cluster (distributed mode)
- KEDA for Kafka-driven consumer autoscaling
- OpenTelemetry + Jaeger for distributed tracing
- SLO monitoring with error budget dashboards

### New Modules

- `geo-routing` module: IP → Country → target URL resolution
- `abuse-detection` module: Safe Browsing API integration, blocklist
- `bulk-processing` module: async batch URL creation
- `notification` module: webhook + email on URL events

### Deliverables

- GeoIP MaxMind database integration
- Bulk URL creation API with async processing and polling
- Geo-routing UI in management dashboard
- A/B testing configuration UI
- Multi-region Terraform modules
- Canary deployment pipeline with automated rollback
- Full SLO dashboards with error budget burn rate

### Architecture Tradeoffs at V2

| Decision | Why | Tradeoff |
|---|---|---|
| Multi-region (2 regions) | EU GDPR + latency | 2x infrastructure cost |
| ClickHouse cluster | 100M clicks/day analytics | Significant operational overhead |
| Kafka MirrorMaker2 | Cross-region event streaming | Complex lag monitoring |
| KEDA autoscaling | Dynamic analytics throughput | Requires Kafka metrics exposure |

### Risks

- Multi-region consistency: URL created in US might take < 1s to be available in EU
- ClickHouse cluster management: rebalancing, backup, shard failures
- Kafka MirrorMaker2 lag: EU analytics may lag US by > 60s

### Team Scaling

- Backend Engineers: 3–4 (feature dev + API)
- DevOps/SRE: 1–2 (Kubernetes, monitoring, multi-region)
- Data Engineer: 1 (ClickHouse, analytics pipeline)

### Complexity: High

---

## Phase 3: V3 — Enterprise Scale (Months 10–18) — 8–12 Engineers

### Goal

Multi-tenant SaaS offering with white-label support, enterprise SSO, compliance features, and 1B+ redirects/day capacity.

### Features Added

- Multi-tenant white-label short domains (e.g., `go.acme.com/slug`)
- Enterprise SSO (SAML 2.0, OIDC)
- Team management + RBAC within organization
- Audit trail (immutable log of all URL operations per tenant)
- GDPR compliance tooling (data export, right to erasure)
- SLA reporting dashboard for enterprise customers
- Compliance certifications: SOC2, ISO27001 preparation
- 3rd-party API integrations (Slack, Salesforce)
- Advanced fraud detection (ML-based URL scoring)

### Architecture

```
Global Traffic (1B+ redirects/day)
    ↓
Multi-region active-active (US, EU, AP, AU)
CloudFront → Lambda@Edge (edge-side redirect for zero-origin-hit on cached URLs)

Per Region:
  EKS (100+ pods per region)
  PostgreSQL Citus (horizontal sharding by tenant_id)
  Redis Cluster (100GB+ per region)
  Kafka (Confluent Cloud or MSK 10+ brokers)
  ClickHouse (6+ shard distributed cluster)

Global Control Plane:
  Tenant Management Service
  SSO Service
  Billing & Metering Service
```

### Infrastructure Evolution

- PostgreSQL Citus extension for horizontal sharding by `tenant_id`
- Lambda@Edge for < 1ms redirect on CDN-cached URLs (no origin hop)
- 4 active regions: US-East, EU-West, AP-South, AP-Southeast
- Kafka Confluent Cloud (fully managed, eliminates broker ops)
- Data residency: EU tenant data stays in EU (data sovereignty)
- Zero-trust networking: Istio service mesh, mTLS between all services

### New Services (Microservice Extraction)

By Phase 3, the following are extracted from the monolith:
- `redirect-service`: Stateless, ultra-low-latency, deployed at CDN edge
- `url-management-service`: CRUD for short URLs
- `analytics-service`: Click ingestion + reporting
- `identity-service`: Auth, SSO, API keys
- `tenant-service`: Multi-tenancy management
- `abuse-service`: Real-time fraud scoring

### Deliverables

- Citus sharding implementation with tenant_id shard key
- Lambda@Edge redirect function deployment
- SAML/OIDC integration for enterprise SSO
- Immutable audit log to S3 with Object Lock
- Compliance documentation package (SOC2 evidence)
- Data residency routing (EU data to EU region)
- Billing metering: per-redirect, per-URL quota tracking

### Team Scaling

- 2 product teams (URL Management + Analytics)
- 1 Platform team (Kubernetes, Kafka, infrastructure)
- 1 Security team (compliance, pen testing)
- 1 Data team (ClickHouse, ML fraud scoring)
- Total: 12–15 engineers

### Complexity: Very High

---

## Implementation Principles Across All Phases

| Principle | Application |
|---|---|
| API-first | Define OpenAPI spec before implementation |
| Contract testing | Pact tests between services before integration |
| Observability from day 1 | Prometheus metrics from MVP onwards |
| Database migrations | Flyway from day 1; no schema changes without migration |
| Feature flags | LaunchDarkly from V1 onwards; no dark launches |
| Security by default | HTTPS-only, non-root containers, minimal DB permissions from day 1 |
| Runbooks | Every alert has a runbook link before alert goes live |
| Chaos engineering | Introduce from V2 onwards (monthly chaos game days) |

---

## Interview Discussion Points

- **What would you build first?** Core redirect path: `POST /urls` + `GET /{code}` + Redis cache. Everything else is additive. A URL shortener that doesn't redirect reliably is useless
- **How do you prioritize features in V2?** Business impact × technical risk. Geo-routing: high value for enterprise customers, moderate technical complexity → high priority. A/B testing: niche use case, moderate complexity → lower priority
- **At what scale does the architecture break?** Single-region breaks at ~100M redirects/day (Redis hot keys). Multi-region breaks at ~1B redirects/day (DB replication lag becomes user-visible). Edge computing (Lambda@Edge) removes the app server bottleneck entirely beyond that
- **What would you do differently if starting over?** Build the API design and event schema first — these are the hardest to change later. Don't introduce Kafka until you have > 100K events/day (simpler: write to DB + async read by analytics job). Use managed services aggressively (Confluent, Aiven) — operational simplicity is worth the cost for teams < 10 engineers
