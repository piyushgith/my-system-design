# 05 — Database Design: API Gateway

## Objective

Define the persistent storage strategy for the API Gateway's control plane (route configs, policies, API keys, developer accounts) and the runtime's transient state (rate limit counters, JWKS cache, active key cache). This document covers schema design, indexing, partitioning, Redis data structures, and the critical separation between the hot path (no DB calls) and the cold path (admin operations).

---

## Storage Architecture Overview

The API Gateway uses a **two-tier storage model**:

| Tier | Technology | Purpose | Access Pattern |
|---|---|---|---|
| **Persistent Store** | PostgreSQL | Route definitions, policies, API keys, developer accounts, audit logs | Admin operations only; < 100 ops/sec |
| **Fast Cache** | Redis Cluster | Active route config snapshot, API key lookup cache, JWKS cache, rate limit counters | Every request; 500K+ ops/sec |

**Critical design rule:** The Gateway Runtime (hot path) reads exclusively from Redis and in-memory structures. It NEVER makes synchronous PostgreSQL calls while processing a client request. PostgreSQL is the source of truth; Redis is the working copy.

---

## PostgreSQL Schema

### Table: `routes`

```sql
CREATE TABLE routes (
    route_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL,
    upstream_uri    VARCHAR(1024) NOT NULL,
    order_priority  INTEGER NOT NULL DEFAULT 100,
    status          VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version         BIGINT NOT NULL DEFAULT 1,      -- optimistic locking version
    enabled         BOOLEAN NOT NULL DEFAULT TRUE,
    tags            JSONB,
    traffic_split   JSONB,                          -- [{uri, weight}, ...]
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMPTZ,                    -- soft delete

    CONSTRAINT routes_status_check CHECK (status IN ('ACTIVE','DISABLED','CANARY','DELETED'))
);

CREATE INDEX idx_routes_status ON routes(status) WHERE deleted_at IS NULL;
CREATE INDEX idx_routes_order ON routes(order_priority) WHERE status = 'ACTIVE';
CREATE INDEX idx_routes_tags ON routes USING GIN(tags);
CREATE INDEX idx_routes_updated_at ON routes(updated_at);
```

**Design decisions:**
- `version` column enables optimistic locking. Admin API PATCH requests include `If-Match` with the current version; the UPDATE statement includes `WHERE version = :expectedVersion`. If 0 rows updated, the server returns 409 Conflict.
- `tags` as JSONB with GIN index allows flexible filtering by arbitrary metadata without schema changes.
- `traffic_split` as JSONB avoids a separate join table for what is typically a 2-element array (v1/v2 split). For more complex topologies, this would need normalization.
- Soft delete with `deleted_at` preserves audit history. A separate archival job moves records with `deleted_at < NOW() - INTERVAL '30 days'` to an archive table.

### Table: `route_predicates`

```sql
CREATE TABLE route_predicates (
    predicate_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id        UUID NOT NULL REFERENCES routes(route_id) ON DELETE CASCADE,
    predicate_type  VARCHAR(50) NOT NULL,      -- PATH, HOST, METHOD, HEADER, QUERY
    pattern         VARCHAR(1024) NOT NULL,
    negated         BOOLEAN NOT NULL DEFAULT FALSE,
    eval_order      INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT route_predicates_type_check
        CHECK (predicate_type IN ('PATH','HOST','METHOD','HEADER','QUERY','WEIGHT'))
);

CREATE INDEX idx_predicates_route_id ON route_predicates(route_id);
```

### Table: `route_filters`

```sql
CREATE TABLE route_filters (
    filter_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    route_id        UUID NOT NULL REFERENCES routes(route_id) ON DELETE CASCADE,
    filter_name     VARCHAR(100) NOT NULL,
    args            JSONB NOT NULL DEFAULT '{}',
    filter_order    INTEGER NOT NULL DEFAULT 0,
    phase           VARCHAR(10) NOT NULL DEFAULT 'PRE',  -- PRE or POST

    CONSTRAINT route_filters_phase_check CHECK (phase IN ('PRE','POST'))
);

CREATE INDEX idx_filters_route_id ON route_filters(route_id);
```

**Design decision on normalization:** Filters and predicates are in separate tables (not embedded as JSONB in the `routes` table) to enable querying — e.g., "which routes use the `CircuitBreakerFilter`?" and "which routes target path `/api/v1/orders/**`?". For a system with 10,000+ routes, these operational queries are valuable for impact analysis before making policy changes.

### Table: `rate_limit_policies`

```sql
CREATE TABLE rate_limit_policies (
    policy_id       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(255) NOT NULL UNIQUE,
    algorithm       VARCHAR(30) NOT NULL DEFAULT 'TOKEN_BUCKET',
    on_exceed       VARCHAR(30) NOT NULL DEFAULT 'REJECT_429',
    retry_after_strategy VARCHAR(20) NOT NULL DEFAULT 'DYNAMIC',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT rl_algorithm_check
        CHECK (algorithm IN ('FIXED_WINDOW','SLIDING_WINDOW','TOKEN_BUCKET','LEAKY_BUCKET')),
    CONSTRAINT rl_on_exceed_check
        CHECK (on_exceed IN ('REJECT_429','THROTTLE_DELAY','DEGRADE_TO_CACHED'))
);

CREATE TABLE rate_limits (
    limit_id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id           UUID NOT NULL REFERENCES rate_limit_policies(policy_id) ON DELETE CASCADE,
    dimension           VARCHAR(20) NOT NULL,   -- USER, IP, API_KEY, TENANT, ROUTE_GLOBAL
    requests_allowed    BIGINT NOT NULL,
    window_seconds      INTEGER NOT NULL,
    burst_allowed       BIGINT,                 -- for token bucket

    CONSTRAINT rl_dimension_check
        CHECK (dimension IN ('USER','IP','API_KEY','TENANT','ROUTE_GLOBAL'))
);
```

### Table: `api_keys`

```sql
CREATE TABLE api_keys (
    key_id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    key_hash            VARCHAR(64) NOT NULL UNIQUE,  -- SHA-256 hex
    key_prefix          VARCHAR(16) NOT NULL,
    owner_id            UUID NOT NULL REFERENCES developers(developer_id),
    tenant_id           UUID,
    name                VARCHAR(255) NOT NULL,
    scopes              TEXT[] NOT NULL DEFAULT '{}',
    allowed_patterns    TEXT[] NOT NULL DEFAULT '{}',
    rate_limit_policy_id UUID REFERENCES rate_limit_policies(policy_id),
    expires_at          TIMESTAMPTZ,
    revoked_at          TIMESTAMPTZ,
    last_used_at        TIMESTAMPTZ,
    metadata            JSONB NOT NULL DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_api_keys_hash ON api_keys(key_hash);
CREATE INDEX idx_api_keys_owner ON api_keys(owner_id);
CREATE INDEX idx_api_keys_active ON api_keys(key_hash)
    WHERE revoked_at IS NULL AND (expires_at IS NULL OR expires_at > NOW());
CREATE INDEX idx_api_keys_tenant ON api_keys(tenant_id) WHERE tenant_id IS NOT NULL;
```

**Critical index:** `idx_api_keys_hash` is the primary lookup index used by the key validation path. Since the gateway caches active keys in Redis, this index is only hit on cache misses (key not in Redis) or during key creation/revocation. However, it must be blazing fast for cache warming at gateway startup.

**Partial index `idx_api_keys_active`:** This index covers only active keys (not revoked, not expired). Most admin queries operate on active keys, so this partial index is much smaller than a full index on `key_hash`.

### Table: `developers`

```sql
CREATE TABLE developers (
    developer_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               VARCHAR(255) NOT NULL UNIQUE,
    organization_name   VARCHAR(255),
    plan                VARCHAR(20) NOT NULL DEFAULT 'FREE',
    daily_request_limit BIGINT NOT NULL DEFAULT 10000,
    monthly_request_limit BIGINT NOT NULL DEFAULT 100000,
    verified_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT dev_plan_check CHECK (plan IN ('FREE','PRO','ENTERPRISE'))
);

CREATE INDEX idx_developers_email ON developers(email);
CREATE INDEX idx_developers_plan ON developers(plan);
```

### Table: `config_audit_log`

```sql
CREATE TABLE config_audit_log (
    audit_id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    entity_type     VARCHAR(50) NOT NULL,   -- ROUTE, POLICY, API_KEY, DEVELOPER
    entity_id       UUID NOT NULL,
    action          VARCHAR(20) NOT NULL,   -- CREATED, UPDATED, DELETED, REVOKED
    actor_id        UUID NOT NULL,          -- admin user or system
    before_state    JSONB,
    after_state     JSONB,
    change_reason   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
)
PARTITION BY RANGE (created_at);

CREATE TABLE config_audit_log_2026_05
    PARTITION OF config_audit_log
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');

CREATE INDEX idx_audit_entity ON config_audit_log(entity_type, entity_id);
CREATE INDEX idx_audit_actor ON config_audit_log(actor_id);
CREATE INDEX idx_audit_created_at ON config_audit_log(created_at DESC);
```

**Partitioning strategy:** The audit log is partitioned by month. Old partitions (> 2 years) can be detached and archived to S3 without affecting current operations. New partitions are created by a maintenance job at the beginning of each month.

---

## Redis Data Structures

Redis is the operational heart of the Gateway Runtime. Every data structure is chosen for O(1) or O(log n) access at 500K+ ops/sec.

### Active Route Table

```
Key: gateway:routes:active
Type: Hash
Fields: {routeId → serialized RouteDefinition JSON}

Key: gateway:routes:version
Type: String (atomic counter)
Value: "42"  (incremented on every config change)
```

Gateway instances poll `gateway:routes:version` every 5 seconds. If the version is higher than their local version, they read `gateway:routes:active` and reload their in-memory route table. This is a lightweight polling mechanism — the version check is a single Redis GET (O(1)).

**Alternative:** Redis pub/sub or Kafka push. Pub/sub has delivery guarantees issues (missed messages if the subscriber is down). Kafka is more reliable but adds consumer complexity. The version-polling approach is simple, reliable, and adds at most 5 seconds of config propagation lag — acceptable for route changes.

### API Key Cache

```
Key: gateway:apikey:{keyHash}
Type: Hash
Fields:
  - keyId: uuid
  - ownerId: uuid
  - tenantId: uuid
  - scopes: ["orders:read","products:read"]  (JSON array)
  - rateLimitPolicyId: uuid
  - status: ACTIVE | REVOKED | EXPIRED

TTL: 300 seconds (5 minutes)
```

The gateway looks up an incoming API key's hash in Redis first. Cache miss → PostgreSQL lookup → re-cache. On revocation, the key record is immediately deleted from Redis (not just marked revoked), ensuring the gateway detects revocation on the next request (or within the Redis key TTL if the delete fails — see failure scenarios).

### JWKS Cache (Public Keys for JWT Validation)

```
Key: gateway:jwks:{issuerId}
Type: String (serialized JWKS JSON)
TTL: 300 seconds (5 minutes)

Key: gateway:jwks:{issuerId}:lastFetched
Type: String (ISO timestamp)
```

JWT validation is done entirely in-memory using cached public keys. The gateway never calls the IdP on the hot path. On cache expiry, one instance re-fetches JWKS and re-populates the cache. To prevent a thundering herd when the TTL expires simultaneously on 30 instances, a jittered TTL is used (base TTL ± 30 seconds per instance).

### Rate Limit Counters

The rate limit storage strategy depends on the algorithm:

**Fixed Window (simplest):**
```
Key: gateway:rl:fw:{dimension}:{dimensionValue}:{windowId}
Type: String (integer counter)
TTL: window_seconds * 2  (extra headroom for clock drift)
Commands: INCR, GETEX
```

**Token Bucket (more accurate, handles bursts):**
```
Key: gateway:rl:tb:{dimension}:{dimensionValue}
Type: Hash
Fields:
  - tokens: current token count (float, stored as string)
  - lastRefill: timestamp of last refill (epoch seconds)
TTL: managed by refill logic (no natural expiry)
Commands: Single Lua script (atomic read-modify-write)
```

**Sliding Window Log (most accurate, highest memory cost):**
```
Key: gateway:rl:sw:{dimension}:{dimensionValue}
Type: Sorted Set
Members: request timestamps (epoch milliseconds)
Score: same timestamp (for range queries)
Commands: ZADD, ZREMRANGEBYSCORE, ZCARD (wrapped in Lua script)
```

**Chosen approach:** Token bucket for user-level rate limiting (handles burst traffic gracefully — a user who has been idle is allowed to make a burst of requests). Fixed window for global route-level rate limiting (simpler and cheaper). The choice is per-policy, configured in `rate_limit_policies.algorithm`.

### Circuit Breaker State

```
Key: gateway:cb:{instanceId}:{serviceId}
Type: Hash
Fields:
  - state: CLOSED | OPEN | HALF_OPEN
  - failureCount: integer
  - successCount: integer (for HALF_OPEN)
  - openedAt: ISO timestamp
  - nextAttemptAt: ISO timestamp

TTL: None (CB state is instance-local; persisted in Redis only for dashboarding)
```

**Design decision:** Circuit breaker state is NOT shared across gateway instances. Each instance maintains its own CB state machine. Rationale: sharing CB state requires distributed consensus (Redis or ZooKeeper coordination), which adds latency and complexity. The cost of not sharing is that instances may open/close their CBs at slightly different times, creating a brief period of inconsistent routing. This is acceptable — the upstream service's behavior (failing or recovering) will eventually cause all instances to converge on the same CB state.

---

## Indexing Strategy Summary

| Table | Index | Type | Purpose |
|---|---|---|---|
| routes | status + deleted_at | Partial B-tree | Active route queries |
| routes | order_priority | B-tree | Ordered route loading |
| routes | tags | GIN | Arbitrary tag filtering |
| routes | updated_at | B-tree | Incremental sync polling |
| route_predicates | route_id | B-tree | Predicate loading per route |
| route_filters | route_id | B-tree | Filter loading per route |
| api_keys | key_hash | B-tree (unique) | Key lookup by hash |
| api_keys | owner_id | B-tree | Keys per developer |
| api_keys | tenant_id (partial) | Partial B-tree | Multi-tenant queries |
| developers | email | B-tree (unique) | Login lookup |
| config_audit_log | entity_type + entity_id | B-tree | Audit history per entity |
| config_audit_log | created_at DESC | B-tree | Recent changes query |

---

## Data Archival Strategy

| Data | Retention | Archival Destination |
|---|---|---|
| Deleted routes | 30 days in DB, then archive | S3 Parquet (for analytics) |
| Audit logs | 2 years in DB (partitioned), then archive | S3 Glacier (compliance) |
| Revoked API keys | 1 year in DB, then archive | S3 Parquet |
| Rate limit counters | Redis TTL only (no archival) | — |
| Access logs | Kafka 7 days, Elasticsearch 90 days, S3 7 years | S3 Glacier (compliance) |

---

## Multi-Tenancy Considerations

The `tenant_id` field appears in `api_keys` and can be added to route `tags`. For a multi-tenant SaaS deployment:

- **Route isolation:** Tenant-specific routes include a `HeaderPredicate` matching `X-Tenant-ID`. The gateway extracts `tenant_id` from the JWT and rejects requests where the JWT tenant doesn't match the route's required tenant.
- **Rate limit isolation:** Rate limit counters use `tenant_id` as the dimension value when the `TENANT` dimension is configured. Tenant A's traffic does not affect Tenant B's rate limit counters.
- **Data isolation in PostgreSQL:** A single schema with `tenant_id` columns (schema-per-tenant is overkill for the control plane of a gateway).

---

## Failure Mode: Redis Unavailability

If Redis becomes unavailable:

1. **Rate limiting degrades:** Gateway instances switch to local in-memory counters (fixed window, per-instance). Each instance allows up to `limit / instance_count` requests per window. This is approximate but prevents complete service disruption.
2. **API key lookup fails:** Gateway can be configured to ALLOW traffic without key validation (fail-open) or BLOCK traffic (fail-closed). The choice is per-route and configured in the route's filter args.
3. **JWKS validation degraded:** In-memory JWKS cache continues to work until the JVM process restarts or the cache TTL expires (5 minutes). If the JWKS cache expires and Redis is unavailable, the gateway FAILS requests requiring JWT validation (auth cannot be degraded safely).
4. **Route table stale:** The in-memory route table does not expire. The gateway continues to use the last known good route table. Config changes will not propagate until Redis recovers.

---

## Interview-Level Discussion Points

1. **Why PostgreSQL over MongoDB for the control plane?** The route/policy/key data is highly relational (routes reference policies, keys reference developers). ACID transactions are required for operations like "revoke key AND publish event AND update audit log." MongoDB's eventual consistency model would require application-level transaction management.

2. **Redis as a rate limit store — what happens when a Redis primary shard fails over?** During failover (typically 15–30 seconds for Redis Sentinel), the rate limit counters for keys on that shard are unavailable. The gateway either fails open (allows traffic without rate checking) or uses in-memory fallback counters (approximate). The rate limit window partially resets — some users may get slightly more requests than their limit during the failover window. Is this acceptable?

3. **Token bucket vs. fixed window — the burst problem:** Fixed window allows a burst of `limit` requests at the end of one window and another `limit` requests at the start of the next window — effectively 2x the limit in 1 second. The token bucket prevents this by replenishing tokens at a steady rate. However, token bucket requires an atomic read-modify-write (Lua script), which is more expensive than a simple INCR. At 500K RPS, this adds up. What's the right tradeoff?

4. **Partial index for active API keys:** The partial index `WHERE revoked_at IS NULL AND expires_at > NOW()` is evaluated at index creation time. It does not dynamically exclude keys as they expire. An expired key's hash remains in the index. What is the operational implication? How do you handle this?

5. **Audit log partitioning — what are the operational risks of monthly partitions?** If a partition creation job fails to run at the start of the month, INSERT statements into the audit log fail (no matching partition). How do you detect this? What's the recovery procedure?
