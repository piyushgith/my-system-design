# 15 — Implementation Roadmap: Stock Trading Order Book

## Objective

Phase-wise build plan from MVP (basic matching with single symbol) to production-grade exchange with 500 symbols, FIX protocol, circuit breakers, and regulatory compliance.

---

## Phase Overview

| Phase | Scope | Timeline | Team Size |
|-------|-------|----------|-----------|
| MVP | Single symbol, basic limit orders, REST API | 8 weeks | 2-3 engineers |
| V1 | Multiple symbols, market orders, WebSocket | 12 weeks | 4-5 engineers |
| V2 | FIX protocol, IOC/FOK, circuit breakers, risk controls | 16 weeks | 6-8 engineers |
| V3 | 500 symbols, Disruptor, co-location, regulatory compliance | 20 weeks | 10+ engineers |

---

## MVP: Single Symbol, Basic Matching

**Goal:** Prove the matching algorithm is correct. Basic REST API. No latency requirements yet.

### Features

- Single instrument (one symbol)
- Limit orders only (BUY, GTC time-in-force)
- Price-time priority matching
- Partial fills
- Cancel open orders
- REST API: submit, cancel, query orders
- In-memory order book (TreeMap)
- PostgreSQL for persistence (synchronous writes — latency not a concern yet)
- No risk checks
- No market data streaming

### Architecture

- Single Spring Boot application (no separation of concerns yet)
- PostgreSQL for all state
- No Redis, no Kafka
- H2 for local testing

### Risks

- Matching algorithm bugs — correctness is critical to validate before adding complexity
- No persistence recovery — restart loses in-memory state

### Success Criteria

- 1,000 orders submitted
- Matching produces correct fills (manual verification against expected outcomes)
- Cancel works correctly
- Partial fills tracked correctly

---

## V1: Multi-Symbol, Market Orders, WebSocket

**Goal:** Functional exchange for multiple symbols with real-time market data.

### Features Added

- 10-50 symbols
- Market orders, DAY time-in-force
- IOC orders
- WebSocket market data (Level 1: best bid/ask, trade ticker)
- REST snapshot for Level 2 (order book depth)
- Basic pre-trade check: balance validation (simple, non-distributed)
- Redis for market data distribution
- Kafka for downstream consumers
- Order status WebSocket (execution reports)

### Architecture Evolution

- Separate Order Gateway service (Spring Boot) from Matching Engine service
- Matching Engine: one thread per symbol (basic, not Disruptor yet)
- Redis introduced for market data state and pub/sub
- Kafka for trade events, audit consumer
- PostgreSQL partitioned by date
- Docker Compose for local; Kubernetes for staging

### Risks

- Race conditions in multi-symbol matching if thread management is incorrect
- WebSocket connection management at scale (10,000 connections)
- Kafka consumer lag during market open burst

### Team Scaling

- Matching engine team (2 engineers)
- API/gateway team (2 engineers)
- Infrastructure/DevOps (1 engineer)

---

## V2: FIX Protocol, Full Order Types, Risk Controls

**Goal:** Production-ready for institutional clients with regulatory risk controls.

### Features Added

- FIX 4.2/4.4 gateway (QuickFIX/J)
- FOK (Fill-Or-Kill) orders
- Stop-limit orders
- GTC (Good-Till-Cancelled) across sessions
- Full pre-trade risk engine (buying power + position limits via Redis)
- Self-trade prevention
- Circuit breakers (LULD-style per symbol)
- Market open/close scheduling
- Pre-market auction
- Audit service (immutable event log, HMAC chain)
- Rate limiting per participant
- RBAC (roles: RETAIL_TRADER, INSTITUTIONAL, MARKET_MAKER)
- Idempotency enforcement (clientOrderId)

### Architecture Evolution

- LMAX Disruptor ring buffer replaces naive multi-thread design
- JVM tuning: G1GC or ZGC introduced
- Redis Cluster (3 shards) for risk data
- Dedicated audit DB (separate PostgreSQL instance or schema)
- Blue-green deployment for matching engine
- Latency regression testing in CI (Gatling)
- mTLS for FIX connections

### Risks

- Disruptor migration is complex — extensive testing before production
- FIX protocol edge cases (FIX has 30 years of specification quirks)
- Circuit breaker logic must be correct — false triggers cause trading halts

### Success Criteria

- p99 order latency < 10ms (gateway-to-ack)
- p99 matching latency < 2ms
- FIX acceptance test suite passes (50+ test cases)
- Circuit breaker triggers and resumes correctly in staging chaos test

---

## V3: Production Scale (500 Symbols, Compliance, DR)

**Goal:** Full exchange-grade system with regulatory compliance and disaster recovery.

### Features Added

- 500 symbols across multiple matching pods
- Symbol sharding (Matching Pod A/B)
- Kafka MirrorMaker 2 to DR region
- FINRA CAT reporting integration
- Settlement instructions (DTCC format)
- Post-market reconciliation (automated)
- Cold archival to S3 (Parquet)
- Athena queries on historical trade data
- Admin dashboard (trading halt controls, participant management)
- Surveillance alerts (wash trades, layering detection)
- Market maker obligations enforcement

### Architecture Evolution

- Matching pods (pod-per-group of symbols)
- Dedicated market data service cluster (WebSocket at 100K connections)
- DR warm standby in secondary region
- Full observability: OpenTelemetry, Jaeger, Prometheus, Grafana
- SLO burn rate alerting (Prometheus recording rules)
- Chaos engineering program (Chaos Monkey for pod failures)

### Risks

- DR failover is complex — requires coordinated cutover, tested quarterly
- Kafka MirrorMaker adds replication lag (~2-5 seconds cross-region)
- FINRA CAT reporting is operationally heavy — dedicated team needed
- Symbol rebalancing across pods (done during maintenance windows only)

### Infrastructure Evolution

| Component | MVP | V1 | V2 | V3 |
|-----------|-----|----|----|-----|
| Matching Pods | 1 pod | 2 pods | 2 pods | 5 pods |
| Order Gateway | 1 pod | 4 pods | 8 pods | 20 pods |
| PostgreSQL | Single | Primary + 1 replica | Primary + 2 replicas | Primary + 3 replicas + read pool |
| Redis | None | Single | Sentinel | Cluster (6 nodes) |
| Kafka | None | 3 brokers | 6 brokers | 6 brokers + MirrorMaker |

---

## Implementation Order Within Each Phase

For each phase, the implementation sequence:

1. **Domain model + tests** — Order, OrderBook, matching algorithm correctness tests
2. **In-memory matching** — pure Java, no I/O, 100% test coverage
3. **Persistence** — event journal, snapshot, recovery
4. **API layer** — REST endpoints, then WebSocket
5. **Infrastructure** — Redis, Kafka, external services
6. **Load testing** — validate latency and throughput targets
7. **Security hardening** — auth, mTLS, rate limiting
8. **Observability** — metrics, dashboards, alerts
9. **Runbooks** — operational procedures before going live

**Principle:** never go to production without: a working reconciliation script, a circuit breaker, and a tested recovery procedure.
