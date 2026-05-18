# 00 — Requirements Analysis

## Objective
Define the functional scope, non-functional targets, scale assumptions, and capacity estimates for a production-grade Collaborative Document Editor (Google Docs-scale). This file sets the contract that every downstream architecture decision must satisfy.

---

## Functional Requirements

### Core Document Operations
- Create, read, update, delete documents
- Rich-text editing: bold, italic, headings, lists, tables, images, hyperlinks, code blocks
- Real-time collaborative editing by multiple simultaneous users on the same document
- Conflict-free concurrent edits — no data loss when two users edit the same region

### Collaboration
- Live cursor presence: see where other users are currently editing
- User name/avatar labels on cursors with color coding
- Selection highlighting for each collaborator
- Real-time propagation of edits (target < 300 ms end-to-end)

### Version History & Undo/Redo
- Full version history with named snapshots ("Version 3 — after legal review")
- Per-user undo/redo stack that does not conflict with others' edits
- Restore to any historical version
- Diff view between any two versions

### Access Control & Sharing
- Permission levels: Owner, Editor, Commenter, Viewer
- Share via link (with optional password and expiry)
- Workspace/organization-level sharing policies
- Guest (unauthenticated) view-only links

### Offline Support
- Edit documents while offline; changes queue locally
- Automatic sync when connectivity is restored with conflict resolution

### Comments & Suggestions
- Anchor comments to specific text ranges
- Threaded comment replies
- Suggestion mode: propose tracked changes; accept/reject workflow

### Export / Import
- Export to DOCX, PDF, Markdown, HTML
- Import from DOCX, Markdown

---

## Non-Functional Requirements

| Property | Target |
|---|---|
| Availability | 99.99% (≤ 52 min/year downtime) |
| Real-time latency | < 300 ms for collaborative op propagation (p99) |
| Read latency | < 100 ms for document open (p95) |
| Durability | Zero document data loss; at-least-once op delivery |
| Consistency | Eventual consistency for collaboration; strong for document saves |
| Scalability | 100 M DAU, 1 B documents, 10 K ops/sec per hot document |
| Throughput | 10 B total ops/day globally |
| Storage | Document content + version history for 10 years |
| Offline | Offline edit support with eventual sync |
| Security | End-to-end ACL enforcement; encryption at rest and in transit |

---

## Assumptions
1. Average document size: 50 KB raw content; 500 KB with embedded images stored separately in object storage.
2. 10% of documents are actively edited at any given moment.
3. Average active session: 20 minutes; average idle open tab: 2 hours.
4. 10 concurrent editors per active document on average; hot documents may have up to 1,000 simultaneous editors (live meeting notes, conference doc).
5. Version snapshots are taken every 5 minutes during active editing, plus on explicit save.
6. 70% of traffic is reads (document open, version history); 30% is writes (edit operations).
7. Geographic distribution: 40% NA, 30% EU, 20% APAC, 10% rest.
8. Mobile clients represent 30% of sessions.

---

## Scale Estimation (Back-of-Envelope)

### Users & Documents
- 100 M DAU, 1 B total documents
- Peak concurrent users: ~10 M (10% of DAU during peak hours)
- Active documents at peak: ~2 M (20% of concurrent users each editing a different doc)

### Operations Throughput
- 10 active editors per doc × 2 M active docs = 20 M concurrent editing users
- Average operation rate per user: 1 op/2 seconds = 0.5 ops/sec/user
- Total peak ops: 20 M × 0.5 = **10 M ops/sec globally**
- Hot doc at 10 K ops/sec: single document receiving 10 K edits/second (live event)

### Operation Message Size
- Average OT/CRDT delta: ~200 bytes (position, text, metadata)
- 10 M ops/sec × 200 bytes = **2 GB/sec inbound operation bandwidth**
- After fan-out to all editors on same doc: 10× = ~20 GB/sec egress at peak

### Storage
- 1 B documents × 50 KB avg = **50 TB** active document content
- Version history: 20 versions/doc avg × 50 KB = 1 PB (stored as deltas ~5 KB each = 100 TB)
- Images/attachments: 1 B docs × avg 2 images × 200 KB = 400 TB (object storage)
- Kafka ops log retention (7 days): 10 M ops/sec × 200 bytes × 86,400 sec = **172 TB/day** → 1.2 PB for 7-day retention (requires aggressive compression, ~5× → ~240 TB/day)

### WebSocket Connections
- 20 M concurrent editing sessions + 5 M idle open tabs = **25 M WebSocket connections**
- WebSocket server: 50 K connections per server node → **500 server nodes** at peak

### Collaboration Service Throughput
- Each op fan-out: average 10 recipients → 10 M × 10 = **100 M messages/sec fan-out**
- Redis Pub/Sub per document channel aggregates fan-out per shard

---

## Read/Write Patterns

| Pattern | Frequency | Notes |
|---|---|---|
| Document open (full snapshot read) | Very high | Must be fast; served from cache |
| Edit operation stream | Very high | Core write path; must be low-latency |
| Version history browse | Medium | Can tolerate slightly higher latency |
| Comment read | Medium | Read-heavy after document publish |
| Permission check | Very high | On every operation; must be cached |
| Snapshot write (autosave checkpoint) | Medium | Batched, async |
| Export | Low | CPU-intensive, async job |

---

## Latency Expectations

| Operation | p50 | p95 | p99 |
|---|---|---|---|
| Document open | 80 ms | 150 ms | 300 ms |
| Op propagation (WS round trip) | 50 ms | 200 ms | 500 ms |
| Version restore | 200 ms | 800 ms | 2 s |
| Export (PDF) | 2 s | 10 s | 30 s (async) |
| Comment thread load | 60 ms | 150 ms | 400 ms |

---

## Availability Targets

| Tier | Target | Strategy |
|---|---|---|
| Document read | 99.99% | Multi-region read replicas, CDN for static assets |
| Real-time editing | 99.95% | Graceful degradation to async mode if WebSocket layer fails |
| Export | 99.9% | Async job queue with retry |
| Version history | 99.99% | Immutable event log in durable store |

---

## Constraints
- Operations must be idempotent: network retries cannot corrupt document state.
- No single point of failure at any layer (ops servers, storage, messaging).
- GDPR compliance: user data deletion propagates to document history.
- Data residency: EU documents must remain in EU region.
- Total cost of infrastructure must be optimized — naive fan-out at 10 M ops/sec is prohibitively expensive.

---

## Interview Discussion Points
- Why does 10 K ops/sec per hot document create a fan-out problem and not just a throughput problem?
- How do you handle the "thundering herd" when 1,000 users open the same document simultaneously?
- What is the difference between operational throughput (ops/sec) and storage throughput (MB/sec), and which is the bottleneck here?
- How do capacity estimates change if you support document branching (like Git branches for documents)?
- At what point does eventual consistency become unacceptable for collaborative editing, and what do you do about it?
