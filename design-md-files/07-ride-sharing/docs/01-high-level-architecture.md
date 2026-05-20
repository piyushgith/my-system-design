# 01 — High-Level Architecture: Ride-Sharing Platform

---

## Objective

Define the overall system architecture, justify the choice of microservices, describe service boundaries, communication patterns, and provide a complete visual representation of how data flows through the system from ride request to trip completion.

---

## 1. Architecture Choice: Microservices

### Why Microservices for Ride-Sharing?

This is one of the few cases where microservices are genuinely justified from day one — not because of organizational size, but because of fundamentally different operational characteristics across domains.

| Service Domain | Scale Characteristic | Why Independent? |
|---|---|---|
| Location Service | 250,000 writes/sec, sub-second latency | Must scale independently; overwhelms other services if colocated |
| Matching Service | CPU-intensive, bursty load during rush hour | Needs horizontal scaling divorced from data storage services |
| Trip Service | Moderate load, strong consistency required | Different DB transaction patterns than high-throughput services |
| Payment Service | Low throughput, extreme reliability | PCI scope isolation; different deployment and audit requirements |
| Pricing/Surge Service | Read-heavy, computation-intensive | Surge recalculation is CPU-heavy; can be stale by seconds |
| Notification Service | High fan-out, eventual consistency | Push/SMS delivery has different retry/failure semantics |
| Analytics Service | Read-heavy, batch-tolerant | Must not impact OLTP services; separate read path |

**The fundamental argument:** If you colocate Location Service with Trip Service, a surge in location writes (250K/sec) would starve trip management operations. Independent scaling is not premature optimization here — it is survival.

### Microservices Tradeoffs

**Costs accepted:**
- Distributed tracing complexity — every request spans multiple services
- Eventual consistency between services — trip state and payment state must be reconciled via events
- Operational burden — 7+ services to deploy, monitor, and operate
- Network overhead — inter-service calls add latency vs in-process calls
- Testing complexity — integration testing requires service virtualization

**Benefits realized:**
- Location Service can run on memory-optimized instances; Payment Service runs on smaller compute
- Each service can be deployed and rolled back independently
- Payment domain can be PCI-scoped separately from non-sensitive services
- Teams can own services autonomously (Matching team, Maps team, Payments team)
- Failure isolation: Payment Service outage does not crash Location updates

### When NOT to use microservices (honest assessment)

- For a startup with 5 engineers, start with a modular monolith. Extract Location Service first (unique scaling need), then Matching. Let microservices evolve from proven module boundaries.
- If your team cannot operate Kubernetes and distributed tracing, the operational cost exceeds the benefit.
- If your traffic is under 100K daily trips, a well-architected monolith with a Redis sidecar is far simpler.

---

## 2. Core Services Overview

| Service | Responsibility | Primary Data Store | Communication |
|---|---|---|---|
| API Gateway | Auth, routing, rate limiting, SSL termination | None | Sync (REST) |
| Location Service | Ingest driver GPS, serve nearby driver queries | Redis GEO | Async (Kafka) + Sync |
| Matching Service | Algorithmic driver-rider assignment | In-memory + Kafka | Event-driven |
| Trip Service | Trip lifecycle state machine | PostgreSQL | Sync + Async |
| Pricing Service | Fare estimation, surge multiplier calculation | Redis (cache) + PostgreSQL | Sync |
| Payment Service | Charge rider, credit driver, refunds | PostgreSQL + Payment Gateway | Async |
| Driver Service | Driver profile, availability, document management | PostgreSQL | Sync |
| Rider Service | Rider profile, preferences, history | PostgreSQL | Sync |
| Notification Service | Push, SMS, in-app notifications | Redis (queue) | Async |
| Analytics Service | Trip metrics, revenue, driver utilization | ClickHouse / BigQuery | Async (Kafka consumer) |

---

## 3. High-Level Architecture Diagram

```mermaid
graph TB
    subgraph Client Layer
        RA[Rider App\niOS/Android]
        DA[Driver App\niOS/Android]
        WA[Web App\nBrowser]
    end

    subgraph Edge Layer
        CDN[CDN\nCloudFront/Fastly]
        LB[Load Balancer\nNGINX / AWS ALB]
        AG[API Gateway\nKong / AWS API GW]
        WS[WebSocket Gateway\nSeparate connection tier]
    end

    subgraph Core Services
        LS[Location Service\nRedis GEO]
        MS[Matching Service\nAlgorithm Engine]
        TS[Trip Service\nState Machine]
        PS[Pricing Service\nSurge Engine]
        PAY[Payment Service\nSaga Orchestrator]
        DS[Driver Service]
        RS[Rider Service]
        NS[Notification Service]
    end

    subgraph Data Layer
        PG1[(PostgreSQL\nTrips DB)]
        PG2[(PostgreSQL\nUsers DB)]
        PG3[(PostgreSQL\nPayments DB)]
        RD1[(Redis Cluster\nDriver Locations\nCity Shards)]
        RD2[(Redis\nCache + Sessions)]
        ES[(Elasticsearch\nSearch + Analytics)]
    end

    subgraph Event Bus
        KF[Apache Kafka\nEvent Streaming]
    end

    subgraph External Services
        MAP[Maps API\nGoogle Maps/HERE]
        PGW[Payment Gateway\nStripe/Razorpay]
        PUSH[Push Service\nFCM/APNS]
        SMS[SMS Gateway\nTwilio]
        BGC[Background Check\nExternal API]
    end

    subgraph Observability
        PROM[Prometheus]
        GRAF[Grafana]
        JAEG[Jaeger\nDistributed Tracing]
        ELK[ELK Stack\nLog Aggregation]
    end

    RA --> CDN
    WA --> CDN
    CDN --> LB
    LB --> AG
    LB --> WS
    DA --> AG

    AG --> LS
    AG --> MS
    AG --> TS
    AG --> PS
    AG --> DS
    AG --> RS
    AG --> PAY

    WS --> TS
    WS --> LS

    LS --> RD1
    LS --> KF

    MS --> LS
    MS --> TS
    MS --> PS
    MS --> KF
    MS --> MAP

    TS --> PG1
    TS --> KF
    TS --> RD2

    PS --> RD2
    PS --> PG1

    PAY --> PG3
    PAY --> PGW
    PAY --> KF

    DS --> PG2
    DS --> BGC
    RS --> PG2

    KF --> NS
    KF --> MS
    KF --> PAY

    NS --> PUSH
    NS --> SMS

    PG1 --> ES
    KF --> ES
```

---

## 4. Request Flow: Rider Requests a Ride

```mermaid
sequenceDiagram
    participant RA as Rider App
    participant AG as API Gateway
    participant TS as Trip Service
    participant PS as Pricing Service
    participant MS as Matching Service
    participant LS as Location Service
    participant DA as Driver App
    participant NS as Notification Service
    participant PAY as Payment Service

    RA->>AG: POST /trips (pickup, destination, vehicle_type)
    AG->>PS: GET /fare-estimate
    PS->>LS: GET /surge-multiplier (city_id)
    LS-->>PS: surge = 1.8x
    PS-->>AG: fare_range = ₹150-180
    AG-->>RA: fare estimate shown

    RA->>AG: POST /trips/confirm
    AG->>TS: createTrip(rider_id, pickup, destination)
    TS->>TS: TRIP_CREATED state persisted
    TS->>MS: requestMatch(trip_id, pickup_coords, vehicle_type)

    MS->>LS: getNearbyDrivers(lat, lng, radius=5km, vehicle_type)
    LS-->>MS: [driver_1, driver_2, driver_3] sorted by distance

    MS->>MS: rank drivers (distance + rating + acceptance rate)
    MS->>DA: PUSH ride_offer (via Notification Service)
    NS->>DA: Push notification with trip details

    DA->>AG: POST /trips/{trip_id}/accept
    AG->>MS: driverAccepted(trip_id, driver_id)
    MS->>TS: assignDriver(trip_id, driver_id)
    TS->>TS: state → DRIVER_MATCHED (atomic update)
    TS-->>RA: WebSocket push — driver assigned, ETA = 4 min

    Note over MS,DA: If driver rejects or no response in 15s, offer to next driver
```

---

## 5. Real-Time Tracking Architecture

```mermaid
graph LR
    subgraph Driver App
        GPS[GPS Module]
        SDK[Driver SDK]
    end

    subgraph Backend
        LC[Location Collector\nHTTP/2 or WebSocket]
        LW[Location Writer\nKafka Producer]
        RG[(Redis GEO\nCity Shard)]
        KT[Kafka Topic\ndriver-location-updates]
        LP[Location Pusher\nKafka Consumer]
        WS[WebSocket Server\nSticky Session]
    end

    subgraph Rider App
        MAP[Map View]
    end

    GPS -->|every 4 seconds| SDK
    SDK -->|batch or stream| LC
    LC --> LW
    LW --> RG
    LW --> KT
    KT --> LP
    LP -->|push to rider's WebSocket| WS
    WS --> MAP
```

**Key design decision:** Driver location is written to Redis GEO immediately (for matching queries) AND published to Kafka (for fan-out to rider apps watching active trips). This dual-write ensures both real-time matching and real-time tracking work without coupling.

---

## 6. Service Communication Patterns

### Synchronous (REST/gRPC)

Used when the caller needs an immediate response to proceed:

- Rider App → API Gateway → Trip Service (create trip)
- API Gateway → Pricing Service (fare estimate — rider is waiting)
- Matching Service → Location Service (find nearby drivers)
- Matching Service → Maps API (ETA calculation)

### Asynchronous (Kafka Events)

Used when the action is fire-and-forget or fan-out:

- Location Service → Kafka (driver location updates — high volume, no blocking needed)
- Trip Service → Kafka (trip lifecycle events — multiple consumers)
- Payment Service → Kafka (payment events — notification, analytics, driver ledger)
- Matching Service → Kafka (match events — triggers notifications, trip updates)

### WebSocket (Server-Sent Push)

Used for real-time client updates:

- Backend → Rider App (driver location updates, trip status changes)
- Backend → Driver App (new ride offers, trip instructions)

### Service Mesh Consideration

At Uber/Ola scale, a service mesh (Envoy/Istio) is essential:
- mTLS between services — no network-level snooping possible
- Circuit breaking — Location Service slowdown does not cascade to Matching
- Automatic retries with backoff — configurable per route
- Observability — L7 metrics without app-level instrumentation
- Traffic shaping — canary deploys at 1% of a service's traffic

For a startup or mid-size team: Skip the service mesh initially. Use client-side libraries (Resilience4j) for circuit breaking. Add Istio when you have a dedicated platform team.

---

## 7. API Gateway Responsibilities

| Responsibility | Implementation |
|---|---|
| Authentication | JWT validation (RS256, public key verification) |
| Rate limiting | Per rider: 10 ride requests/min; per driver: 60 location updates/min |
| Request routing | Path-based routing to microservices |
| SSL termination | TLS 1.3 at gateway; internal traffic optionally HTTP |
| Request/response logging | Correlation ID injection on all requests |
| Load balancing | Round-robin to service replicas |
| API versioning | Path prefix /v1/, /v2/ |
| Circuit breaking | Fail fast if downstream service is unhealthy |

---

## 8. Data Flow for Trip Completion and Payment

```mermaid
sequenceDiagram
    participant DA as Driver App
    participant TS as Trip Service
    participant KF as Kafka
    participant PAY as Payment Service
    participant PGW as Payment Gateway
    participant DS as Driver Service
    participant NS as Notification Service

    DA->>TS: POST /trips/{id}/complete
    TS->>TS: state → COMPLETED, calculate fare
    TS->>KF: publish TripCompleted{trip_id, fare, rider_id, driver_id}

    KF->>PAY: consume TripCompleted
    PAY->>PGW: charge rider's saved card (idempotency_key = trip_id)
    PGW-->>PAY: charge success

    PAY->>PAY: persist payment record
    PAY->>KF: publish PaymentProcessed{trip_id, amount, driver_share}

    KF->>DS: consume PaymentProcessed
    DS->>DS: credit driver earnings ledger

    KF->>NS: consume PaymentProcessed
    NS->>NS: send receipt to rider (email + push)
    NS->>NS: send earnings notification to driver
```

---

## 9. Multi-Region Architecture Overview

```mermaid
graph TB
    subgraph Region US-East
        AG_US[API Gateway]
        SVC_US[Services Cluster]
        DB_US[(PostgreSQL Primary\nUS)]
        RD_US[(Redis Cluster\nUS)]
        KF_US[Kafka Cluster\nUS]
    end

    subgraph Region IN-Mumbai
        AG_IN[API Gateway]
        SVC_IN[Services Cluster]
        DB_IN[(PostgreSQL Primary\nIndia)]
        RD_IN[(Redis Cluster\nIndia)]
        KF_IN[Kafka Cluster\nIndia]
    end

    subgraph Region EU-Frankfurt
        AG_EU[API Gateway]
        SVC_EU[Services Cluster]
        DB_EU[(PostgreSQL Primary\nEU)]
        RD_EU[(Redis Cluster\nEU)]
        KF_EU[Kafka Cluster\nEU]
    end

    GLB[Global Load Balancer\nGeoDNS / AWS Route53]
    GLB --> AG_US
    GLB --> AG_IN
    GLB --> AG_EU

    DB_US -->|async replication| DB_IN
    DB_IN -->|async replication| DB_EU
```

**Key principle:** Each region is operationally independent. A rider in Mumbai is served entirely by the India region. User profile data is replicated globally for auth, but trip data stays in the originating region. This satisfies data residency laws and reduces latency.

---

## 10. Failure Isolation Design

```mermaid
graph LR
    MS[Matching Service] -->|Circuit Breaker| LS[Location Service]
    MS -->|Circuit Breaker| MAP[Maps API]
    TS[Trip Service] -->|Circuit Breaker| PAY[Payment Service]
    AG[API Gateway] -->|Circuit Breaker| MS
    AG -->|Circuit Breaker| TS

    CB[Circuit Breaker\nStates]
    CB --> CLOSED[Closed\nNormal Operation]
    CB --> OPEN[Open\nFail Fast]
    CB --> HALF[Half-Open\nProbe Recovery]
```

- If Location Service is slow (>500ms P99), Matching Service opens circuit and uses last-known driver positions from a short-lived cache for up to 30 seconds before queueing requests
- If Maps API is slow, Matching Service falls back to Haversine distance-based ETA (less accurate but functional)
- If Payment Service is degraded, Trip can complete and payment is queued for retry (deferred charge pattern)

---

## Interview-Level Discussion Points

- **Why not a single service with Redis GEO bolted on?** At 250K location writes/second, the location ingestion path needs dedicated horizontal scaling. Mixing it with trip management would require the entire system to scale to location-write load.
- **Why API Gateway and not direct service calls from clients?** Security (single auth validation point), rate limiting, versioning, and the ability to refactor services without breaking clients.
- **How do you handle the WebSocket connection at 200K concurrent riders?** WebSocket servers are stateful. You need sticky sessions (consistent hashing to the same pod) or a pub/sub broker (Redis Pub/Sub or Kafka) that any WebSocket pod can subscribe to for pushing updates to connected riders.
- **Why Kafka and not just direct REST calls between services?** Location updates at 250K/sec cannot be fanned out synchronously. Kafka provides backpressure handling, replay capability, and decoupling of producer throughput from consumer processing speed.
- **Service mesh vs. no service mesh at what scale?** Service mesh adds ~5ms latency per hop and significant operational complexity. Justified when you have 15+ services and a dedicated platform team. For 7 services, use library-level resilience (Resilience4j).
