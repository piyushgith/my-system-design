# 17 — Testing Strategy: File Storage System

---

## Objective

Verify the guarantees that matter for a storage system: **durable, resumable uploads**, **correct chunk deduplication under concurrency**, presigned-URL safety, sync correctness, and quota integrity. Tests validate behavior and the failure scenarios in `11-failure-scenarios.md`.

---

## Test Pyramid

| Layer | Share | Scope | Tooling |
|---|---|---|---|
| Unit | ~65% | Chunk manifest assembly, SHA-256 content addressing, dedup key logic, quota math | JUnit 5 |
| Integration | ~20% | Metadata (PostgreSQL) + S3 (MinIO) + Redis + Kafka via Testcontainers | Testcontainers, Spring Boot Test |
| Contract | ~5% | Upload init/complete API, sync change-feed cursor contract | Pact |
| E2E + Load | ~10% | Resumable upload, download via CDN, sync delta; throughput | k6, Gatling |

---

## What Each Layer Must Prove

### Unit
- Chunk manifest: out-of-order chunk arrival reassembles correctly by index/ETag.
- Content addressing: identical chunk content → identical SHA-256 → single stored blob.
- Quota math: reservation, commit, and release arithmetic never goes negative or double-counts.
- Presigned URL: TTL boundary, scope (single object), method restriction.

### Integration
- Resumable upload: simulate client disconnect at 60% → resume re-uploads only missing chunks (manifest in Redis).
- **Dedup race:** two clients upload the same chunk concurrently → conditional write ensures one stored object, both metadata records point to it (no corruption, no lost write).
- Multipart complete: assembles object in S3, creates metadata atomically; failure leaves no orphan metadata.
- Sync change feed: client with cursor N receives exactly the deltas after N, in order.

### Contract
- `upload/init`, `upload/complete`, and `sync/changes?since=` schemas locked.

---

## Load & Performance Testing (validates the SLOs)

Design claims: **upload ack < 500ms**, **download TTFB < 200ms (CDN)**, **search < 500ms**, **sync lag < 5s**, **~1,200 upload RPS / ~11,600 browse RPS**, **6 GB/s ingress**.

| Scenario | Target | Pass criteria |
|---|---|---|
| Upload init ack | 1,200 RPS | p99 < 500ms (presigned URL issuance, not byte transfer) |
| Download via CDN | high | TTFB p99 < 200ms on cache hit |
| Metadata browse | 11,600 RPS | p99 within target; read replicas + Redis absorb load |
| Sync delta latency | — | change visible on second device < 5s |

Upload-throughput tests validate the **control path** (session + manifest), since bytes go client→S3 directly via presigned URLs — the app never proxies 6 GB/s.

---

## Chaos / Failure-Injection (validates 11-failure-scenarios.md)

- **Presigned URL leak/abuse** → short TTL + bucket policy reject out-of-scope writes.
- **Kafka (search consumer) lag** → search results stale but bounded; SLO alert fires; no data loss.
- **Metadata DB pressure at browse peak** → read replicas + cache hold; primary protected.
- **Dedup conditional-write contention** → high-concurrency same-chunk test proves no corruption.
- **Partial multipart failure** → no orphan metadata; client can retry cleanly.

---

## CI/CD Gates

| Stage | Gate |
|---|---|
| PR | Unit + integration (MinIO) pass; changed-line coverage ≥ 80% |
| Merge | Contract suite; schema compatibility |
| Pre-prod | Resumable-upload + dedup-race + sync E2E |
| Post-deploy | Synthetic upload/download canary; consumer-lag and quota-drift alerts |

---

## Interview Discussion Points

- **How do you test the dedup race?** Concurrent uploads of identical content with a conditional (if-not-exists) write; assert one blob, two references.
- **How do you test resumable upload deterministically?** Drop the connection at a known chunk boundary; assert only missing chunks re-upload.
- **Why test the control path, not byte throughput, for uploads?** Bytes bypass the app via presigned URLs; the testable bottleneck is session/manifest handling.
