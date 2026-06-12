# 17 — Testing Strategy: Chat Application

---

## Objective

Verify the guarantees that make a chat system trustworthy: **in-order delivery within a conversation**, **zero loss of an acknowledged message**, correct offline sync, and multi-device consistency. Tests validate behavior and the failure scenarios in `11-failure-scenarios.md`, not transport plumbing.

---

## Test Pyramid

| Layer | Share | Scope | Tooling |
|---|---|---|---|
| Unit | ~60% | Sequence-number assignment, Snowflake ID ordering, presence TTL logic, fan-out recipient expansion | JUnit 5 |
| Integration | ~25% | Cassandra + Kafka + Redis (Pub/Sub + registry) via Testcontainers | Testcontainers, Spring Boot Test |
| Contract | ~5% | WebSocket frame schema, gRPC message-service contract | Pact, protobuf compatibility |
| E2E + Load | ~10% | Two-client delivery, offline sync, multi-device; connection-scale load | k6 (WebSocket), Gatling |

---

## What Each Layer Must Prove

### Unit
- Per-conversation `sequence_num` is strictly monotonic; clients order by seq, not wall clock.
- Snowflake IDs are globally unique and time-ordered across simulated server IDs.
- Presence: key refresh → ONLINE; TTL expiry without refresh → OFFLINE (no explicit disconnect needed).
- Group fan-out expands to the exact recipient set; sender not double-counted.

### Integration
- 1:1 delivery (both online): message persisted to Cassandra **before** Kafka publish; recipient receives via Redis Pub/Sub.
- Offline sync: recipient offline → push queued; on reconnect, `seq > lastSyncSeq` returns exactly the missed messages, in order.
- Multi-device: a message sent on device A appears on device B within the 2s target.
- Connection registry: userId → serverId mapping; dead-server entry routes to Notification instead.

### Contract
- WebSocket frame and gRPC `SendMessage` schemas locked; protobuf changes checked for back-compat.

---

## Load & Performance Testing (validates the SLOs)

Design claims: **p99 delivery < 500ms (online)**, **1M msg/sec peak**, **50M concurrent WebSocket connections (~50K/server)**, **history first page p99 < 200ms**.

| Scenario | Target | Pass criteria |
|---|---|---|
| 1:1 delivery, both online | 1M msg/sec aggregate | p99 < 500ms end-to-end |
| Connection scale per server | 50K conns/JVM | Stable memory, heartbeat overhead bounded |
| Group fan-out (1,000 members) | sustained | Sender ACK not gated on all deliveries |
| Offline sync burst (reconnect storm) | 100K reconnects | Sync queries bounded; Cassandra not overwhelmed |
| History page load | — | p99 < 200ms |

Connection-scale tests must run against real WebSocket servers — connection count is memory-bound and cannot be simulated at the unit level.

---

## Chaos / Failure-Injection (validates 11-failure-scenarios.md)

- **WebSocket server crash** → registry entries expire; reconnecting clients re-register elsewhere; in-flight messages recoverable from Kafka/Cassandra.
- **Redis Pub/Sub partition** → live delivery degrades but Kafka durability preserves messages for sync.
- **Cassandra node loss** → quorum writes continue; no acknowledged message lost.
- **Kafka lag** → offline/notification path lags but never drops; ordering preserved per partition (keyed by conversation).
- **Clock skew across servers** → ordering still correct because it relies on seq_num, not timestamps.

---

## CI/CD Gates

| Stage | Gate |
|---|---|
| PR | Unit + integration pass; changed-line coverage ≥ 80% |
| Merge | Contract + protobuf compat checks |
| Pre-prod | Two-client + offline-sync + multi-device E2E; connection-scale soak |
| Post-deploy | Synthetic send/receive canary; delivery-latency and lag alerts |

---

## Interview Discussion Points

- **How do you test message ordering deterministically?** Inject concurrent sends, assert recipients render strictly by seq_num regardless of arrival order.
- **How do you test 50M connections without 50M clients?** Validate per-server limit (50K) under soak, then extrapolate by server count; the unit is one saturated server.
- **What proves "no acknowledged message lost"?** Kill the message service immediately after Cassandra ACK but before Kafka publish — recovery must still deliver on sync.
