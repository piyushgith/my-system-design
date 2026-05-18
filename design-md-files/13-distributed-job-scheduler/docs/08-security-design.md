# 08 — Security Design: Distributed Job Scheduler

## Objective
Design a comprehensive security posture covering authentication, authorization, secrets management, data isolation, audit trail, and network-level security for the distributed job scheduler in both single-tenant and multi-tenant deployments.

---

## 1. Authentication

### JWT-Based Authentication (API Layer)

All external API calls must present a valid JWT Bearer token. The JWT is issued by an external Identity Provider (e.g., Okta, Keycloak, Auth0) and validated at the API Gateway.

**JWT Claims structure:**
```json
{
  "sub": "user:john.doe@company.com",
  "iss": "https://auth.company.com",
  "aud": "scheduler-api",
  "exp": 1705399200,
  "iat": 1705395600,
  "jti": "unique-token-id",
  "namespace": "production",
  "roles": ["JOB_OPERATOR", "JOB_VIEWER"],
  "teams": ["data-engineering", "ml-platform"]
}
```

**Validation at API Gateway:**
1. Verify JWT signature (RS256) against IdP's public key (fetched via JWKS endpoint, cached 1 hour)
2. Verify `aud` claim matches `scheduler-api`
3. Verify `exp` not expired
4. Extract `namespace` claim for tenant routing (in multi-tenant mode)
5. Pass `X-User-Id`, `X-Namespace`, `X-Roles` headers downstream to internal services

**Service-to-service auth (internal):**
- Internal services (Scheduler Engine ↔ Result Processor) use mTLS with service certificates (Istio sidecar in K8s)
- Workers authenticate to Kafka using SASL/SCRAM with per-worker-pool credentials

---

## 2. Authorization (RBAC)

### Role Definitions

| Role | Scope | Permissions |
|---|---|---|
| `JOB_ADMIN` | Namespace | Full CRUD on all jobs in namespace, manage workers, view DLQ, replay DLQ |
| `JOB_OPERATOR` | Namespace | Create, update, pause, resume, delete own jobs; trigger manual runs; view all jobs |
| `JOB_VIEWER` | Namespace | Read-only: list jobs, view execution history, view worker status |
| `JOB_EXECUTOR` | System-internal | Used by Scheduler Engine to write executions (not assignable to users) |
| `SYSTEM_ADMIN` | Global | Cross-namespace access, manage tenants, access audit log, DLQ management |

### Permission Matrix

| Operation | JOB_VIEWER | JOB_OPERATOR | JOB_ADMIN | SYSTEM_ADMIN |
|---|---|---|---|---|
| GET /jobs | ✓ (own namespace) | ✓ (own namespace) | ✓ (own namespace) | ✓ (all namespaces) |
| POST /jobs | ✗ | ✓ | ✓ | ✓ |
| DELETE /jobs/{id} | ✗ | ✗ (only own jobs) | ✓ | ✓ |
| POST /jobs/{id}/trigger | ✗ | ✓ | ✓ | ✓ |
| GET /workers | ✓ | ✓ | ✓ | ✓ |
| GET /admin/dlq | ✗ | ✗ | ✓ | ✓ |
| POST /admin/dlq/{id}/retry | ✗ | ✗ | ✓ | ✓ |
| GET /audit-log | ✗ | ✗ | ✓ (own namespace) | ✓ |

### Attribute-Based Access Control (ABAC — Phase 2)

For fine-grained control (e.g., a team can only manage jobs tagged with their team name):

```
ABAC Rule: ALLOW if
  user.teams INTERSECTS job.tags.teams
  AND user.roles CONTAINS 'JOB_OPERATOR'
  AND job.namespace == user.namespace
```

ABAC evaluation happens in the application layer (Spring Security SpEL expressions on method security), not at the gateway level.

---

## 3. Secrets Management for Job Parameters

### Problem
Jobs often need secrets (API keys, database passwords, auth tokens) passed as parameters. Storing these in the `jobs.parameters` JSONB column as plaintext is a critical security risk — secrets appear in audit logs, Kafka messages, and execution logs.

### Solution: Secret Reference Pattern

Jobs reference secrets by path, not by value:
```json
{
  "httpConfig": {
    "headers": {
      "Authorization": "{{secrets.api.REPORT_API_KEY}}"
    }
  }
}
```

At dispatch time, the Scheduler Engine resolves secret references:
1. Parse job config for `{{secrets.*}}` placeholders
2. Fetch actual value from HashiCorp Vault (or AWS Secrets Manager) using the Scheduler's service account
3. Inject resolved values into `execution_context.secrets` (encrypted JSONB field)
4. Worker receives secrets in `ExecutionContext` — they are transmitted over encrypted Kafka (TLS) and stored in encrypted form
5. After execution, worker zeroes out secret values from memory

**Vault integration:**
- Scheduler Engine authenticates to Vault using Kubernetes Service Account JWT (Vault K8s auth method)
- Secrets fetched with short TTL leases (5 minutes max)
- Secret rotation: when a secret is rotated in Vault, the next job dispatch fetches the new value automatically (no job reconfiguration needed)

### What Never Goes in the Database or Logs
- Resolved secret values never stored in PostgreSQL
- Execution logs scrubbed for common secret patterns before storage (regex for tokens, keys, passwords)
- `execution_context.secrets` column is encrypted at rest with column-level encryption (pgcrypto)

---

## 4. Multi-Tenant Isolation

### Namespace Isolation

Every resource (Job, Execution, Worker pool) is scoped to a `namespace`. Row-level security (RLS) in PostgreSQL enforces namespace isolation at the database level:

```sql
-- Application role for API requests
CREATE ROLE scheduler_app;

-- RLS policy: application can only see its own namespace
CREATE POLICY namespace_isolation ON scheduling.jobs
    FOR ALL TO scheduler_app
    USING (namespace = current_setting('app.current_namespace'));

-- Set namespace at the start of each DB session
SET LOCAL app.current_namespace = 'production';
```

**Defense in depth:**
1. JWT claim (`namespace`) extracted at API Gateway
2. Application service validates namespace in every query
3. PostgreSQL RLS enforces namespace at database level (even if application logic is wrong)
4. Worker pools are namespace-scoped: workers tagged with `namespace=production` only consume from `job-triggers` partitions allocated to `production`

### Network Isolation

- API Gateway validates namespace; internal services trust the `X-Namespace` header only from API Gateway
- Worker pods for different namespaces run in different K8s namespaces with Network Policies blocking cross-namespace pod communication
- Kafka topics per namespace in Phase 2 (single topic with namespace filtering in Phase 1)

---

## 5. Rate Limiting

### Per-Namespace Rate Limits

Implemented at API Gateway using Redis Token Bucket (sliding window):

| Endpoint | Default Limit | Configurable per Namespace |
|---|---|---|
| POST /jobs (job registration) | 100 req/min | Yes (up to 10,000/min for large tenants) |
| POST /jobs/{id}/trigger | 1,000 req/min | Yes |
| GET /executions | 10,000 req/min | Yes |
| POST /admin/dlq/{id}/retry | 100 req/min | No (fixed) |

**Burst allowance:** 2x the rate limit allowed in a 5-second window; reverts to base rate after burst.

**Rate limit response:**
```http
HTTP/1.1 429 Too Many Requests
Content-Type: application/problem+json
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1705399260
Retry-After: 12

{
  "type": "https://scheduler.internal/errors/rate-limit-exceeded",
  "title": "Rate Limit Exceeded",
  "detail": "Namespace 'production' has exceeded 1000 trigger requests/minute."
}
```

---

## 6. Network Security

### TLS Everywhere
- All external traffic: TLS 1.3 with strong cipher suites (AES-256-GCM)
- Internal service-to-service: mTLS via Istio service mesh
- Kafka: TLS + SASL/SCRAM for broker connections
- PostgreSQL: TLS for all client connections (require SSL)
- Redis: TLS for all client connections

### Network Policies (Kubernetes)
```
Allowed connections:
  API Gateway → Scheduler Engine
  API Gateway → PostgreSQL (read replica)
  Scheduler Engine → PostgreSQL (primary)
  Scheduler Engine → Redis
  Scheduler Engine → Kafka (producer)
  Workers → Kafka (consumer, producer for results)
  Workers → PostgreSQL (read replica, for job definition fetch)
  Workers → Redis (heartbeat)
  Result Processor → PostgreSQL (primary)
  Result Processor → Redis (lock release)

Denied by default:
  Workers → Scheduler Engine (workers never call back to scheduler directly)
  Workers → other Workers
  External → PostgreSQL, Redis, Kafka directly
```

---

## 7. API Security

### Input Validation
- Cron expression validation: parsed and validated server-side; reject on syntax error
- Job parameters: sanitized against injection patterns (stored as JSONB, never concatenated into SQL)
- URL fields in httpConfig: validated for scheme (HTTPS only for external URLs), hostname allowlist optionally configured per namespace
- Max payload sizes: job registration request limited to 64KB (configurable)

### Injection Prevention
- All database queries use parameterized queries (PreparedStatement via Spring Data JPA)
- JSONB columns stored as structured data — not interpolated into queries
- Template parameters in job config (`{{trigger_date}}`) are substituted in a sandboxed template engine with allowlist of valid variables

### CSRF Protection
- API is stateless (JWT); no session cookies → CSRF not applicable
- If a web dashboard uses cookies: CSRF tokens required for state-changing operations

---

## 8. Audit Logging

### What is Audited

| Event | Logged Fields |
|---|---|
| Job created | jobId, name, schedule, createdBy, clientIp, timestamp |
| Job updated | jobId, changedFields (before/after snapshot), updatedBy, clientIp |
| Job deleted | jobId, deletedBy, reason, clientIp |
| Job paused/resumed | jobId, action, actor, reason |
| Manual trigger | jobId, executionId, triggeredBy, overrideParams (no secrets) |
| DLQ retry | dlqEntryId, executionId, retriedBy |
| DLQ discarded | dlqEntryId, discardedBy, reason |
| Role assigned | userId, role, assignedBy |
| Failed auth attempt | userId (if known), clientIp, endpoint |

### Audit Log Immutability
- Written via an append-only database role with `INSERT` permission only on `monitoring.audit_log`
- Row-level security policy denies `UPDATE` and `DELETE` to all roles except `audit_admin` (used only for compliance archival)
- Audit log rows are checksummed (`SHA-256(row_content)`) and stored in a separate column for tamper detection
- Audit entries exported to immutable S3 (Object Lock with Governance mode) for GDPR/compliance retention

---

## 9. Security Monitoring and Alerting

| Alert | Condition | Severity |
|---|---|---|
| Excessive failed auth | >100 401 responses in 1 minute from same IP | HIGH |
| Privilege escalation attempt | User accessing another namespace's resource | CRITICAL |
| DLQ retry flood | >50 manual DLQ retries in 5 minutes by one user | HIGH |
| Unusual trigger volume | Namespace triggers >10x daily average in 10 min | MEDIUM |
| Secret resolution failure | Vault unavailable for >30 seconds | HIGH |
| Admin API access | Any access to SYSTEM_ADMIN endpoints | LOW (audit) |

---

## Interview Discussion Points

**Q: What happens if a worker pod is compromised and tries to write fake execution results?**
A: Three defenses: (1) The fencing token check ensures the worker can only write results for executions where it holds the current fencing token — a compromised worker with a stale token gets rejected. (2) Workers authenticate to Kafka with per-pool SASL credentials — a compromised worker can only write to `job-results` (not `job-triggers` or `job-events`). (3) The Result Processor validates that the `worker_id` in the result matches the `worker_id` stored in the execution record.

**Q: How do you prevent a tenant from causing a DoS by submitting millions of jobs?**
A: Three layers: (1) API rate limiting (jobs/minute per namespace). (2) Per-namespace concurrency limits (max concurrent executions for a namespace, enforced by the scheduler before dispatching). (3) Resource quotas at the Kafka level (producer quotas per namespace client ID). Job submission succeeds, but dispatch is throttled.

**Q: If job parameters contain PII (user data in reports), how do you handle GDPR right-to-erasure?**
A: Job parameters in `execution_context` are JSONB — PII can be scrubbed by updating the column. For audit log, we store a reference (user_id) not the data itself. Execution logs in S3 are subject to retention policies and can be deleted via S3 Object Lock governance mode. The critical invariant: audit log of who did what is retained, but PII payloads can be erased.
