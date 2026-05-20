# 00 — Requirements Analysis: File Storage System (Google Drive / Dropbox)

## Objective
Define the full scope of a production-grade file storage platform — functional capabilities, non-functional expectations, scale assumptions, and capacity estimates — so every downstream architectural decision is grounded in real constraints.

---

## Functional Requirements

### Core (Must Have)
| # | Requirement |
|---|-------------|
| 1 | Users can upload files (any type, up to 5 GB per file) |
| 2 | Users can download files by direct link or from their dashboard |
| 3 | Files are organized into folders with nested hierarchy |
| 4 | Users can share files/folders with other users (view / edit / comment permissions) |
| 5 | Files are versioned — users can view and restore previous versions |
| 6 | Files can be deleted (soft delete → trash → permanent purge) |
| 7 | File/folder search by name, type, and last modified date |
| 8 | File preview for common types (PDF, images, video, docs) |

### Extended (Should Have)
| # | Requirement |
|---|-------------|
| 9 | Desktop/mobile sync client — detect local changes and sync to cloud |
| 10 | Conflict resolution when same file edited from two devices |
| 11 | Block-level deduplication to reduce storage |
| 12 | Chunked / resumable uploads for large files |
| 13 | Collaborative editing integration (link to Docs-style editors) |
| 14 | Storage quota per user / team |

### Out of Scope (for MVP)
- Full real-time collaborative editing (Google Docs-level OT/CRDT)
- AI-based file classification
- Enterprise DLP (Data Loss Prevention)

---

## Non-Functional Requirements

| Attribute | Target |
|-----------|--------|
| Availability | 99.99% (< 52 min downtime/year) |
| Durability | 99.999999999% (11 nines) — S3-class durability |
| Upload latency | < 500 ms to acknowledge receipt; parallel chunk upload |
| Download latency | < 200 ms TTFB from CDN edge for cached files |
| Search latency | < 500 ms for metadata search |
| Consistency | Strong consistency for metadata, eventual for CDN propagation |
| File size limit | 5 GB per file (chunked into 5–10 MB blocks) |
| Sync client lag | < 5 seconds for file change to appear on another device |

---

## Assumptions
- Authentication is handled externally (OAuth2/OIDC provider).
- Object storage (S3-compatible) is available — we do not build raw disk management.
- Users are globally distributed; CDN is mandatory for downloads.
- Sync clients (desktop/mobile) are separate apps consuming the same API.
- Block deduplication is content-hash-based (SHA-256 per chunk).
- Files are not end-to-end encrypted by default; server-side encryption at rest.

---

## Constraints
- Single file max = 5 GB. Chunks = 5–10 MB each → max ~1000 chunks per file.
- Quota enforcement: free tier 15 GB, paid tier up to 2 TB.
- GDPR compliance: user data deletion must propagate within 30 days.
- File access control must be enforced at every download/stream, not just link generation.

---

## Scale Estimation

### User Base
| Metric | Value |
|--------|-------|
| Total registered users | 500 million |
| Daily Active Users (DAU) | 50 million |
| Concurrent active sessions | 5 million |

### Storage Estimates
| Metric | Calculation | Result |
|--------|-------------|--------|
| Avg file size | — | 5 MB |
| Files uploaded/day | 50M DAU × 2 uploads | 100 million files/day |
| New data/day | 100M × 5 MB | 500 TB/day |
| Annual new data | 500 TB × 365 | ~180 PB/year |
| With replication (3×) | 180 PB × 3 | ~540 PB/year |
| Deduplication savings (30%) | — | Effective ~378 PB/year |

### Traffic Estimates
| Operation | Calculation | RPS |
|-----------|-------------|-----|
| Upload requests | 100M files / 86,400s | ~1,200 RPS |
| Download requests | 10× uploads (read-heavy) | ~12,000 RPS |
| Metadata reads (browse) | 50M DAU × 20 ops / 86,400s | ~11,600 RPS |
| Search queries | 50M DAU × 2 searches / 86,400s | ~1,200 RPS |

### Bandwidth Estimates
| Direction | Calculation | Bandwidth |
|-----------|-------------|-----------|
| Upload ingress | 1,200 RPS × 5 MB | ~6 GB/s |
| Download egress | 12,000 RPS × 5 MB | ~60 GB/s |
| CDN offload (70%) | — | Origin sees ~18 GB/s egress |

---

## Read / Write Patterns

| Pattern | Observation |
|---------|-------------|
| Read-heavy | Downloads >> Uploads (~10:1) |
| Bursty uploads | Morning and evening peaks (2–3× baseline) |
| Metadata reads | Very frequent (file browser, sync checks) |
| Chunk writes | Parallel, idempotent — safe to retry |
| Version reads | Rare — only when user explicitly browses history |

---

## Latency Expectations

| Operation | P50 | P99 |
|-----------|-----|-----|
| File metadata read | 20 ms | 100 ms |
| Small file download (<1 MB) | 50 ms TTFB | 200 ms |
| Large file first chunk | 100 ms | 500 ms |
| Upload acknowledgment | 200 ms | 1 s |
| Search results | 150 ms | 500 ms |

---

## Availability Targets

| Component | Target |
|-----------|--------|
| Upload API | 99.99% |
| Download / CDN | 99.99% |
| Metadata API | 99.99% |
| Sync service | 99.9% |
| Search | 99.9% |

---

## Interview-Level Discussion Points

- **Why 11-nines durability?** — User data loss is catastrophic to trust. Achieved via cross-region replication + erasure coding in object storage.
- **How do you handle 500 TB/day ingress?** — Chunked uploads distributed across many storage nodes, backed by object storage auto-scaling.
- **Deduplication math**: If 30% of chunks are duplicates, 30% storage savings = ~$XX million/year at scale. Hash-on-client before upload.
- **Strong vs eventual consistency**: Metadata must be strongly consistent (you can't show a stale folder). CDN cache for downloads can be eventually consistent.
- **Quota enforcement**: Must be enforced at upload time, not post-hoc. Requires atomic quota check-and-decrement.
