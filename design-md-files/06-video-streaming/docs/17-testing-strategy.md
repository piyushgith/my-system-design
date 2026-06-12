# 17 — Testing Strategy: Video Streaming Platform

---

## Objective

Verify the guarantees that define the platform: a correct upload→transcode→publish pipeline, **exactly-once view counting**, CDN/origin-shield protection, DRM enforcement, and a playback path that never breaks when peripheral services fail. Tests validate behavior and the failure scenarios in `11-failure-scenarios.md`.

---

## Test Pyramid

| Layer | Share | Scope | Tooling |
|---|---|---|---|
| Unit | ~60% | Manifest assembly, rendition-ready state machine, HLL counting, view-dedup window, signed-URL/DRM token logic | JUnit 5 |
| Integration | ~25% | S3 (MinIO) + Kafka + PostgreSQL + Redis via Testcontainers; transcode orchestration | Testcontainers |
| Contract | ~5% | Upload API, manifest format, view-event schema | Pact, schema registry |
| E2E + Load | ~10% | Upload→publish, playback ABR, view-count accuracy; CDN load | k6, ffmpeg fixtures |

---

## What Each Layer Must Prove

### Unit
- Rendition state machine: video becomes "live" only when the minimum rendition set is ready.
- Exactly-once view counting: a replayed `ViewEvent` does not double-count (idempotency window asserted).
- HyperLogLog unique-viewer estimate within tolerance for known input.
- Signed-URL / DRM token: expiry, scope, and tamper rejection.

### Integration
- Upload-to-publish: `VideoUploaded` → orchestrator emits N `TranscodeRequested` → workers write segments → `VideoPublished` only after min renditions complete.
- Partial transcode failure: one rendition fails → DLQ + retry; video still publishes at available renditions if policy allows.
- Manifest build reflects exactly the renditions present in S3 (no dangling references).
- View pipeline: events → Kafka → analytics store; count converges.

### Contract
- Upload API, master-manifest format, and `ViewEvent` schema locked; consumer compatibility enforced.

---

## Load & Performance Testing (validates the SLOs)

Design claims: **video start p99 < 2s (TTFF)**, **transcode visible < 15 min**, **API p99 < 200ms**, **search p99 < 500ms**, **CDN hit > 95% for top 5%**, **25 Tbps peak egress**.

| Scenario | Target | Pass criteria |
|---|---|---|
| Playback start (cache hit) | high | TTFF p99 < 2s |
| ABR downgrade under bandwidth drop | — | Player switches rendition without stall |
| Viral video herd | spike on one video | Origin shield collapses misses; origin RPS bounded |
| Transcode pipeline | 500 hrs/min upload | Standard video published < 15 min |
| Metadata API | — | p99 < 200ms |

The origin-shield request-collapsing behavior (see `09-caching-strategy.md` Layer 3) must be explicitly load-tested with synchronized cold misses across many edge regions.

---

## Chaos / Failure-Injection (validates 11-failure-scenarios.md)

- **Recommendation/search down** → playback unaffected; serve trending/cached (graceful degradation).
- **Transcode worker crash mid-job** → job redelivered; idempotent; no duplicate segments.
- **Origin shield outage** → edges fail open to origin; degraded protection, not outage.
- **DMCA takedown** → all CDN entries purged; subsequent requests blocked.
- **View-event Kafka replay** → counts stay exact (idempotency).

---

## CI/CD Gates

| Stage | Gate |
|---|---|
| PR | Unit + integration pass; changed-line coverage ≥ 80% |
| Merge | Contract + schema-registry compatibility |
| Pre-prod | Upload→publish E2E with real ffmpeg; playback ABR test |
| Post-deploy | Synthetic playback canary; TTFF, CDN-hit, and transcode-lag alerts |

---

## Interview Discussion Points

- **How do you test exactly-once view counting on an at-least-once bus?** Replay the same event; assert the dedup window collapses it.
- **How do you test the origin shield?** Fire synchronized cold misses from many simulated edges; assert one origin fetch.
- **How do you keep playback green when recommendations fail?** Fault-inject the rec service; assert the playback path has no hard dependency on it.
