# 14 — Interview Discussion Points: Ride-Sharing Platform

## Objective
Compile the hardest interviewer challenges, common candidate mistakes, senior/staff-level discussion depth, and "what breaks first" analysis for a ride-sharing system design interview. This system is particularly rich in geospatial, real-time, and distributed systems complexity.

---

## Expected Interviewer Questions by Category

### 1. Geospatial & Location

| Question | Strong Answer Direction |
|----------|------------------------|
| How do you store and query driver locations at scale? | Redis GEO commands: `GEOADD drivers:bangalore {lng} {lat} {driverId}` on each location update. `GEORADIUS drivers:bangalore {lng} {lat} 5km ASC COUNT 20` for nearby drivers. O(N+log(M)) complexity. Partition by city to avoid single key hotspot. |
| Why Redis GEO instead of PostGIS? | Redis GEO is in-memory → microsecond queries vs millisecond. Location is ephemeral (30s TTL) — no durability needed. PostGIS shines for complex polygon queries (geofences, admin areas). Use PostGIS for surge zone boundaries, Redis GEO for live driver lookup. |
| What is Google S2 and how does Uber use it? | S2 partitions Earth into hierarchical cells using Hilbert curve. Each cell has a 64-bit integer ID. Drivers stored per S2 cell → nearby drivers found by querying adjacent cells. More flexible than Redis GEO (variable precision, hierarchical queries), but more complex to implement. |
| How do you handle 10 million location updates per minute? | Partition Kafka topic by city_id (not driver_id) to avoid hot partitions. Location Service consumers per city. Redis GEOADD is O(log N) per call. At 10M/min = 167K/sec → Redis cluster with 6 shards, each handling ~28K ops/sec (well within Redis's 100K+ ops/sec capacity per node). |
| A driver is in a tunnel with no GPS signal for 2 minutes. What happens? | Driver app uses dead reckoning (last known speed + direction). After 30s without update, Redis key expires → driver marked unavailable. After 2 min offline, Trip Service sends "are you still there?" ping. If no response in 5 min and trip is active → auto-complete at last known location + manual review trigger. |

### 2. Matching Algorithm

| Question | Strong Answer Direction |
|----------|------------------------|
| Walk me through the matching algorithm. | 1) Get nearby available drivers (Redis GEO radius). 2) Filter: vehicle type match, driver acceptance rate > threshold, rating > minimum. 3) Score each driver: weighted sum of (proximity score, ETA, driver rating, acceptance rate). 4) Select top driver. 5) Send push notification with 15s acceptance window. 6) If rejected/timeout → next candidate. |
| How do you match in < 3 seconds? | Redis GEO query: < 5ms. Scoring 20 candidates: < 10ms. Push notification dispatch: async (don't wait for delivery confirmation). DB write for match: < 20ms with read-after-write optimization. Total synchronous path: < 50ms. The 3s SLO is for driver acceptance — matching itself is < 100ms. |
| What if no driver is available nearby? | Expand search radius in increments (2km → 5km → 10km) with increasing wait time. Show rider "searching" UI with driver count indicator. After 5 minutes of no match → "no drivers available" + suggest future booking. Log unmatched requests for demand forecasting. |
| How do you prevent two riders from being matched to the same driver simultaneously? | Optimistic locking with Redis: `SET driver:{id}:status MATCHED NX EX 30` (SET if Not eXists, expire 30s). Only the first matching attempt that sets this key wins. Others skip this driver and try the next candidate. |

### 3. Surge Pricing

| Question | Strong Answer Direction |
|----------|------------------------|
| How does surge pricing work? | Calculate supply/demand ratio per geographic zone (hexagonal grid, H3 library). ratio = active_riders_requesting / available_drivers. Surge multiplier = f(ratio) → e.g., ratio > 2.0 → 1.5x, ratio > 4.0 → 2.0x. Update every 60 seconds. Cache surge multiplier per zone in Redis. |
| How do you define surge zones? | Uber uses H3 hexagonal grid (Uber open source). Each hexagon is ~1 km². Aggregate demand and supply at hexagon level. Parent hexagon (larger) used for pricing to smooth boundaries. |
| How do you prevent surge gaming? | Driver app cannot see surge map. Show rider the multiplier but not the formula. Cap surge at regulatory limits (some cities have rules). Log surge events for compliance review. |
| How do you communicate surge to the rider before they book? | Fare estimate API returns `basePrice × surgeMultiplier + breakdown`. Rider must confirm the fare estimate before booking. Surge locked at booking time, not at trip completion. |

### 4. Trip Lifecycle & Failures

| Question | Strong Answer Direction |
|----------|------------------------|
| Walk me through a full trip from request to payment. | Rider requests → Matching finds driver → Driver accepts → ETA shown → Driver arrives (status: ARRIVED) → Rider confirmed → Trip starts (status: IN_PROGRESS) → Driver ends trip → Trip completes → Fare calculated → Payment charged → Ratings requested → Events to analytics. |
| What if the payment fails at trip end? | Retry payment 3× with exponential backoff. If still failing → mark trip as PAYMENT_PENDING. Background job retries every 15 min for 24h. After 24h failure → send invoice to rider (email/app), mark as PAYMENT_OUTSTANDING. After 7 days → block rider account until settled. |
| What if the driver cancels mid-trip? | Trip transitions to DRIVER_CANCELLED. Immediately begin re-matching from rider's current GPS location. Rider gets notification. If no driver found in 10 min → full refund and issue credit. Driver cancellation rate tracked — too many mid-trip cancellations → driver account review. |
| How do you handle an abruptly ended trip (driver app crash)? | Trip Service has a watchdog: checks for trips in IN_PROGRESS state with no location update for > 5 min. Sends push notification to driver. If no response in 5 min → auto-complete trip at last known GPS location. Fare calculated from last GPS point. Flag for manual review. |

### 5. Distributed Systems & Scale

| Question | Strong Answer Direction |
|----------|------------------------|
| How do you handle a city-level outage? | City's data is partitioned. Other cities unaffected. Within the city: multi-AZ deployment means single AZ failure is transparent. Full region failure → Route 53 failover to secondary region, but location data is lost (ephemeral). Active trips in failed region get auto-completed at last known position + refund. |
| What breaks first at 10× current scale? | Location update ingestion (Redis writes). Solution: add Redis shards. Second: Matching Service CPU (more concurrent searches). Solution: scale pods. Third: PostgreSQL trip writes (connection pool exhaustion). Solution: PgBouncer connection pooler + read replicas for analytics queries. |
| How do you guarantee exactly-once payment? | Payment Service uses idempotency key (tripId + attempt_number). Kafka exactly-once semantics for payment events. Payment gateway supports idempotency key. Even if retried, the same idempotency key → no double charge. |
| How do you handle the thundering herd when surge ends? | When surge drops, many riders request simultaneously. Location Service receives spike. Rate limit at API gateway (per-city burst limit). KEDA scales Matching pods in advance (predict surge end from pricing signal). Kafka absorbs spike as queue. |

---

## Common Candidate Mistakes

| Mistake | Why It's Wrong | Correct Approach |
|---------|----------------|-----------------|
| Storing live driver locations in PostgreSQL | RDBMS cannot handle 167K geospatial writes/second | Redis GEO with city-level partitioning |
| Matching synchronously in HTTP request path | Matching involves push notifications and driver acceptance — cannot be synchronous | Kafka-driven async matching with rider polling/WebSocket |
| Single global Redis key for all drivers | Hot key problem — one Redis node overwhelmed | Partition by city: `drivers:{city}` |
| Polling for trip status every second | 50M DAU × 1 req/s = 50M RPS on Trip Service | WebSocket or SSE push from Trip Service on state change |
| Not handling driver double-booking | Two riders matched to same driver | Redis `SET NX` (set-if-not-exists) atomic lock per driver |
| Real-time surge calculation per request | Calculating supply/demand per ride request is O(all active requests) | Pre-calculate surge per zone every 60s, cache in Redis |
| Not considering regulatory constraints | Many cities ban surge pricing above 2× during emergencies | Surge pricing service must read regulatory config per city |

---

## Senior Engineer Discussion Points

- **CAP theorem for location data**: Location is AP (available + partition-tolerant). We accept that during network partition, two regions may see different driver availability. Consistency is not critical — showing slightly stale driver count is acceptable.
- **Idempotency throughout**: Every operation (match, payment, state transition) must be idempotent. tripId is the idempotency key — safe to retry the entire matching flow.
- **Backpressure on location updates**: If Redis is slow, Location Service's Kafka consumer falls behind. This is correct behavior — backpressure. Driver GPS timestamps are preserved in events, so when consumed late, stale data is detected and discarded.
- **Database schema for trip history**: Trips table partitioned by `created_at` month. Old partitions (> 6 months) archived to S3 Parquet. Athena/BigQuery for historical analytics.

---

## Staff Engineer Discussion Points

- **H3 hexagonal grid vs S2 vs Geohash**: H3 (Uber open source) → uniform hexagons, consistent area, great for surge zones. S2 → hierarchical cells, great for variable-resolution queries. Geohash → simple string prefix, easy to implement, non-uniform cell shapes (worse at poles). For a ride-sharing system with city-level precision: H3 wins for surge, Redis GEO (geohash-based internally) for driver proximity.
- **Matching as an optimization problem**: Real Uber matching is NP-hard (global optimal assignment across thousands of drivers and riders). Greedy local matching (nearest driver) is O(N) but suboptimal. Hungarian algorithm (optimal) is O(N³) — too slow. Production solution: approximate local optimization with ML-predicted ETA correction.
- **Build vs Buy for maps/routing**: Google Maps API at scale = $X million/year. Uber built their own mapping stack (H3, Valhalla routing engine, in-house map tiles). Justification: at 10M trips/day × $0.005/routing call = $18M/year just for routing. Self-hosted Valhalla pays for itself in 6 months at this scale.
- **Multi-modal matching**: UberPool (shared rides) requires matching multiple riders with compatible routes. This is a vehicle routing problem (VRP) — computationally intractable for large N. ML-based heuristics + geographic clustering solve it in practice.

---

## "What Would Break First?" Analysis

| Scale Factor | First Failure | Second Failure | Third Failure |
|-------------|--------------|----------------|---------------|
| 2× location update volume | Redis write throughput (single shard) | Kafka consumer lag | Nothing else at 2× |
| 5× ride requests | Matching Service pods (CPU) | PostgreSQL connections | Redis reads |
| 10× concurrent drivers | Redis GEO memory (per-city key too large) | Location Service WebSocket pods | Notification Service throughput |
| Surge event (Diwali/NYE) | API gateway rate limiter (need raise) | Matching Service queue depth | Push notification delivery (FCM rate limit) |
| Multi-city expansion (10 new cities) | Ops burden (per-city monitoring) | Database partitioning (add city partition) | City-specific regulatory config management |

---

## Tradeoff Discussions Interviewers Expect

| Topic | Option A | Option B | Production Choice |
|-------|----------|----------|------------------|
| Driver location storage | Redis GEO | PostGIS | Redis GEO (speed) + PostGIS for geofences |
| Matching: sync vs async | Synchronous HTTP | Kafka async | Kafka async (handles driver acceptance time) |
| Surge zone shape | Square grid | H3 hexagons | H3 (uniform area, no corner artifacts) |
| Driver accept/reject | Push notification | Driver polls | Push (FCM/APNs) + polling fallback |
| Trip history storage | Hot PostgreSQL | Archived to S3 | Tiered: 6mo in PG, older to S3+Athena |
| Payment timing | Before trip | After trip | After trip (ride-first, pay-on-completion) |
