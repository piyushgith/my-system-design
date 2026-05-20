# 07 — Scaling Strategy: KYC / Identity Verification Pipeline

---

## Objective

Define horizontal and vertical scaling for the KYC pipeline components, address vendor API rate limits, and design burst capacity for campaign-day spikes (10K/day normal → 50K/day peak).

---

## Scaling Dimensions

| Component | Bottleneck | Strategy |
|---|---|---|
| KYC API (submission) | CPU/memory per pod | Horizontal scaling via HPA |
| Pipeline Orchestrator | DB poll frequency, vendor concurrency | Worker pool sizing, backpressure |
| Vendor API calls | Vendor rate limits, cost | Rate limiting at VendorRouter, queue buffering |
| PostgreSQL | Write-heavy on state transitions | Vertical scaling + read replicas |
| Manual Review | Human throughput (not a system problem) | SLA management, priority queue |
| S3 document storage | Throughput, cost | S3 is effectively unbounded; lifecycle policies manage cost |

---

## Application Layer Scaling

### KYC API Pods

- Stateless (no session state in pods) — scales horizontally
- HPA on CPU utilization: target 60%
- Min replicas: 3 (one per AZ) | Max replicas: 20
- Submission is fast (< 100ms) — primarily DB insert + Kafka produce

### Pipeline Worker Pods

- Each worker pod polls for pending steps or consumes Kafka `kyc.application.submitted` events
- Worker pool per step type: separate workers for OCR, Liveness, Watchlist
  - Why separate? OCR calls are slower (2–3s) and vendor-rate-limited; Watchlist is faster (200ms)
  - Separate pools prevent slow OCR from blocking fast Watchlist workers
- Thread pool per worker pod: 20 threads × N pods = parallelism
- At 50,000 applications/day (peak) = ~0.58 applications/second = 1 application per 1.7 seconds
  - OCR takes 2s, 20 parallel threads → handles 10 applications/second per pod
  - **1 worker pod is sufficient** at this scale — scale to 3 pods for HA

---

## Vendor API Rate Limit Management

Vendor APIs impose rate limits. The VendorRouter enforces these.

| Vendor | Rate Limit | Cost Per Call | Strategy |
|---|---|---|---|
| DigiLocker | 100 RPS (tiered by plan) | Free (government API) | Queue + leaky bucket |
| Onfido | 50 RPS per account | £0.50–£2 per check | Rate limit + circuit breaker |
| Jumio | 25 RPS | $1–$5 per check | Fallback only (expensive) |
| LexisNexis | 200 RPS | $0.10–$0.50 per screen | Primary for watchlist |

**Rate Limiter implementation:**
- Redis `INCR` + expiry sliding window per vendor per second
- If window full: enqueue application in a per-vendor wait queue (in-memory ring buffer)
- Backpressure: if wait queue exceeds 1,000 items, alert on vendor capacity

**Cost control:**
- At 10,000 applications/day × Onfido OCR $1/check = $10,000/day in vendor costs
- Cache OCR results by document fingerprint (S3 ETag hash) — if same document re-submitted (re-KYC), use cached result (TTL 30 days)
- Use DigiLocker (free) as primary for Aadhaar (Indian market); Onfido only for non-Aadhaar documents

---

## Database Scaling

### Write Pattern

State transitions are the most frequent write:
- 50,000 applications/day × 8 transitions avg = 400,000 transitions/day
- Peak: 400,000 / 8 hours = 13.9 transitions/second — very manageable for PostgreSQL

### Read Pattern

Manual reviewers query the review queue:
- 1,000 manual reviews/day × 10 status checks = 10,000 reads/day — trivial
- Status polling by Onboarding service: 50,000 applications × 10 polls = 500,000 reads/day = ~6 RPS — trivial

### Scaling Plan

| Phase | Load | Action |
|---|---|---|
| MVP | 10K/day | Single PostgreSQL instance |
| V1 | 50K/day | Primary + 1 read replica; status queries on replica |
| V2 | 500K/day | 2 read replicas; partition state_transitions by month |
| V3 | 5M/day | Separate read DB for reporting; archive old partitions |

---

## Burst Capacity (Campaign Day: 50K applications in 4 hours)

Campaign events (cashback, new product launch) can spike submissions 10x.

**Problem:** 50K / 4 hours = 208 applications/minute = 3.5/second. Vendor OCR at 2s each: needs 7 concurrent OCR workers.

**Solution: Pre-warm + queue buffering**

1. **Pre-warm:** On campaign event detected (marketing calendar), pre-scale worker pods from 3 → 15 (30 minutes before campaign)
2. **Queue buffer:** Kafka acts as the burst buffer — submissions arrive faster than pipeline processes; Kafka holds the backlog
3. **Vendor capacity:** Pre-negotiate higher rate limits with vendors for known campaign dates
4. **Graceful degradation:** During extreme burst, deprioritize WATCHLIST step (schedule for off-peak) — complete OCR and Liveness in real-time, watchlist overnight

---

## Manual Review Queue Scaling

Human throughput cannot be auto-scaled. Design for SLA management:

| Queue Depth | Action |
|---|---|
| 0–100 | Normal — assigned reviewer handles |
| 100–500 | Alert compliance manager — additional reviewers assigned |
| 500+ | P1 alert — pause new MANUAL_REVIEW routing, escalate to head of compliance |
| 1000+ | Consider automated re-screening with updated rules to clear false positives |

**Measurement:** `SELECT COUNT(*) FROM manual_review_queue WHERE completed_at IS NULL` — Prometheus gauge, alert on > 200.

---

## CDN for Document Upload

Document uploads (1–3 MB) from mobile clients go to S3 via presigned URLs. For global users:
- S3 Transfer Acceleration: uses CloudFront edge locations for faster upload — 50–300% faster for cross-region uploads
- Presigned URL region: match to user's region (Mumbai for India users, Frankfurt for EU users)
- Cost: S3 Transfer Acceleration pricing vs standard upload — justified for international users

---

## Interview Discussion Points

- **What is the bottleneck at 1M applications/day (10x growth)?** Vendor API rate limits. At 1M/day = 12 OCR calls/second. Onfido at 50 RPS is fine; DigiLocker at 100 RPS is tight. Solution: negotiate enterprise API plans, add vendor redundancy, implement document result caching
- **How do you handle a sudden 100x spike (viral product launch)?** Submissions accepted immediately (API is fast — just DB insert). Pipeline queue absorbs the spike (Kafka). Workers drain the queue at their natural pace. Users wait longer for KYC approval. Set expectation: "Verification may take up to 30 minutes due to high demand." This is the Kafka as surge buffer pattern
- **Why separate worker pools per step type?** At high load, OCR (slow, expensive) and Watchlist (fast, cheap) compete for the same thread pool. A slow OCR call holds a thread that could be running 5 Watchlist checks. Separate pools prevent step-type coupling and allow independent scaling
- **How do you make the system cost-efficient?** Implement a "cheapest viable vendor first" routing strategy: DigiLocker (free) for Aadhaar → only upgrade to Onfido if DigiLocker fails or document is non-Aadhaar. Cache OCR results by document hash — re-KYC with same document doesn't incur vendor cost again. Track cost-per-application metric in Grafana
