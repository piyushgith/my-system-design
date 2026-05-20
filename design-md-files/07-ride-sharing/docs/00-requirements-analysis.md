# 00 — Requirements Analysis: Ride-Sharing Platform

---

## Objective

Define the complete requirements landscape for a production-grade ride-sharing platform (Uber/Ola scale), including functional capabilities, non-functional guarantees, capacity planning, and traffic modeling. This document serves as the foundation for every architectural decision that follows.

---

## 1. Problem Statement

Build a real-time ride-sharing platform that matches riders with nearby available drivers, handles the complete trip lifecycle from request to payment, provides live GPS tracking, and scales to tens of millions of daily trips across multiple cities globally.

The core technical challenge is the intersection of:
- Real-time geospatial computation at massive scale
- Sub-second matching under high concurrency
- Distributed consistency across a multi-service architecture
- Financial transaction reliability
- Location privacy and data sovereignty

---

## 2. Functional Requirements

### 2.1 Rider-Facing Features

| Feature | Description | Priority |
|---|---|---|
| Ride Request | Rider sets pickup + destination, selects vehicle type, sees fare estimate | P0 |
| Driver Matching | System finds and assigns nearest available driver | P0 |
| Real-Time Tracking | Rider sees driver's live location on map during approach and trip | P0 |
| ETA Display | Estimated arrival time shown before and after matching | P0 |
| Trip Cancellation | Rider can cancel before driver arrives (cancellation fee applies after threshold) | P0 |
| Payment | Automatic charge via saved card/wallet at trip end | P0 |
| Trip History | Rider can view past trips with receipts | P1 |
| Rating & Review | Rider rates driver (1–5 stars) after trip completion | P1 |
| Fare Estimate | Show fare range before requesting, accounting for surge | P0 |
| Scheduled Rides | Book a ride up to 7 days in advance | P2 |
| Ride Pooling | Match multiple riders going in similar directions (UberPool equivalent) | P2 |
| SOS / Emergency | One-tap emergency contact + share trip status | P1 |

### 2.2 Driver-Facing Features

| Feature | Description | Priority |
|---|---|---|
| Go Online/Offline | Driver toggles availability | P0 |
| Ride Accept/Reject | Driver accepts or rejects incoming ride requests | P0 |
| Navigation | In-app turn-by-turn or deep link to Google Maps/Waze | P0 |
| Trip Status Updates | Mark arrived at pickup, start trip, end trip | P0 |
| Location Broadcasting | Continuous GPS update to backend while online | P0 |
| Earnings Dashboard | View daily/weekly earnings, trip breakdown | P1 |
| Rating Display | Driver sees their current rating | P1 |
| Surge Zone Visibility | Driver sees heatmap of high-demand areas | P2 |
| Driver Incentives | Bonus tracking (e.g., complete 10 rides for ₹500 bonus) | P2 |

### 2.3 Platform/Admin Features

| Feature | Description | Priority |
|---|---|---|
| Driver Onboarding | Background check integration, document verification | P0 |
| Fraud Detection | Detect GPS spoofing, fake trip completion | P1 |
| Surge Pricing Engine | Dynamic multiplier based on supply-demand ratio | P0 |
| City Management | Configure city boundaries, zone pricing, driver caps | P1 |
| Analytics Dashboard | Trip metrics, driver utilization, revenue per city | P1 |
| Dispute Resolution | Rider/driver can report issues; admin can review | P1 |

---

## 3. Non-Functional Requirements

### 3.1 Performance SLOs

| Operation | Target Latency | Notes |
|---|---|---|
| Driver matching | < 3 seconds P99 | From ride request to driver assigned |
| Location update ingestion | < 500ms P99 | GPS ping to backend stored in Redis |
| ETA calculation | < 1 second P99 | Routing API response |
| Fare estimate | < 2 seconds P99 | Include surge calculation |
| Payment processing | < 5 seconds P99 | End-to-end charge confirmation |
| Trip status update | < 500ms P99 | Driver marks arrived/started/ended |
| Live tracking update on rider screen | < 2 seconds | Location lag acceptable |
| Ride history query | < 300ms P99 | Paginated, indexed query |

### 3.2 Availability Targets

| Component | Availability | Rationale |
|---|---|---|
| Matching Service | 99.99% | Core revenue path; downtime = no rides |
| Location Service | 99.99% | Real-time dependency for matching |
| Payment Service | 99.99% | Financial; outage = revenue loss and liability |
| Trip Service | 99.99% | Active trips cannot be interrupted |
| Notification Service | 99.9% | Degraded experience but not critical path |
| Analytics Service | 99.5% | Internal use; eventual consistency acceptable |

### 3.3 Consistency Requirements

| Data | Consistency Model | Rationale |
|---|---|---|
| Driver location | Eventual (seconds) | Stale by 1–2s is acceptable |
| Trip state | Strong | Cannot have two drivers assigned to same trip |
| Payment | Strong | Financial accuracy is non-negotiable |
| Surge multiplier | Eventual (seconds) | Minor inaccuracy acceptable; recalculated frequently |
| Rider/driver profile | Eventual | Profile reads can tolerate slight staleness |

### 3.4 Reliability

- RTO (Recovery Time Objective): < 30 seconds for stateless services, < 2 minutes for stateful
- RPO (Recovery Point Objective): 0 for payment data, < 5 seconds for trip state, < 30 seconds for location data
- Zero data loss for financial transactions
- Graceful degradation: if matching is slow, queue rides rather than fail
- Active trip continuity: ongoing trips must survive any single service failure

---

## 4. Scale Estimation

### 4.1 User Base

```
Global active riders:      100 million
Global active drivers:     10 million registered, 1 million online simultaneously (10%)
Daily Active Riders:       20 million
Peak concurrent riders:    2 million (evening rush hour)
Cities covered:            500+ cities globally
Average city size (major): 50,000 active drivers, 500,000 daily riders
```

### 4.2 Trip Volume

```
Daily trips globally:      5 million
Peak trips per hour:       500,000 (10% of daily in 1 hour during evening rush)
Peak trips per minute:     ~8,333
Peak trips per second:     ~139

Average trip duration:     25 minutes
Average trip distance:     8 km
Peak concurrent active trips: ~200,000 (at any point during peak hour)
```

### 4.3 Location Update Volume

```
Active drivers (peak):     1,000,000
Location update frequency: every 4 seconds per driver
Location updates per sec:  250,000 updates/second
Location updates per min:  15,000,000 updates/minute (15M/min, higher than brief spec)

Note: During off-peak, drops to ~50,000 updates/sec (200,000 online drivers)
```

**Back-of-envelope justification:**

- 1M online drivers × 1 update/4 seconds = 250,000 updates/sec
- Each update payload: ~200 bytes (driver_id, lat, lng, timestamp, heading, speed)
- Bandwidth for ingestion: 250,000 × 200B = 50 MB/s = 400 Mbps inbound
- This is substantial but manageable with horizontal partitioning by city

### 4.4 Matching Load

```
Ride requests per second (peak): 139 RPS
Each matching query scans ~1,000 nearby drivers per city segment
Matching decision per request: < 50ms computation + 2.5s driver response window
Concurrent matching jobs: ~139 × 3s window = ~417 concurrent matching processes
```

### 4.5 Storage Estimation

#### Trip Data (PostgreSQL)

```
Trips per day:             5 million
Avg trip record size:      2 KB (metadata + route snapshot)
Daily trip storage:        5M × 2KB = 10 GB/day
Yearly trip storage:       3.65 TB/year
With indexes (~2x):        7 TB/year
10-year retention:         70 TB → archive cold data to S3 after 6 months
```

#### Location History (if retained for audit/ML)

```
Updates per second:        250,000
Updates per day:           250,000 × 86,400 = 21.6 billion
Per update storage:        50 bytes (compressed lat/lng + timestamp)
Daily storage:             ~1.08 TB/day
→ Do NOT store all in OLTP. Stream to data lake (S3/GCS) via Kafka
```

#### Driver Location Redis Cache

```
Online drivers:            1,000,000
Per driver GEO entry:      ~64 bytes (Redis GEO uses sorted set internally)
Total Redis memory:        1M × 64B = 64 MB per city cluster
Across 50 major cities:    ~3.2 GB total (negligible)
```

#### User/Driver Profile Data

```
Total registered users:    200 million
Avg profile size:          1 KB
Total profile storage:     200 GB (trivial at this scale)
```

### 4.6 Read/Write Patterns

| Endpoint | Type | RPS Estimate | Notes |
|---|---|---|---|
| Driver location update | Write | 250,000/s | High-frequency, low-latency writes to Redis |
| Nearby driver query | Read | 500/s | Per active matching job (139 requests × fanout) |
| Trip status read (rider polling) | Read | 400,000/s | 200K active trips × 2 reads/sec |
| Trip write (lifecycle events) | Write | ~5,000/s | Arrived, started, completed events |
| Payment write | Write | 139/s | One per trip completion at peak |
| Ride request (new trip) | Write | 139/s | Trip creation |
| Rating submission | Write | ~100/s | Post-trip, delayed |
| Surge multiplier read | Read | ~10,000/s | Cached; every fare estimate reads it |

### 4.7 Network Bandwidth

```
Inbound (location updates):    400 Mbps
Outbound (WebSocket pushes):   200K active trips × 1 update/2s × 500 bytes = 400 Mbps
Total peak bandwidth:          ~1 Gbps (manageable per region)
```

---

## 5. Assumptions

- Drivers use a dedicated mobile app with GPS capabilities
- Riders use a mobile app (iOS/Android) or web interface
- Third-party routing/maps API (Google Maps, HERE, or Mapbox) for ETA and navigation
- Payment gateway (Stripe/Braintree/Razorpay) handles actual card processing
- SMS/push notification infrastructure via third-party (FCM, APNS, Twilio)
- Each city operates as a logical partition for matching and location
- Drivers must complete background checks before going online (offline process)
- All monetary values are handled in minor currency units (paise/cents) as integers
- Rides are point-to-point (no multi-stop optimization in MVP)

---

## 6. Constraints

- Location data must respect GDPR (EU) and regional data residency laws
- Payment data must be PCI-DSS compliant; raw card data never touches our servers
- Driver background check is an async, offline-first process
- Network quality varies: driver app must handle intermittent connectivity gracefully
- Matching must work even if the routing API is slow; ETA estimate can be approximate
- Cannot store precise location history longer than legally required (varies by jurisdiction)

---

## 7. Out of Scope (for initial design)

- Public transit integration
- Delivery services (food/packages) — separate platform
- Driver financing programs
- Autonomous vehicle fleet management
- Cross-border rides
- Ride-sharing with public APIs for third-party booking

---

## 8. Key Architectural Signals from Requirements

1. **Location updates are the highest-volume write** — Redis GEO is mandatory; PostgreSQL cannot absorb 250K writes/sec without extreme engineering
2. **Matching latency (<3s) with strong consistency** — trip assignment must be atomic; two drivers cannot accept the same ride
3. **Trip state machine requires strong consistency** — distributed locking or single-writer-per-trip pattern needed
4. **Payment is a distributed transaction** — trip completion → charge rider → credit driver; Saga pattern required
5. **City is the natural partition key** — all matching, surge, and location operations are city-scoped
6. **Active trips must survive service restarts** — durable state with Kafka event sourcing or database fallback
7. **WebSocket connections at scale** — 200K concurrent active trips; sticky sessions or pub/sub required

---

## Interview-Level Discussion Points

- **Why is matching latency the hardest SLO to meet?** It combines geospatial search (Redis GEO), availability check (driver state), and a real-time round-trip to the driver's device — all under 3 seconds including network RTT.
- **How would you handle a city blackout event (e.g., festival) with 10x normal demand?** Surge pricing throttles demand, but matching still needs to queue requests. Discuss request queuing with TTL, burst capacity via HPA, and graceful degradation.
- **What is the true cost of 250K location writes/second?** A single Redis instance can handle ~100K ops/sec. At 250K/sec, you need sharding by city. A major city like Mumbai might have 50K active drivers = 12.5K writes/sec, well within one Redis shard.
- **Why not store all location history?** 1TB/day raw — you'd burn through storage faster than you could provision it. Stream to data lake for ML training; don't persist in OLTP.
- **How do you prevent double-charging a rider?** Idempotency keys on payment API + exactly-once semantics via Kafka transactions + database-level deduplication.
