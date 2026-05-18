# 00 — Requirements Analysis

## Objective

Define the functional and non-functional requirements for a production-grade Multi-Tenant SaaS CRM platform. Establish capacity planning, traffic patterns, and constraints that will drive every architectural decision downstream.

---

## Problem Statement

Build a CRM platform where multiple organizations (tenants) manage their customers, pipelines, contacts, deals, tasks, and workflows — all on shared infrastructure — with complete data isolation, per-tenant customization, and enterprise-grade compliance.

---

## Functional Requirements

### Core CRM Features
- **Contact Management**: CRUD on contacts with dynamic custom fields per tenant
- **Account Management**: Company/organization records linked to contacts
- **Deal/Pipeline Management**: Multi-stage deal pipelines, configurable per tenant
- **Task and Activity Management**: Tasks, calls, emails, meetings linked to any CRM record
- **Lead Management**: Lead capture, scoring, routing, and conversion
- **Notes and Comments**: Threaded notes on any CRM entity

### Multi-Tenancy
- Tenant registration and onboarding workflow (self-serve + admin-assisted)
- Tenant-level configuration: branding, locale, timezone, currency
- Per-tenant data isolation — tenants MUST NOT see each other's data
- Tenant suspension and offboarding with data export

### Identity and Access
- Multi-tenant RBAC: Owner, Admin, Manager, Rep, Read-Only roles per tenant
- ABAC for fine-grained rules (e.g., "Reps can only see their own deals")
- SSO support via OAuth2 / SAML 2.0 per tenant
- MFA enforcement configurable per tenant

### Workflow Automation
- Configurable workflow triggers (record create, update, field change, time-based)
- Configurable actions (send email, create task, assign owner, webhook call)
- Per-tenant workflow definitions stored as metadata/DSL

### Dynamic Schema
- Per-tenant custom fields on any core entity (text, number, date, picklist, multi-select, lookup)
- Per-tenant field validation rules
- Custom object types (advanced tier)

### Integrations and Plugins
- Webhook delivery for external integrations
- Plugin/extension marketplace (third-party apps install per tenant)
- REST and GraphQL API access per tenant (with per-tenant API keys)

### Audit and Compliance
- Full audit history on every field change, by whom, at what time
- GDPR: Right to erasure per contact, data export per tenant
- SOC2 compliance controls: access logs, encryption, retention policies

### SaaS Pricing Tiers
- **Starter**: 5 users, basic pipelines, no custom fields
- **Growth**: 25 users, custom fields, workflows, integrations
- **Enterprise**: Unlimited users, custom objects, SSO, dedicated support, SLA
- Feature flags gated by tier per tenant

---

## Non-Functional Requirements

| Property | Target |
|---|---|
| Availability | 99.9% (Starter), 99.95% (Growth), 99.99% (Enterprise) |
| Read Latency (P99) | < 200ms for standard reads |
| Write Latency (P99) | < 500ms for writes |
| Search Latency (P99) | < 300ms for full-text search |
| Tenant Isolation | Zero cross-tenant data leakage |
| Audit Delivery | Audit events written within 5 seconds of action |
| Data Durability | 99.999999999% (11 nines) via replication |
| RTO | < 4 hours for Enterprise, < 24 hours for Starter |
| RPO | < 1 hour for Enterprise, < 24 hours for Starter |
| Compliance | GDPR, SOC2 Type II, ISO 27001 (roadmap) |

---

## Assumptions

- Tenants are organizations (B2B), not individual users
- Each tenant has between 1 and 5,000 users (SMB to Enterprise)
- Average tenant has ~100 users, leading to ~1M total users at scale
- Majority of tenants are SMB (Starter/Growth tier) — 80% of tenants, 20% of revenue
- Enterprise tenants (~5% of count) generate ~60% of revenue — must receive superior SLA
- Data per tenant varies: SMB tenants ~10GB, Enterprise tenants up to 1TB
- All tenants run on shared infrastructure unless explicitly on dedicated tier

---

## Constraints

- Must support multi-region deployment for data residency compliance (EU data stays in EU)
- Cannot break tenant isolation under any failure mode
- Must support zero-downtime schema migrations across all tenants
- Plugin marketplace must not allow plugins to access another tenant's data
- GDPR erasure must propagate within 30 days to all systems including backups
- API rate limits must be enforced per tenant to prevent noisy-neighbor effects

---

## Scale Estimation (Back of Envelope)

### Users and Tenants

```
Total tenants: 10,000
Average users per tenant: 100
Total users: 1,000,000

Peak concurrent users: ~5% of 1M = 50,000
Active users in a business day: ~30% = 300,000
```

### Traffic Estimation

```
Assumptions:
- Each active user makes ~50 read requests/day
- Each active user makes ~10 write requests/day

Reads/day  = 300,000 * 50 = 15,000,000
Writes/day = 300,000 * 10 = 3,000,000

Peak reads RPS  = 15M / (8hr * 3600) * 3x peak factor ≈ 1,560 RPS
Peak writes RPS = 3M  / (8hr * 3600) * 3x peak factor ≈ 310 RPS

Total peak RPS ≈ ~2,000 RPS (moderate load — manageable)
```

### Storage Estimation

```
Per-tenant average data:
  Contacts:     10,000 contacts × 2KB average = 20MB
  Deals:         5,000 deals    × 3KB average = 15MB
  Activities:   50,000 events   × 1KB average = 50MB
  Audit logs:   Assume 3x amplification over data = 255MB
  Total per SMB tenant: ~350MB

10,000 tenants × 350MB = 3.5TB raw data (across all SMB tenants)
Top 500 Enterprise tenants × 100GB = 50TB

Total estimated storage: ~55TB across primary + replicas × 3 = 165TB
Elasticsearch index: ~20% of primary data = ~11TB
Redis working set: ~5% of hot data ≈ ~3TB (distributed)
```

### Throughput for Kafka

```
Audit events = every write generates 1-3 audit records
Write RPS ~310 × 2 avg audit records = 620 audit events/sec
Workflow triggers ~50% of writes = 155 events/sec
Total Kafka throughput: ~800 events/sec (very manageable)
Peak: 3-4x = ~3,200 events/sec
```

---

## Read/Write Patterns

| Pattern | Ratio | Notes |
|---|---|---|
| Reads vs Writes | 80/20 | CRM is read-heavy during daily use |
| Contact lookups | Very high | Typeahead search, list views |
| Pipeline views | High | Sales team dashboards |
| Audit log reads | Low | Compliance only |
| Bulk exports | Occasional | GDPR export, reporting |
| Bulk imports | Occasional | CSV import of contacts |

---

## Latency Expectations

| Operation | Target P50 | Target P99 |
|---|---|---|
| Contact list (paginated) | 50ms | 150ms |
| Contact search (full-text) | 80ms | 250ms |
| Deal pipeline view | 60ms | 200ms |
| Workflow trigger processing | 200ms | 1s (async ok) |
| Audit log write | 50ms | 300ms (async) |
| Tenant onboarding | N/A | < 30s end-to-end |

---

## Risks Identified at Requirements Stage

1. **GDPR erasure complexity**: Contacts may be referenced in audit logs, Elasticsearch, Kafka topics, backups. True erasure is technically hard — requires tombstoning, key-based encryption erasure (crypto-shredding), and scheduled scrubbing.
2. **Dynamic schema abuse**: Tenants creating thousands of custom fields can degrade query performance without guardrails.
3. **Noisy neighbor**: A large Enterprise tenant running bulk import can saturate database connection pools or Kafka topics impacting SMB tenants.
4. **Plugin sandboxing**: Third-party plugins accessing the CRM data model — untrusted code execution is a severe security risk.
5. **Cross-tenant leakage in shared infra**: A single RLS bug or missing `tenant_id` filter can expose all tenants' data. Zero tolerance.

---

## Interview Discussion Points

- **How would you handle a tenant requiring data residency in Germany?** → Multi-region deployment, region-pinned tenants, EU-specific Kafka clusters.
- **What changes if you need to support 100,000 tenants instead of 10,000?** → Database-per-tenant becomes impractical, row-level security with aggressive connection pooling via PgBouncer scales better, but provisioning automation becomes critical.
- **How do you estimate storage without knowing tenant behavior?** → Model by percentile (P50 tenant vs P95 tenant), use telemetry from beta cohorts to refine assumptions.
- **How do you prevent one large tenant from impacting all others?** → Tenant-aware rate limiting, dedicated resources for Enterprise tier, async processing queues partitioned by tenant.
