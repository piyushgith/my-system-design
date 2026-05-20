# 09 — Caching Strategy: KYC / Identity Verification Pipeline

---

## Objective

Define caching for the KYC pipeline — status reads, idempotency, vendor result caching, and review queue. KYC is not a high-frequency read system (unlike a ledger), so caching is targeted at specific bottlenecks.

---

## Caching Needs Analysis

KYC processes 10,000–50,000 applications/day. The main read patterns are:
1. Status polling by Onboarding service (10x per application = 100,000–500,000 reads/day)
2. Idempotency key lookup on submission (once per application)
3. Review queue reads by compliance officers (low frequency)
4. Vendor result lookup (OCR result reuse for re-KYC with same document)

At these volumes, PostgreSQL can handle reads without Redis for most paths. Redis is used strategically for:
- Status caching (eliminate 90% of DB reads for polling)
- Idempotency fast path
- Vendor result caching (cost savings)

---

## Cache Layer Design

### Cache 1: Application Status Cache

**Key:** `kyc:status:{application_id}`

**Value:**
```json
{
  "application_id": "kyc_app_uuid",
  "status": "LIVENESS_PENDING",
  "steps": [{"step_type": "DOCUMENT_OCR", "status": "PASS"}, ...],
  "updated_at": "2024-01-15T10:30:15Z"
}
```

**TTL:** 30 seconds

**Population:** Write-through — on every state transition, the pipeline writes the new status to Redis before returning.

**Invalidation:** On state transition, `SET` with new status + TTL reset.

**Why 30-second TTL (not shorter)?** KYC takes 5–10 minutes. Polling every 5 seconds × 10 minutes = 120 polls per application. With 30-second cache, most polls are cache hits. The 30-second lag is invisible to users who are waiting minutes anyway.

**Why NOT shorter TTL?** 5-second TTL would still require DB reads every 30 seconds (due to cache miss rate and TTL spread). 30 seconds balances freshness with DB load.

---

### Cache 2: Idempotency Cache

**Key:** `kyc:idempotency:{idempotency_key}`

**Value:** `application_id` (UUID string)

**TTL:** 24 hours

**Population:** On successful application INSERT, SET this key.

**Fallback:** If Redis unavailable, fall through to DB UNIQUE constraint check — slower but correct.

This is identical to the ledger's idempotency design. The same pattern applies across all financial domain services.

---

### Cache 3: Vendor Result Cache (OCR Deduplication)

**Key:** `kyc:vendor:ocr:{document_hash}:{document_type}`

`document_hash` = SHA-256 of the document S3 ETag (deterministic hash of document content)

**Value:**
```json
{
  "extracted_name": "Piyush Prasad",
  "extracted_dob": "1995-06-15",
  "document_id_hash": "hmac_hash",
  "confidence_score": 0.98,
  "vendor": "DIGILOCKER",
  "cached_at": "2024-01-15T10:30:00Z"
}
```

**TTL:** 30 days

**When used:** On re-KYC (re-verification), if the user re-uploads the same document (same file), the document hash matches → return cached OCR result without calling the vendor API.

**Cost savings:** Re-KYC at 500/day × $1/OCR call = $500/day in vendor costs. If 70% of re-KYC uses the same document → saves $350/day = ~$127,000/year.

**Security:** The cache key uses the document S3 ETag hash (not any PII). The cached value contains extracted data but not the document image. Cached values are encrypted at rest in Redis (Redis encryption or application-layer).

---

### Cache 4: Review Queue Count (Compliance Dashboard)

**Key:** `kyc:review:queue_depth:{priority}`

**Value:** Count (integer)

**TTL:** 30 seconds

**Purpose:** The compliance dashboard header shows "142 pending cases (15 HIGH)". Without this cache, every dashboard page load queries `SELECT COUNT(*) FROM manual_review_queue WHERE ... GROUP BY priority` — expensive at high queue depths.

**Population:** Incremented on `INSERT INTO manual_review_queue`, decremented on `UPDATE SET completed_at`.

**Consistency:** Count may be off by ±5 (Redis TTL-based) — acceptable for a dashboard display metric.

---

### Cache 5: Vendor Circuit Breaker State (Redis-Backed)

**Key:** `kyc:vendor:circuit:{vendor_id}`

**Value:** `{state: "OPEN|CLOSED|HALF_OPEN", failure_count: 12, last_failure: "timestamp"}`

**TTL:** None — updated by circuit breaker logic

**Purpose:** Circuit breaker state is shared across all pipeline worker pods. If pod A detects Onfido is failing, pod B should also know immediately — not learn after its own 5 failures.

**Mechanism:**
- Redis Lua script: `INCR kyc:vendor:failures:{vendor_id}` with 60-second sliding window
- If failures > 5: set circuit to OPEN in Redis
- After 60 seconds: set to HALF_OPEN, allow 1 probe request
- On probe success: set to CLOSED, reset failure counter

---

## Redis Configuration for KYC

| Parameter | Value | Reason |
|---|---|---|
| `maxmemory-policy` | `volatile-lru` | Evict TTL-expired keys first; never evict idempotency keys with no TTL |
| `maxmemory` | 2 GB | KYC cache is small (status 10KB × 50K apps = 500 MB peak) |
| `save` | Disabled | Cache is ephemeral; DB is source of truth |
| `appendonly` | Disabled | Same reason |
| Cluster mode | Single instance + replica | Low throughput doesn't justify cluster |

**Unlike the ledger:** KYC doesn't need Redis cluster (far lower read volume). A single Redis with one replica for HA is sufficient.

---

## What is NOT Cached

| Data | Why Not Cached |
|---|---|
| PII (personal_data) | Never in Redis — PII exposure risk. All PII reads go to PostgreSQL via encrypted blob |
| State transition history | Immutable, read infrequently — DB read is fine |
| Manual review decisions | Compliance decisions must come from authoritative source (DB) |
| Document images | Never — too large (1–3 MB each), served directly from S3 |
| Watchlist screening results | Real-time screening required; caching a watchlist result means new entries won't be detected |

---

## Interview Discussion Points

- **Why cache the status if applications only last a few minutes?** The 10x polling pattern means 100K–500K status reads/day. At 10,000 applications/day in 8 business hours: 50K/day peak = 1.7 applications/second × 10 polls = 17 reads/second. PostgreSQL can handle this without Redis. But with Redis: 90%+ cache hit rate reduces DB reads to < 2/second — protecting the DB for write operations
- **Why not cache watchlist screening results?** A watchlist hit result depends on the current state of the watchlist. A person cleared yesterday may appear on a new sanctions list today. Caching watchlist results introduces a window where a newly-sanctioned customer remains KYC-approved. For compliance, watchlist checks must be real-time. Re-verification handles the periodic re-screening problem
- **How do you handle Redis unavailability in the KYC pipeline?** All cache lookups are wrapped in try-catch with a log. On Redis miss: fall through to DB. Status reads → DB query. Idempotency → DB UNIQUE constraint. Vendor circuit state → in-memory circuit breaker per pod (each pod has its own, but less efficient). OCR cache → call vendor API again (more expensive, not a correctness issue). KYC correctness does not depend on Redis — it's purely a performance optimization
- **What happens to the vendor OCR cache when a document is corrected (e.g., user gets a new Aadhaar)?** The new Aadhaar has a different document hash (different file content). The old cache entry remains until TTL expires (30 days) — no staleness problem because it's keyed by the old document hash. New document gets a fresh OCR call
