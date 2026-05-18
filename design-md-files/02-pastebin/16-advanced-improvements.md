# 16 — Advanced Improvements: Pastebin / Code Sharing Platform

---

## Objective

Explore advanced technical improvements beyond V3. These are the kinds of discussions that differentiate senior engineers from staff/principal engineers in interviews — knowing *when* to apply them and *why* they're worth the complexity cost.

---

## 1. Compression — Reducing Storage Cost at Scale

### Why It Matters
At 100 GB/month of new paste content, over 5 years = 6 TB of raw storage. Compression can reduce this to 1.5–2 TB (3-4x ratio for code/text).

### Compression Strategy

| Algorithm | Ratio | Speed | Best For |
|-----------|-------|-------|---------|
| Gzip (level 6) | 3-4x | Medium | S3 storage (one-time compress) |
| LZ4 | 2-3x | Very fast | Redis values, in-flight |
| Snappy | 2x | Fast | Kafka messages |
| Brotli | 4-6x | Slow | CDN-served content (pre-compressed) |
| Zstd | 4-5x | Fast | S3 storage (modern default) |

**Implementation:**
- S3 objects: compress with Zstd before upload, store with `Content-Encoding: zstd`
- CDN: serve Brotli-compressed content for browsers supporting it (`Accept-Encoding: br`)
- Redis: LZ4-compress values > 512 bytes before storing
- Kafka: Snappy or Zstd at topic-level compression

**Cost savings at 6 TB → 1.5 TB:** S3 Standard at $0.023/GB = $138/month → $34.50/month saved.

### Deduplication + Compression Combined

Content hash (SHA-256) ensures identical content shares one S3 object. Combined with Zstd compression:
- A 10 KB config file shared by 1,000 users → stored once as ~2.5 KB
- Savings: 10,000 KB → 2.5 KB (4,000x effective storage reduction for that content)

---

## 2. Client-Side Syntax Highlighting vs Server-Side Rendering

### Current Approach (Recommended)
- Server stores language hint (e.g., `"java"`)
- Client downloads PrismJS or Highlight.js
- Syntax highlighting applied in browser (JS)

**Pros:** Zero server CPU for highlighting; supports any browser; library updated independently.
**Cons:** JS bundle size (~100 KB for full Highlight.js); slight FOUC (flash of unstyled content).

### Alternative: Server-Side Rendered HTML

Server pre-renders highlighted HTML using `Chroma` (Go) or `Pygments` (Python) library.

```
Paste content → Chroma → HTML with <span class="kw">public</span>
```

**Pros:** No JS required; instant rendering; better for embed use cases.
**Cons:** Increases server CPU on every cache miss; increases S3 object size (HTML > plain text); language ecosystem mismatch (Java app calling Python subprocess).

**Recommendation:** Client-side for MVP/V1/V2. Server-side pre-rendering as an option for embed iFrames (V4).

---

## 3. Collaborative Annotation System

Users annotate specific lines of a paste (like GitHub PR line comments).

### Design Challenges
- Annotations are mutable (add, delete, reply)
- Paste content is immutable — annotation positions must reference line numbers
- If paste is versioned, annotations become stale when content changes

### Data Model
```sql
CREATE TABLE paste.annotations (
    id          UUID PRIMARY KEY,
    paste_id    UUID NOT NULL,
    version_id  UUID NOT NULL,         -- locked to version
    line_start  INT NOT NULL,
    line_end    INT,
    content     TEXT NOT NULL,
    author_id   UUID NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL
);
```

### Real-Time Annotation Delivery
- Use WebSocket or Server-Sent Events (SSE) for real-time new annotation notifications
- On paste view: initial annotation load via REST
- New annotations pushed via SSE to all current viewers

**Operational cost:** WebSocket/SSE connections maintained per paste being viewed → stateful. Need sticky sessions or a pub/sub backend (Redis Pub/Sub per paste channel).

---

## 4. Embed Support

Allow pastes to be embedded in external pages:

```html
<iframe src="https://pastebin.io/embed/abc123" 
        width="100%" height="400" frameborder="0"></iframe>
```

### Embed-Specific Concerns

1. **X-Frame-Options:** Default `DENY` prevents embedding. Embed endpoint returns `X-Frame-Options: SAMEORIGIN` (or allow-list specific domains)
2. **CSP frame-ancestors:** `frame-ancestors 'self' *.trusted-site.com`
3. **Sandboxed rendering:** Embed page uses `sandbox="allow-scripts"` attribute — prevents embedded content from accessing parent cookies
4. **No auth in embed:** Embedded pastes must be PUBLIC or UNLISTED
5. **Themed embed:** Support `?theme=dark&lang=java` query params for styling

---

## 5. Search Within User's Pastes

Allow authenticated users to search their own paste content (not a global search engine).

### Why Not Full-Text Search Immediately?

Paste content in S3 is not indexed. Full-text search requires:
- Indexing content on creation
- A search index store (Elasticsearch or PostgreSQL `pg_trgm`)
- Keeping index in sync with deletions

**Scope-limited:** Only searching the authenticated user's own pastes (not all public pastes).

### Implementation Options

**Option A: PostgreSQL `pg_trgm` (Simple)**
- For inline pastes: trigram index on `content_inline`
- For S3 pastes: not searchable (content not in DB)
- Limitation: only works for small pastes

**Option B: Elasticsearch (Full Power)**
- On paste creation: publish to `paste.created` Kafka topic
- Search indexer consumer: fetch S3 content, index in Elasticsearch
- Field mapping: `{ paste_id, owner_id, title, language, content_text }`
- Search: `GET /api/v1/users/me/pastes/search?q=redis+cache`
- On delete: remove from ES index

**Elasticsearch scaling concern:**
- Index only authenticated users' paste content (not anonymous pastes)
- Use `owner_id` as a routing key → all user's pastes on same shard → efficient per-user queries
- Index size: at 100 pastes/user average and 1M users = 100M indexed documents
- Manageable on a 3-node Elasticsearch cluster

---

## 6. URL Shortener as a Sub-Feature

Pastebin's short key mechanism is essentially a URL shortener. Expose this as a feature:

```
POST /api/v1/links
{ "url": "https://very-long-url.example.com/path?query=params" }
→ { "shortKey": "lnk123", "url": "https://pastebin.io/l/lnk123" }
```

Reuses the existing short key generation, metadata table (new `type` column: `PASTE | LINK`), and redirect infrastructure.

**Monetization angle:** Link shortening is a separate paid feature (branded shortlinks, analytics).

---

## 7. API Webhooks

Programmatic users want to be notified when:
- Their paste is viewed (first view, every N views)
- Their paste is about to expire (24h warning)
- Their paste was flagged for abuse

### Webhook Design
```sql
CREATE TABLE paste.webhooks (
    id          UUID PRIMARY KEY,
    user_id     UUID NOT NULL,
    url         VARCHAR(2048) NOT NULL,
    secret      VARCHAR(128) NOT NULL,    -- HMAC signing secret
    events      VARCHAR[] NOT NULL,        -- ['paste.viewed', 'paste.expired']
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL
);
```

**Webhook delivery:**
- Kafka consumer (webhook-delivery-consumer) subscribes to relevant topics
- For each event: find matching webhooks for the paste owner, POST to webhook URL
- Payload signed with HMAC-SHA256 using webhook secret (client verifies)
- Retry: 3 attempts with exponential backoff; on failure → mark webhook as failing

**Why HMAC signing?** Webhook receiver can't trust the IP. HMAC signature proves payload came from Pastebin (not a spoofed request).

---

## 8. Content Archive and Data Export (GDPR Compliance)

**GDPR right to access:** User can request all their data.
**GDPR right to erasure:** User can request deletion of all their data.

### Data Export (GDPR Article 15)
```
1. User requests: POST /api/v1/users/me/export
2. System: collect all user's pastes, metadata, view stats, API keys
3. System: package as ZIP (JSON metadata + content files)
4. System: upload ZIP to S3 with user-specific presigned URL
5. System: email user with download link (valid 24 hours)
```

This is an async job (paste content may be in S3, needs assembly). Kafka event to trigger.

### Erasure (GDPR Article 17)
```
1. User requests: DELETE /api/v1/users/me
2. System: soft-delete user account
3. System: cascade delete all user's pastes
4. System: publish deletion events → S3 cleanup, CDN invalidation
5. System: anonymize analytics data (remove owner reference)
6. System: retain audit log (legal obligation overrides GDPR erasure for audit trail)
```

**Tension:** GDPR requires erasure, but audit logs must be retained for legal/financial compliance. Resolution: audit logs retain event type and timestamp but replace userId with a pseudonym hash.

---

## 9. Architecture Self-Critique

### What This Architecture Does Well
- Simple, understandable data model (single Paste aggregate)
- Immutable content = trivial caching (no cache invalidation on content change)
- Outbox pattern eliminates the dual-write problem
- Modular boundaries allow clean microservice extraction

### Known Weaknesses

| Weakness | Impact | When It Becomes a Problem |
|---------|--------|--------------------------|
| Single S3 region for writes | Cross-region write latency | When significant traffic from non-primary regions |
| Cleanup is eventually consistent (minutes, not seconds) | Paste accessible past expiry | Rarely a real problem; user expectation is manageable |
| Anonymous paste spam at scale | Storage cost, DB bloat | At 100M anonymous pastes/month |
| Single Kafka cluster | Kafka outage pauses cleanup/analytics | Mitigated by outbox; low risk but not zero |
| Redis memory bound | Cache hit drops if Redis is too small | Solvable by increasing Redis size (linear cost) |
| No full-text search at launch | Users cannot find old pastes | Only a problem if paste count grows and search is requested |

### What a Taking Interviewer Will Challenge

1. **"Your cleanup is eventually consistent — show me the exact sequence where a user sees an expired paste."**
> User requests expired paste → cache miss → DB query returns paste (is_deleted=FALSE, cleanup not yet processed) → paste served. Cleanup processes within 5 minutes → subsequent requests return 410. This is the eventual consistency window. Acceptable per requirements.

2. **"You said S3 is for content ≥ 1 KB. What if the average paste is 500 bytes?"**
> Then inline DB storage handles everything — no S3 needed for MVP. The threshold is a performance optimization, not a correctness requirement. If average sizes change, adjust the threshold.

3. **"What about metadata search — can a user search public pastes by language or title?"**
> Not in V1/V2. Add Elasticsearch in V3 for user-scoped search. Global public paste search (like Google for code) would require a much larger Elasticsearch cluster and crawler — treat as a separate product.

4. **"How do you prevent one user from exhausting your S3 storage?"**
> Per-user storage quota. Track `SUM(content_size) WHERE owner_id = ?` at creation time. Reject if quota exceeded. Default quota: 1 GB for free users, configurable for paid tiers.

---

## Interview Discussion Points

- At what scale would you add Brotli pre-compression to S3 objects? What's the tradeoff?
- How would you implement per-user storage quotas without making paste creation slow?
- What is HMAC webhook signing and why is it more secure than IP allowlisting?
- How would you design a search feature that only allows users to search their own pastes? (Elasticsearch routing by owner_id)
- What is the GDPR tension between audit log retention and the right to erasure? How do you resolve it?
- If you could change one architectural decision in retrospect, what would it be and why?
