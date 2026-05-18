# 14 — Interview Discussion Points

## Objective

Prepare for senior and staff-level system design interview discussions on a Multi-Tenant SaaS CRM. This file covers the questions interviewers are most likely to ask, the tradeoffs they expect you to articulate, the mistakes that eliminate candidates, and the deeper concerns that distinguish a Staff+ response from a Senior one.

---

## Core Framing: What Interviewers Are Testing

A Multi-Tenant SaaS CRM is a rich problem because it sits at the intersection of:
- **Distributed systems** (Kafka, async processing, eventual consistency)
- **Security and isolation** (tenant data separation, RBAC, audit)
- **Operational complexity** (per-tenant configuration, migrations, onboarding)
- **Compliance engineering** (GDPR, data residency, deletion guarantees)
- **Product engineering** (dynamic schemas, configurable workflows, plugin systems)

Interviewers use this problem to test whether you can hold multiple concerns simultaneously without oversimplifying any of them.

---

## Part 1: Tenant Isolation — The Central Tradeoff

### Expected Questions

1. **"Walk me through your three options for tenant isolation and why you chose one."**

   The three models and their real tradeoffs:

   | Model | Isolation | Operational Complexity | Cost at Scale | Best For |
   |---|---|---|---|---|
   | DB-per-tenant | Strongest | Very High | Very High | Regulated industries, enterprise |
   | Schema-per-tenant | Strong | High | Medium-High | Mid-market SaaS |
   | Row-Level Security (RLS) | Moderate | Low | Low | SMB SaaS, rapid growth |

   The answer is never "schema-per-tenant is always best." The right answer depends on tenant count, regulatory requirements, and team operational maturity. A startup with 10 enterprise clients should consider schema-per-tenant. A startup with 10,000 SMB clients should use RLS and invest in bulletproof policy enforcement.

2. **"How do you prevent a tenant from accidentally reading another tenant's data in the RLS model?"**

   - PostgreSQL RLS policies enforce `tenant_id = current_setting('app.current_tenant')` at the DB layer
   - The application sets this session variable before every query
   - Integration tests verify isolation: a test creates records as Tenant A, then queries as Tenant B and asserts zero results
   - Read replicas inherit RLS policies — this is often missed in interviews

3. **"What happens if a developer forgets to set the tenant context in a new service?"**

   This is the **most dangerous bug** in a RLS-based system. Mitigations:
   - A `TenantContextFilter` (Servlet filter) sets tenant context on every inbound request and throws if the header is missing
   - A custom DataSource wrapper asserts tenant context is set before allowing any connection checkout
   - Code review checklist and automated linting rules for new repository classes

4. **"How would you migrate from RLS to schema-per-tenant for a specific enterprise client?"**

   This is a real operational problem. Answer: a one-time migration job extracts tenant data from the shared schema, creates a dedicated schema, runs Flyway baseline, copies data, updates the tenant registry to point to the new schema, and disables RLS for that tenant. Zero-downtime requires a dual-write period.

---

## Part 2: RBAC and Permissions

### Expected Questions

5. **"How does your RBAC model handle the case where a tenant wants a custom role that doesn't exist in your default set?"**

   The system distinguishes between **platform roles** (defined by the SaaS vendor, immutable) and **tenant roles** (defined by the tenant admin, mutable). Tenant admins can create custom roles by composing permission sets. The permission model is:
   - `Resource` (Contact, Deal, Pipeline, Report)
   - `Action` (Read, Write, Delete, Export, Assign)
   - `Scope` (Own, Team, All)

   A custom role maps a set of (Resource, Action, Scope) tuples. This is stored per tenant in a `roles` and `role_permissions` table.

6. **"What's the difference between RBAC and ABAC and when would you need ABAC in a CRM?"**

   RBAC: permissions based on role membership. ABAC: permissions based on attributes of the resource, the user, and the environment.

   CRM use cases that require ABAC:
   - "A sales rep can only see deals assigned to their region"
   - "A manager can only export reports if the data is older than 30 days"
   - "A user can edit a contact only if they are the owner or the deal value is below $10k"

   These cannot be expressed with RBAC alone. The answer is a policy engine (OPA, or a lightweight custom rule evaluator) layered on top of RBAC.

---

## Part 3: Audit and Compliance

### Expected Questions

7. **"How do you implement a tamper-evident audit log?"**

   Options:
   - Write audit events to an append-only Kafka topic, consumed by an Audit Service that writes to a write-once S3 bucket with Object Lock (WORM storage)
   - Hash chaining: each audit record includes a hash of the previous record, making retroactive modification detectable
   - For stricter compliance: write to an immutable ledger (Amazon QLDB or similar)

   The interview expects you to distinguish between "we log things" and "we can prove to a regulator that logs haven't been tampered with."

8. **"A tenant's admin requests a complete data export for GDPR. Walk me through the implementation."**

   - Trigger: `POST /api/v1/tenants/{tenant_id}/gdpr/export`
   - An async job is enqueued (Kafka or a job queue)
   - The export job queries all tables where `tenant_id = ?`, including soft-deleted records
   - Output is a JSON archive (or machine-readable CSV per entity type)
   - The archive is encrypted with the tenant's public key or a one-time token-protected download link
   - The job completes within 30 days (legal requirement); target SLA is 24-72 hours
   - Notification sent to the requesting admin with a download link that expires in 7 days

9. **"A tenant requests account deletion. How do you ensure all their data is gone?"**

   Three-phase approach:
   - **Phase 1 — Soft delete + access revocation**: Immediately mark tenant as `PENDING_DELETION`, revoke all sessions, block new logins. SLA: seconds.
   - **Phase 2 — Data purge**: Background job deletes all records across all tables, drops schema (if schema-per-tenant), purges Redis keys, purges Kafka consumer group offsets, deletes Elasticsearch indices. SLA: 24 hours.
   - **Phase 3 — Verification + certificate**: An audit job verifies no tenant data remains across all datastores. Issues a deletion certificate stored in the platform's own audit log. SLA: 72 hours total.

   The hard problem: data in backups. GDPR allows backup retention for a defined period, but the backup must not be used to restore deleted tenant data. This is handled via a "backup suppression list" checked before any restore operation.

---

## Part 4: Scaling Questions

### Expected Questions

10. **"Your largest tenant generates 10x the write volume of all others. How does this affect your system?"**

    This is the **noisy neighbor problem**. In a shared database:
    - Connection pool exhaustion: one tenant monopolizes DB connections
    - Lock contention on shared tables
    - Kafka partition hotspot if tenant_id is the partition key

    Mitigations:
    - Per-tenant connection pool limits at the PgBouncer level
    - Kafka: partition by `(tenant_id + entity_id)` hash, not raw tenant_id
    - Rate limiting at the API gateway layer per tenant tier
    - For extreme cases: move the tenant to a dedicated database shard

11. **"How does your system scale from 100 tenants to 100,000?"**

    This is a three-phase scaling story:
    - **Phase 1 (0-1,000 tenants)**: Single PostgreSQL cluster with RLS, single Kafka cluster, shared Redis. Vertical scaling.
    - **Phase 2 (1,000-10,000 tenants)**: Add read replicas, partition Kafka, introduce PgBouncer, shard Redis by tenant range.
    - **Phase 3 (10,000-100,000 tenants)**: Database sharding by tenant cohorts, Vitess or Citus for PostgreSQL, per-region deployments for data residency, global tenant registry with routing metadata.

---

## Part 5: "What Would Break First?"

This is the most important question in a Staff+ interview. The expected answer is not a vague list but a ranked, reasoned analysis.

| Component | Failure Mode | At What Scale | Signal |
|---|---|---|---|
| PostgreSQL primary | Connection pool exhaustion | ~500 concurrent active tenants | Latency spikes, timeouts |
| Kafka partition | Hotspot from large tenant | ~1M events/day from single tenant | Consumer lag per partition |
| Redis | Memory pressure from uncapped caches | Tenant count × avg session size | Eviction rate alert |
| Elasticsearch | Index bloat from audit events | ~100M documents | Query latency, shard limits |
| Tenant migration orchestrator | Slow migrations blocking onboarding | Schema-per-tenant at 5k+ schemas | Onboarding SLA breach |
| RBAC policy evaluation | N+1 permission checks | Per-request deep RBAC trees | API latency regression |

---

## Part 6: Common Candidate Mistakes

| Mistake | Why It's Wrong |
|---|---|
| Choosing one isolation model without justifying the choice | Signals you haven't thought about the operational consequences |
| Not mentioning the noisy neighbor problem | Every multi-tenant system has this problem; omitting it signals inexperience |
| Treating RBAC as "just roles and permissions" | Missing scope, inheritance, and the ABAC boundary |
| Not distinguishing soft delete from hard delete for GDPR | GDPR requires hard deletion eventually; soft delete alone is insufficient |
| Ignoring migration complexity for schema-per-tenant | Migrating 10k schemas in production is an operational challenge most candidates skip |
| Saying "we'll add a read replica to fix read scaling" | Correct but incomplete; misses connection pooling, caching, and CQRS needs |
| Not addressing tenant onboarding automation | Manual tenant onboarding doesn't scale past 100 clients |

---

## Part 7: Staff Engineer Discussion Points

These are the questions that separate a Senior from a Staff+ answer:

12. **"How would you design a self-service tenant onboarding flow that provisions infrastructure automatically?"**

    Answer must cover: schema provisioning, Vault secret creation, default RBAC seed data, Kafka topic ACLs, welcome email trigger, and billing system integration — all as a transactional workflow using a Saga pattern or an orchestrated onboarding state machine.

13. **"How do you design a plugin system that third parties can extend without access to your codebase?"**

    Options:
    - **Webhook-based**: plugins subscribe to events and receive HTTP callbacks. Simple, no execution risk.
    - **Serverless functions**: tenants upload code (Lambda/Cloud Run), platform executes in sandboxed environments. Higher capability, higher operational risk.
    - **WASM sandbox**: plugin logic compiled to WebAssembly, executed in-process. High performance, strict isolation.

    The answer should mention: API contracts, versioning, rate limiting plugin executions, and what happens when a plugin throws an exception in the middle of a workflow.

14. **"How do you implement data residency for a tenant who requires all data to remain in the EU?"**

    - Tenant registry stores `data_residency_region` per tenant
    - API gateway routes requests to the correct regional cluster based on tenant metadata
    - Cross-region data is strictly prohibited; even logs and metrics must stay in region
    - GDPR + Schrems II compliance requires this to be provable, not just claimed
    - The hardest part: ensuring Kafka replication, backups, and analytics pipelines don't cross regional boundaries

---

## Summary: How to Structure Your Interview Answer

A strong candidate structures the answer as:

1. Clarify requirements (tenant count, isolation needs, compliance requirements)
2. Propose and justify the isolation model
3. Walk through the core data model and API design
4. Address the hard problems explicitly (noisy neighbor, GDPR, RBAC edge cases)
5. Discuss scaling evolution: what breaks first and how you'd fix it
6. End with operational concerns: onboarding automation, migration strategy, observability
