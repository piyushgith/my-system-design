# 11 — Failure Scenarios: Pastebin / Code Sharing Platform

---

## Objective

Identify the most likely and most impactful failure scenarios in the Pastebin system. For each failure, define: what breaks, the blast radius, the detection signal, and the recovery strategy.

---

## Failure Categories

1. **Infrastructure failures** — database down, S3 unavailable, Redis failure
2. **Cascading failures** — one component failing under load causes others to fail
3. **Data consistency failures** — partial writes, race conditions, split-brain
4. **Abuse-related failures** — DDoS, spam bursts, content abuse surge
5. **Operational failures** — bad deploy, configuration error, secret rotation failure

---

## Scenario 1: PostgreSQL Primary Down

**What breaks:** All writes fail. Reads from cache/replica still work.

**Detection:** PostgreSQL health check fails; Hikari connection pool errors spike; write endpoint returns 500.

**Blast radius:**
- Paste creation: fails (503 returned to user)
- Paste read (cache hit): unaffected
- Paste read (cache miss, data on replica): degraded (reads from replica possible)
- Expiry cleanup: fails (cleanup job cannot query DB)
- View count updates: fail (acceptable — queued in analytics)

**Recovery strategy:**

```
Automatic:
  1. HAProxy/PostgreSQL HA (Patroni) detects primary failure
  2. Promotes read replica to primary (failover time: ~30 seconds with Patroni)
  3. Application reconnects via updated DNS (HikariCP reconnects on next pool checkout)

Manual validation:
  1. Verify replication lag was < 1 minute before failure
  2. Confirm no data loss (compare WAL positions)
  3. Check that promoted replica has all recent writes

Recovery time: 30 seconds (automated) to 5 minutes (with manual verification)
Data loss risk: < 1 minute of writes (RPO) if async replication; 0 if synchronous
```

**Prevention:**
- Patroni for automatic failover
- Synchronous replication for zero data loss (cost: slightly higher write latency)
- Regular failover drills (test automatic promotion quarterly)

---

## Scenario 2: Redis Cluster Down

**What breaks:** Cache unavailable. All reads fall through to PostgreSQL.

**Detection:** Redis health check fails; Redis command error rate spikes; DB CPU/connections spike.

**Blast radius:**
- Paste reads: 100% cache miss → all hits PostgreSQL → 10x DB load
- Rate limiting: bypassed (no Redis for counters) → abuse risk window
- Idempotency keys: lost → duplicate paste creation possible within 24 hours
- API key authentication: falls back to DB lookup (slower but correct)

**Recovery strategy:**

```
Immediate (circuit breaker opens):
  1. Detect Redis failure (3 consecutive timeouts)
  2. Open circuit breaker: fall through to DB immediately (no retry waits)
  3. Serve reads from DB + optional S3 fallback
  4. Rate limiting: fall back to in-process token bucket (less precise, less consistent)

Warm-up on Redis recovery:
  1. Redis cluster recovers (replica promotion or restart)
  2. Application gradually re-routes reads through Redis (Hikari reconnect)
  3. Cache is cold — potential stampede
  4. Apply mutex pattern: first reader per key populates cache; others wait
```

**Prevention:**
- Redis Cluster (3 shards, 1 primary + 1 replica each): one shard failure doesn't take down all reads
- Connection timeout: 200ms (fail fast)
- Circuit breaker: Resilience4j with Redis health indicator

---

## Scenario 3: S3 Unavailable

**What breaks:** Paste creation fails for large pastes; reading large paste content fails.

**Detection:** S3 client timeout/error rate spikes; paste creation 500 rate increases.

**Blast radius:**
- Create paste (small, < 1 KB): unaffected (inline DB storage path)
- Create paste (large, ≥ 1 KB): fails (cannot write to S3)
- Read paste (small, inline): unaffected (served from DB/Redis)
- Read paste (large, S3 reference): fails at content fetch step

**Recovery strategy:**

```
For Reads:
  - Large pastes return metadata but "content temporarily unavailable"
  - Return 503 with Retry-After header
  - Public pastes: CDN may still serve cached content from edge (up to CDN TTL)

For Writes:
  Option A: Reject large paste creation (return 503 with retry guidance)
  Option B: Fall back to DB storage for pastes < 1 MB (relaxed inline threshold)
    - Risk: PostgreSQL TOAST mechanism handles < 1 GB per row
    - DB bloat risk: accept temporarily, cleanup when S3 recovers

S3 Recovery:
  - S3 Regional AZ failover: AWS handles automatically
  - For prolonged outage: switch to backup S3 bucket in different region
  - Multi-region S3 replication: CRR (Cross-Region Replication) for mission-critical deployments
```

**Prevention:**
- S3 is multi-AZ by default (AWS) — single AZ failure is transparent
- Multi-region: enable S3 CRR from us-east-1 to eu-west-1 for disaster recovery
- S3 presigned URL approach: client uploads directly → application not in S3 upload path

---

## Scenario 4: Kafka Broker Down (Partial)

**What breaks:** Event publishing slows; async side effects are delayed.

**Detection:** Kafka producer retry rate spikes; consumer lag increases; Outbox table row count grows.

**Blast radius:**
- Paste creation: NOT broken — Outbox pattern buffers events in PostgreSQL
- Paste expiry cleanup: delayed — pastes may live past expiry by minutes/hours
- CDN invalidation: delayed — deleted pastes may be served from CDN cache
- Analytics: consumer lag increases — view counts stale

**Key insight:** Because of the Outbox Pattern, **Kafka failure does not break the write path**. Paste creation succeeds. Events are held in the outbox table until Kafka recovers.

**Recovery:**
- Kafka cluster heals (replica election): typically < 30 seconds
- Outbox poller resumes publishing from oldest unpublished event
- Consumers catch up from where they left off (Kafka offset tracking)
- Large backlog: cleanup job and CDN invalidator run at higher throughput to catch up

---

## Scenario 5: Thundering Herd (Viral Paste)

**What breaks:** A paste gets shared widely (Hacker News, Reddit) → sudden burst of reads.

**Detection:** Read RPS for specific short_key spikes; Redis key hit rate for that key becomes extreme; CDN miss rate spike.

**Blast radius:**
- CDN: absorbs most traffic (public pastes cached at edge)
- Redis: single key receives extreme reads → hot key problem on single shard
- PostgreSQL: if CDN is cold (newly created paste) → DB hit burst

**Sequence of failure:**
```
T+0: Paste created and shared on social media
T+0–30s: CDN cold, Redis cold for this paste
          → 10,000 concurrent requests all miss Redis
          → All 10,000 hit PostgreSQL
          → PostgreSQL connection pool exhausted → 503 errors
T+30s: First reader warms Redis; CDN warms
        → Cache hit rate rises to 99%
        → DB load normalizes
```

**Mitigations:**

```
1. Mutex/lock on cache miss (implemented):
   First request gets lock → fetches from DB → warms Redis
   All others wait 100ms → re-check Redis → served from cache
   Prevents thundering herd on Redis miss

2. CDN warm-up (proactive):
   After paste creation: pre-warm CDN by making a synthetic request to /raw/{key}
   CDN caches it immediately
   Viral paste from T+0 serves from CDN edge

3. Local in-process cache (L0):
   Small Caffeine cache (100 entries, 30s TTL)
   Absorbs extreme hot key load before Redis
   Risk: 30s stale on delete → acceptable for viral content

4. Connection pool sizing:
   PgBouncer limits DB connections (20 max)
   Application instances queue requests rather than exhausting DB connections
```

---

## Scenario 6: Expiry Cleanup Backlog

**What breaks:** Cleanup job falls behind → expired pastes still accessible.

**Detection:** `expiry_schedule` table row count grows; consumer lag on `paste.expired` increases; expired pastes still serving.

**Causes:**
- Cleanup job instance crashed
- S3 delete failures causing slow processing
- DB poll query becomes slow (full table scan if index drops)

**Impact:**
- Expired pastes remain accessible (violation of user expectation)
- S3 storage not freed → cost accumulates

**Recovery:**
```
1. Restart cleanup job instances (fast recovery if crash)
2. Scale up cleanup job instances (handle backlog faster)
3. Validate expiry_schedule index health (explain plan on query)
4. Increase cleanup batch size (1,000 → 5,000 per poll cycle)
5. Backlog clears automatically as workers catch up
```

**Prevention:**
- Cleanup job health monitoring: alert if `expiry_schedule` unprocesed count > 10,000
- Run 2 cleanup instances always (HA, not just failover)
- Weekly audit: compare expired paste count in DB vs S3 object count (orphan detection)

---

## Scenario 7: Short Key Collision (Generation Failure)

**What breaks:** Paste creation fails because all generated keys are taken.

**Detection:** Application error log "key collision retry exhausted"; paste creation 503 rate spike.

**When this becomes an issue:**
- Base62, 6 chars = 56 billion possible keys
- At 10M active pastes: collision probability per attempt ≈ 0.0000000018% → negligible
- At 1 billion active pastes: collision probability ≈ 0.0018% → still manageable

**Recovery:**
- Retry with 7-char key: 62^7 = 3.5 trillion combinations
- Automatically increase key length as fill ratio increases
- Counter-based generation (sequential) eliminates collision entirely

---

## Scenario 8: Abuse Surge (Spam Attack)

**What breaks:** Anonymous paste creation endpoint flooded → rate limits overwhelmed → DB overloaded.

**Detection:** Anonymous paste creation rate spikes 10x; IP rate limiter DLQ fills; server CPU spikes.

**Blast radius:**
- Legitimate users: degraded experience (high latency or 429 errors)
- DB: write load increases dramatically
- Storage costs: S3 and DB grow unexpectedly

**Response:**
```
Immediate:
  1. WAF (Web Application Firewall): block abusive IP ranges
  2. CloudFlare Under Attack mode: JS challenge for all new visitors
  3. Temporary CAPTCHA for all anonymous paste creation
  4. Emergency rate limit reduction: 1 paste/hour per IP (from 10)

Medium-term:
  1. Analyze attack pattern: identify shared characteristics (IP range, content pattern)
  2. Update IP reputation blocklist (AbuseIPDB integration)
  3. Content pattern blocklist: add new spam signatures to L1 scan

Long-term:
  1. CAPTCHA as permanent feature for anonymous creation
  2. Anonymous paste size limit: 10 KB (not 10 MB) to reduce attack effectiveness
  3. Require email verification for large paste creation
```

---

## Scenario 9: Bad Deploy (Application Regression)

**What breaks:** New application version has a bug → elevated error rate.

**Detection:** 5xx rate increases after deploy; user reports; synthetic monitoring failures.

**Recovery (Kubernetes rolling deploy):**
```
1. Detect: error rate > 5% for 2 minutes (alerting)
2. Rollback: kubectl rollout undo deployment/pastebin-app
3. Kubernetes terminates new pods, restores old version
4. Recovery time: 2-5 minutes (pod termination + startup)
5. Post-mortem: identify root cause; add test coverage; re-deploy with fix
```

**Canary deployment (T2+ scale):**
```
1. Deploy new version to 5% of pods
2. Monitor error rate, latency, business metrics (paste creation success rate)
3. If healthy for 10 minutes: promote to 50% → 100%
4. If degraded: immediately roll back 5% canary
5. Zero user impact if canary fails (95% still on stable version)
```

---

## CAP Theorem Position

Pastebin chooses **CP (Consistency + Partition Tolerance)** for core data:

```
In a network partition between primary and replica:
  - Primary: continues accepting writes (consistency maintained)
  - Replica: may refuse reads (rather than serve stale data)
  
For paste content: strong consistency always (user must see their paste after creation)
For view counts: eventually consistent (analytics, not core)
For expiry: eventually consistent (within minutes of expiry, not exactly at second)
```

**Accepting inconsistency where it matters less** (view counts, analytics) and enforcing consistency where it matters most (paste creation, access control) is the pragmatic tradeoff.

---

## Resilience Patterns Applied

| Pattern | Where Applied | Purpose |
|---------|--------------|---------|
| Circuit Breaker | Redis, S3, PostgreSQL clients | Fail fast, prevent cascade |
| Retry with Exponential Backoff | Kafka consumers, S3 client | Handle transient failures |
| Transactional Outbox | Paste creation → Kafka | Prevent event loss |
| Dead Letter Queue | Cleanup consumer | Capture unrecoverable failures |
| Idempotent Consumers | All Kafka consumers | Safe at-least-once processing |
| Graceful Degradation | S3 down → DB fallback for small pastes | Partial availability |
| Mutex on Cache Miss | Redis cache layer | Prevent thundering herd |
| Read Replica Fallback | Primary DB down | Continue serving reads |
| Health Check Endpoints | All services | Load balancer aware of health |

---

## Interview Discussion Points

- What is the "split-brain" scenario in a PostgreSQL HA setup, and how does Patroni prevent it?
- How does the Outbox Pattern make the system resilient to Kafka outages without losing events?
- For a paste that is simultaneously being read by 10,000 users and gets deleted, what is the user experience? (Cache, CDN, invalidation timing)
- What is "thundering herd" and why does it specifically affect systems that use cache-aside?
- How do you determine the circuit breaker threshold (how many failures before opening)?
- What is the blast radius of a Redis failure vs a PostgreSQL failure vs an S3 failure? Which is worst?
