# 00 — Requirements Analysis: Pastebin / Code Sharing Platform

---

## Objective

Define the functional and non-functional requirements for a production-grade Pastebin-style code and text sharing platform. Establish scale estimates and capacity planning baselines before any architectural decisions are made.

---

## Functional Requirements

### Core (Must Have)

| # | Requirement |
|---|-------------|
| F1 | Users can create a paste with plain text or code content |
| F2 | Each paste gets a unique short URL/key for sharing |
| F3 | Users can view paste content via the short URL |
| F4 | Paste content can be read in raw format (plain text) |
| F5 | Pastes can have an expiration: 1 hour, 1 day, 1 week, 1 month, never |
| F6 | Pastes can be Public (anyone), Unlisted (link-only), or Private (owner only) |
| F7 | Anonymous paste creation is allowed (no login required for public pastes) |
| F8 | Authenticated users can view and manage their paste history |
| F9 | Authenticated users can delete their own pastes |
| F10 | Paste content has a maximum size limit (10 MB) |

### Extended (Should Have)

| # | Requirement |
|---|-------------|
| F11 | Syntax highlighting based on declared programming language |
| F12 | Password-protected pastes (optional password for viewing) |
| F13 | Duplicate/fork a paste |
| F14 | View count tracking per paste |
| F15 | Custom short alias (e.g., `/p/my-script`) for authenticated users |

### Advanced (Nice to Have)

| # | Requirement |
|---|-------------|
| F16 | Paste versioning — edit a paste and retain history |
| F17 | Diff view between two paste versions |
| F18 | Embed support — paste rendered as iFrame |
| F19 | API access — programmatic paste creation with API key |
| F20 | Abuse detection — automatic flagging of malicious content |

---

## Non-Functional Requirements

| Category | Requirement |
|----------|-------------|
| **Availability** | 99.9% uptime (≤ 8.7 hours downtime/year) |
| **Read Latency** | p99 < 100ms for cached pastes, p99 < 300ms for uncached |
| **Write Latency** | p99 < 500ms for paste creation |
| **Durability** | No data loss for non-expired pastes (RPO = 0) |
| **Scalability** | Handle 10x traffic spikes without pre-scaling |
| **Consistency** | Eventual consistency acceptable for view counts; strong consistency for paste content |
| **Security** | No unauthorized access to private pastes, password-protected pastes |
| **Compliance** | DMCA takedown support, abuse reporting |

---

## Assumptions

- Average paste size: **10 KB** (realistic for code snippets)
- Maximum paste size: **10 MB** (enforced at API layer)
- Paste content is immutable after creation (except via explicit versioning feature)
- Syntax highlighting is rendered client-side (server sends raw + language hint)
- Expiration is best-effort (within minutes of expiry, not exactly at expiry second)
- View count does not need to be real-time exact (eventually consistent is fine)
- No live collaboration on a paste (not Google Docs)
- CDN is used for serving public paste content at the edge

---

## Constraints

- Must use object storage (S3-compatible) for paste content — not relational DB blobs
- Must support anonymous users for creation and reading of public pastes
- Must not store passwords in plaintext
- Expired pastes must be cleaned up from both metadata store and object storage
- Short key collisions must be handled gracefully without user impact

---

## Scale Estimation

### Traffic Estimates

| Metric | Estimate | Basis |
|--------|----------|-------|
| Monthly paste creations | 10 million | Pastebin publicly stated ~1M/day at peak; we model 10M/month |
| Daily paste creations | ~333,000 | 10M / 30 |
| Write RPS (average) | ~4 RPS | 333K / 86,400 |
| Write RPS (peak, 10x) | ~40 RPS | Spikes during CI/CD integrations, incidents |
| Read:Write ratio | 10:1 | Pastes are shared and read many times |
| Read RPS (average) | ~40 RPS | |
| Read RPS (peak) | ~400 RPS | |

### Storage Estimates

| Item | Estimate |
|------|----------|
| Average paste size | 10 KB |
| Monthly new storage | 10M × 10KB = **100 GB/month** |
| Annual new storage | **1.2 TB/year** |
| After 5 years (raw) | ~6 TB |
| Expiration factor (60% expire < 1 month) | ~2.4 TB effective at 5 years |
| Metadata size per paste (PostgreSQL row) | ~500 bytes |
| Metadata for 10M pastes | ~5 GB |
| Redis cache (hot 1% of pastes) | ~10 GB |

### Bandwidth Estimates

| Direction | Estimate |
|-----------|----------|
| Inbound (writes, 40 RPS × 10 KB) | ~400 KB/s = ~3.5 Gbps peak |
| Outbound (reads, 400 RPS × 10 KB) | ~4 MB/s = ~32 Mbps average; CDN absorbs most |

---

## Read / Write Patterns

### Write Pattern
- **Bursty** — developers paste during incidents, CI/CD pipelines, code reviews
- Write path: API → Validate → Generate Key → Upload to S3 → Save metadata to PostgreSQL → Invalidate/warm cache
- Small pastes (< 1 KB): can be stored inline in DB for performance
- Large pastes: always in S3

### Read Pattern
- **Read-heavy** — shared links are read many times after creation
- Hot pastes (trending, shared widely): served from CDN or Redis
- Cold pastes (old, rarely accessed): fetched from S3 via origin server
- Cache hit ratio target: **80%+** for public pastes

---

## Latency Expectations

| Operation | Target p50 | Target p99 |
|-----------|-----------|-----------|
| Create paste | 100ms | 500ms |
| Read paste (cache hit) | 5ms | 50ms |
| Read paste (cache miss, DB hit) | 30ms | 150ms |
| Read paste (cache miss, S3 hit) | 80ms | 300ms |
| Delete paste | 100ms | 500ms |
| List user pastes | 50ms | 200ms |

---

## Availability Targets

| Scenario | Target |
|----------|--------|
| Read availability | 99.95% |
| Write availability | 99.9% |
| Cleanup lag tolerance | ≤ 5 minutes after expiry |
| RTO (Recovery Time Objective) | < 30 minutes |
| RPO (Recovery Point Objective) | < 1 minute (with WAL replication) |

---

## Back-of-Envelope Summary

```
Writes:         ~4 RPS avg, ~40 RPS peak
Reads:          ~40 RPS avg, ~400 RPS peak
Storage growth: ~100 GB/month content + 5 GB/month metadata
Cache needed:   ~10 GB Redis for hot pastes
DB size:        ~5 GB metadata (PostgreSQL), manageable on single node
Object storage: S3-class, growing ~1.2 TB/year
Bandwidth:      CDN handles ~90% of read traffic
```

**Key Insight:** This is an extremely read-heavy, storage-bound system. The write path is trivially simple. The engineering challenges are in:
1. Efficient short key generation (avoiding collisions at scale)
2. Expiration cleanup at scale (millions of entries)
3. CDN cache invalidation on delete/expire
4. Abuse prevention without content moderation at scale
5. Object storage cost optimization (compression, tiering)

---

## Interview Discussion Points

- Why use object storage instead of storing content in PostgreSQL TEXT columns?
- At what paste volume does the inline-in-DB approach break down?
- How do you handle the "thundering herd" when a paste goes viral?
- What is your strategy for cleaning up 6 million expired pastes per month efficiently?
- How do you prevent abuse (spam, malware distribution) at anonymous paste creation?
