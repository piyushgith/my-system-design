# 09 — Caching Strategy: Credit Scoring Engine

---

## Objective

Define the multi-layer caching strategy for the credit scoring engine. Three distinct caches serve different purposes: the score cache (short-lived computed scores), the feature store (pre-computed features per user), and the idempotency cache (duplicate request deduplication). Each has different consistency requirements, TTL strategies, and failure modes.

---

## Cache Architecture Overview

```mermaid
graph TB
    subgraph Callers
        LOAN[Loan Service]
        CARD[Card Service]
    end

    subgraph ScoringEngine["Scoring Engine"]
        API[Scoring API]
        IDEMPOTENCY[Idempotency Check]
        ASSEMBLY[Feature Assembly]
        MODEL[ONNX Inference]
    end

    subgraph CacheLayer["Cache Layer (Redis)"]
        SCORE_CACHE[Score Cache<br/>score:{user_id}:{product_type}<br/>TTL: 5 minutes]
        IDEMPOTENCY_CACHE[Idempotency Cache<br/>idempotent:{request_id}<br/>TTL: 24 hours]
        FEATURE_STORE[Feature Store<br/>feature:{user_id}:{feature_name}<br/>TTL: varies by group]
        CONSENT_CACHE[Consent Cache<br/>consent:{user_id}:bureau<br/>TTL: 5 minutes]
    end

    LOAN --> API
    API --> IDEMPOTENCY --> IDEMPOTENCY_CACHE
    API --> SCORE_CACHE
    ASSEMBLY --> FEATURE_STORE
    API --> CONSENT_CACHE
```

---

## Cache 1: Score Cache

**Purpose:** avoid recomputing the same score for the same user+product within a short window. Primary latency optimization.

### Key Design

```
Key:   score:{user_id}:{product_type}
Value: {score, band, model_version, reason_codes[], source, computed_at}
TTL:   300 seconds (5 minutes)
Store: Redis (separate cluster from feature store)
```

### Read-Through Behavior

```
1. GET score:{user_id}:{product_type}
2. HIT  → return cached score, source=CACHE
3. MISS → compute (feature assembly + ONNX inference)
         → SET score:{user_id}:{product_type} EX 300 (async, non-blocking)
         → return fresh score, source=REAL_TIME
```

### Invalidation

| Trigger | Action |
|---|---|
| Bureau feature updated | DEL score:{user_id}:* (pattern delete via SCAN + DEL) |
| `force_refresh=true` request | GET bypassed, SET on compute, effectively refresh |
| Model promotion (new champion) | NO invalidation — model_version stored in score; old cached scores served until TTL expires |

**Pattern delete trade-off:** `SCAN + DEL` for all product types per user (at most 4 keys: PERSONAL_LOAN, HOME_LOAN, CREDIT_CARD, BNPL). Cost: < 1ms. Benefit: ensures fresh score after bureau update.

**Model promotion decision:** NOT invalidating score cache on model promotion is deliberate. During the 5-minute TTL window after promotion, some users receive champion v2.3 scores (from cache), while others receive champion v2.4 (freshly computed). Acceptable — champion-to-champion model transitions should produce similar scores (< 20-point delta for most users). Challenger → champion promotions increase challenger traffic from 10% → 100% naturally via cache TTL expiry.

### Cache Miss Stampede Protection

**Problem:** 1000 concurrent requests for the same user_id (high-profile loan application) all miss cache simultaneously → 1000 parallel ONNX inferences.

**Fix:**
```
1. Check score cache → miss
2. SETNX scoring_lock:{user_id}:{product_type} "1" EX 10
   → If acquired: compute score, SET score cache, DEL lock
   → If not acquired: short-poll score cache (3 retries × 5ms)
3. After 3 retries: compute independently (fail-safe)
```

Expected contention: low (callers are loan/card services, not thousands of end users hitting the same user simultaneously). Lock overhead < 0.5ms.

---

## Cache 2: Feature Store

**Purpose:** pre-computed feature values per user. The primary read model for CQRS. Updated by the feature pipeline (not the scoring engine).

### Key Design

```
Key Pattern: feature:{user_id}:{feature_name}
Values:      String (numeric or boolean serialized as string)
TTL:         Per feature group (see table below)
Store:       Redis cluster (dedicated, high-memory nodes)
```

### TTL Strategy by Feature Group

| Feature Group | Example Key | TTL | Refresh Source | Rationale |
|---|---|---|---|---|
| Bureau | `feature:usr_abc:bureau.cibil_score` | 32 days | Bureau refresh job (30-day cycle) | Bureau data updates monthly; 2-day buffer before eviction |
| Behavioral | `feature:usr_abc:behavior.upi_txn_count_30d` | 2 days | Daily batch (midnight) + real-time events | Daily batch refreshes; 2-day TTL tolerates overnight miss |
| Performance | `feature:usr_abc:performance.current_emi_dpd` | 24 hours | Kafka Streams (EMI events) | Updated on every EMI event; 24h as fallback |
| Account | `feature:usr_abc:account.age_months` | 7 days | Weekly batch | Slow-changing; weekly refresh sufficient |
| Meta | `feature:usr_abc:meta.bureau_as_of` | No TTL | Explicit update only | Always present after first bureau pull |

### Read Pattern: MGET (Single Round-Trip)

```
MGET
  feature:usr_abc:bureau.cibil_score
  feature:usr_abc:bureau.dpd_last_6m
  feature:usr_abc:bureau.credit_utilization
  feature:usr_abc:bureau.inquiry_count_last_90d
  feature:usr_abc:behavior.upi_txn_count_30d
  feature:usr_abc:behavior.avg_monthly_credit
  feature:usr_abc:performance.current_emi_dpd
  feature:usr_abc:account.age_months
  ... (15-20 total keys)

→ Single Redis round-trip: < 3ms
```

### Missing Feature Handling

`MGET` returns `nil` for missing keys. `FeatureAssemblyService` applies defaults:
- Missing bureau feature → use `feature_definitions.default_value` (e.g., bureau.cibil_score default = 0)
- > 50% bureau features missing → `is_thin_file = true` → route to NTC model
- All features missing → `USER_FEATURES_NOT_FOUND` error (user not onboarded)

### Write Pattern (Feature Pipeline)

```
MSET
  feature:usr_abc:bureau.cibil_score "720"
  feature:usr_abc:bureau.dpd_last_6m "0"
  ...

EXPIREAT feature:usr_abc:bureau.cibil_score {now + 32days}
EXPIREAT feature:usr_abc:bureau.dpd_last_6m {now + 32days}
```

`MSET` does not set TTL per key — requires explicit EXPIREAT per key after MSET. Feature pipeline uses a Lua script to atomically set value + TTL.

---

## Cache 3: Idempotency Cache

**Purpose:** deduplicate score requests with the same `request_id` within 24 hours.

### Key Design

```
Key:   idempotent:{request_id}
Value: {score, band, model_version, computed_at}  (minimal score response)
TTL:   86400 seconds (24 hours)
Store: Redis (same cluster as score cache, but different key prefix)
Persistence: AOF enabled (idempotency must survive Redis restart)
```

### Flow

```
1. POST /scores {request_id: "req_xyz"}
2. GET idempotent:req_xyz
   → HIT: return cached response (same score as original computation)
   → MISS: compute score, SET idempotent:req_xyz EX 86400
```

**Why AOF on idempotency cache?** Score cache can be lost (just recompute). Idempotency cache loss means a duplicate `request_id` would compute a new score — same user, same product, but potentially different score if features changed. This could create two `score_history` records with the same `request_id` (PRIMARY KEY violation). AOF ensures idempotency cache survives Redis restarts within 24h window.

**Backstop:** `score_history.request_id` is PRIMARY KEY (UUID). If Redis idempotency cache is missed (after restart), the INSERT into score_history fails with PK conflict → application catches and returns existing score from score_history. Two-layer idempotency.

---

## Cache 4: Consent Cache

**Purpose:** avoid calling Consent Management Service on every score request.

```
Key:   consent:{user_id}:bureau
Value: {valid: true/false, expires_at: "..."}
TTL:   300 seconds (5 minutes)
```

**Invalidation:** Consent Service publishes `consent.revoked` event → Kafka consumer deletes key from consent cache. Consent expiry handled by TTL (cache key expires at max(consent_expiry, 5min TTL)).

---

## Redis Cluster Configuration

### Two Redis Clusters

| Cluster | Purpose | Size | Persistence |
|---|---|---|---|
| Redis A: Feature Store | Feature values | 32 GB (10M users × 20 features × ~150 bytes) | RDB daily snapshot (features are rebuilable from raw data) |
| Redis B: Score + Idempotency + Consent | All short-lived caches | 8 GB | AOF for idempotency prefix; no persistence for score/consent prefix |

**Separation rationale:** feature store data is large (32 GB), slow-evicting (32-day TTL). Score cache is small (10M users × 4 product types × 200 bytes = ~8 GB), fast-evicting (5-min TTL). Mixing them in one cluster causes eviction pressure: score cache keys have shorter TTL but would compete with feature keys for eviction when memory is full.

### Eviction Policy

- Feature Store: `noeviction` (never evict — if full, return error to feature pipeline writer; alerting fires)
- Score Cache: `allkeys-lru` (evict least-recently-used scores when full; recomputation is acceptable)
- Idempotency Cache: `volatile-lru` (evict only keys with TTL; protect keys without TTL if any)

---

## Cache Failure Scenarios

### Score Cache Unavailable

**Impact:** all requests become cache misses → full ONNX inference for every request → latency increases from ~2ms to ~15ms. At 50 RPS: 50 × 15ms = 750ms of scoring compute per second. Sustainable — scoring pods handle it.

**No circuit breaker needed on score cache.** Fallback is graceful: compute fresh score. Acceptable degradation.

### Feature Store Unavailable

**Impact:** `FeatureAssemblyService.assembleFeatures()` → Redis connection timeout → cannot retrieve features → score computation blocked.

**Mitigation:** 
1. If Redis feature store unavailable: attempt to serve last score from `score_history` PostgreSQL (by user_id, latest computed_at)
2. Return response with `source=FALLBACK_CACHE`, `X-Score-Source: FALLBACK_CACHE` header, include `score_age_seconds` in response
3. Return 503 only if no fallback score exists (new user, never scored)

### Idempotency Cache Unavailable

**Impact:** duplicate `request_id` re-processes → second INSERT into score_history → PRIMARY KEY conflict → caught → return existing record from score_history. Idempotency preserved via DB backstop. No data corruption. Latency penalty: +5ms (DB lookup on idempotency miss + conflict).

---

## Interview Discussion Points

- **Why store reason_codes in the score cache? Isn't that a lot of data?** Reason codes are 4–6 objects with short strings each (~500 bytes). Score cache value = ~1KB per key. 10M users × 4 product types = 40M keys × 1KB = 40 GB. Too large. Fix: score cache stores only {score, band, model_version, computed_at, source}. Reason codes are re-fetched from score_history on GET /scores/{id}. Cache hit for POST /scores serves cached score without reason codes — then a separate GET call fetches them. OR: include reason_code codes only (not descriptions) in cache; descriptions looked up from config table. Tradeoff: two hops vs 40 GB Redis
- **What happens when a user's bureau data is refreshed 3 minutes after they submitted a loan application?** Bureau update → score cache invalidated → next scoring request gets fresh features + new score. If the loan is already in "under review" state using the old score: the Loan Service receives `credit.score.significant_change` event (if delta > 20 points) → re-evaluates application. If delta < 20 points: application continues with old score (score stored in application record at submission time, not re-fetched dynamically)
- **Why not use an L1 in-process cache (JVM heap) for features?** In-process feature cache would: (1) serve stale features longer (JVM cache invalidation requires pod restart or pub-sub between pods); (2) consume scoring engine heap memory (model + features = 500MB+ per pod); (3) create cache invalidation complexity across 12 pods. Redis shared cache is simpler, consistent, and invalidation is single-point. JVM heap cache is a premature optimization for the current scale
- **Is 5-minute score cache TTL appropriate for all products?** No — product-specific TTL is better. Pre-screening (CRM marketing campaign): 60-minute TTL (slightly stale score acceptable). Final loan decision: `force_refresh=true` (no cache). Credit card limit check: 15-minute TTL. Implementation: cache key includes product_type already; TTL can be configurable per product_type in application config without code changes
