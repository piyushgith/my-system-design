# 00 — Requirements Analysis: URL Shortener (TinyURL / Bitly)

---

## Objective

Define the functional and non-functional requirements, constraints, assumptions, and capacity estimates for a production-grade URL shortening service capable of handling candidate's traffic.

---

## Functional Requirements

### Core (MVP)
- A user provides a long URL; the system returns a short URL (e.g., `https://short.ly/aB3xYz`)
- Visiting the short URL redirects the user to the original long URL
- Short URLs are unique and collision-free
- Short URLs must be URL-safe (alphanumeric + no ambiguous chars)

### Extended (V1)
- Users can optionally provide a **custom alias** (e.g., `short.ly/my-product-launch`)
- Short URLs have configurable **TTL / expiration** (default: never)
- Users can **delete** their own short URLs
- Basic **click analytics**: total clicks, referrer, device type, country

### Advanced (V2+)
- **Per-URL analytics dashboard** with time-series click data
- **Geo-routing**: redirect to different destination based on requester's country
- **A/B testing** support (split traffic between two target URLs)
- **Rate limiting** per user/IP
- **Bulk URL shortening** via API
- **QR code generation** for short URLs
- **Password-protected** short URLs
- **Domain white-labeling** (organizations use their own domain)

---

## Non-Functional Requirements

| Property | Target |
|---|---|
| Availability | 99.99% (< 52 min downtime/year) |
| Redirect latency (p99) | < 50ms globally |
| Short URL generation latency | < 200ms |
| Consistency | Eventual for analytics; strong for URL uniqueness |
| Durability | No short URL must be lost once created |
| Read:Write ratio | ~100:1 (extremely read-heavy) |
| Data retention | Configurable per URL; default permanent |
| Security | URLs must not be guessable/enumerable |

---

## Assumptions

- Users are global (multi-region required for low-latency redirects)
- The system is public-facing (anonymous + authenticated users)
- Analytics pipeline can tolerate up to 30 seconds of lag
- Custom aliases may conflict; first-write-wins
- The short domain itself is pre-configured (not dynamically provisioned)
- Malicious URL filtering (phishing/malware) is a separate concern — out of scope for V1 but flagged as future requirement
- A registered user can own and manage their URLs; anonymous users get unmanaged short URLs

---

## Constraints

- Short code length: 6–8 characters (balances URL aesthetics with key space size)
- The system must be **stateless at the redirect layer** for horizontal scalability
- No third-party URL shortening libraries can be used in the core path
- Must support **HTTPS only** for all short URLs

---

## Scale Estimation

### Traffic Assumptions

| Metric | Value |
|---|---|
| Daily active users (DAU) | 50 million |
| URL creation per day | 5 million |
| URL redirects per day | 500 million |
| Peak redirect RPS | ~10,000 RPS |
| Peak creation RPS | ~300 RPS |
| Read:Write ratio | 100:1 |

### Back-of-the-Envelope Calculations

**Short Code Key Space**

- Character set: `[a-zA-Z0-9]` = 62 characters
- 6-character code: 62^6 = ~56 billion unique codes
- 7-character code: 62^7 = ~3.5 trillion unique codes
- At 5M URLs/day → 7-char needed only after ~1.9 million days; 6-char is sufficient for decades

**Storage Estimation**

| Field | Size |
|---|---|
| Short code (6 chars) | 6 bytes |
| Long URL (avg) | 200 bytes |
| Metadata (created_at, user_id, TTL, etc.) | ~100 bytes |
| Total per record | ~306 bytes |

- 5M new URLs/day × 306 bytes ≈ **1.5 GB/day**
- 5 years: ~2.7 TB raw URL data
- Analytics events (click data): ~200 bytes/click × 500M/day ≈ **100 GB/day** — must use a separate analytics store (columnar/time-series), NOT the primary DB

**Bandwidth**

- Redirect response: ~HTTP 301/302 headers ≈ 500 bytes
- 10,000 RPS × 500 bytes = **5 MB/s outbound** at peak — trivial for CDN egress

**Cache Estimation**

- Top 20% URLs account for 80% of traffic (Pareto principle)
- 500M redirects/day → 100M unique URLs accessed
- Hot 20% = 20M URL records × 306 bytes ≈ **6 GB** — fits comfortably in Redis

---

## Read/Write Patterns

| Operation | Pattern | Frequency |
|---|---|---|
| Redirect (read short → long) | Point lookup by short code | Very High (100x) |
| Create short URL | Insert record | Moderate |
| Analytics write | Append-only event stream | Very High (async) |
| Analytics read | Aggregated time-series queries | Low |
| Custom alias check | Uniqueness check | Low |
| URL expiration cleanup | Background batch scan | Scheduled |

**Key Insight**: The redirect path is the **critical hot path**. It must be:
1. Cache-first (Redis/CDN)
2. Zero DB writes on redirect
3. Analytics decoupled via async queue

---

## Latency Expectations

| Operation | Target P50 | Target P99 |
|---|---|---|
| Redirect | < 10ms | < 50ms |
| URL creation | < 100ms | < 200ms |
| Analytics dashboard load | < 500ms | < 2s |
| Custom alias check | < 50ms | < 100ms |

---

## Availability Targets

| Component | Availability |
|---|---|
| Redirect service | 99.99% |
| URL creation service | 99.9% |
| Analytics pipeline | 99.5% (eventual) |
| Admin/management API | 99.5% |

**Why different targets?** Redirect is revenue-critical (dead links = user-visible failures). Creation and analytics can tolerate brief outages without catastrophic impact.

---

## Tradeoffs Acknowledged at Requirements Level

| Decision | Tradeoff |
|---|---|
| 6-char code vs longer | Shorter = aesthetically cleaner, less key space; longer = future-proof, uglier |
| HTTP 301 vs 302 redirect | 301 = browser-cached (faster UX, loses analytics); 302 = not cached (analytics accurate, extra RTT) |
| Eventual analytics | Lose strict click-count accuracy for scalability |
| Anonymous URL creation | Easier adoption, harder abuse prevention |
| Permanent URLs by default | User-friendly, increases storage footprint |

---

## Interview Discussion Points

- **Why 6 characters?** Show key space math, contrast with Twitter's 7-char, justify with expected URL volume
- **Why 302 over 301?** Analytics accuracy vs. performance; in production you can use 301 with cache-busting headers
- **How do you prevent enumeration attacks?** Random code generation (not sequential IDs), key space is too large to brute-force
- **What happens when key space is exhausted?** Expand to 7 chars, reclaim expired URLs, implement TTL enforcement
- **What's the SLA for redirect?** Drives the cache strategy and geo-distribution decision
