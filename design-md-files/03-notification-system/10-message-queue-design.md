# 10 — Message Queue Design: Notification System

---

## Objective

Define the complete Kafka infrastructure design: broker configuration, topic design, consumer group architecture, delivery guarantees, retry topology, DLQ behavior, and the operational discipline required to run Kafka reliably in a notification platform.

---

## Why Kafka and Not Alternatives

| Alternative | Why Rejected |
|-------------|-------------|
| **RabbitMQ** | No native message replay; limited partition-based parallelism; harder to scale beyond single node without complex federation |
| **AWS SQS** | No message replay after deletion; no consumer group model; 15-minute max visibility timeout is too short for campaign fanout; no ordering guarantee |
| **Redis Streams** | Excellent for lower volume but lacks: multi-datacenter replication, long-term retention for replay, mature consumer group rebalancing at 50K msg/sec |
| **AWS SNS + SQS** | Missing: centralized retry orchestration, exactly-once semantics, replay capability for recovery scenarios |

**Kafka wins because:** at 50,000 messages/sec peak with 7-day replay capability, per-channel isolation, and the need for retry topology (separate retry topics), Kafka is the only mature choice.

---

## Kafka Cluster Architecture

### Cluster Configuration

```
Brokers:      9 nodes (3 per availability zone, 3 AZs)
Replication:  RF = 3 (every partition replicated to 3 brokers)
Min ISR:      2 (message considered durable when written to 2 replicas)
Acks:         acks=all on producers (strongest durability guarantee)
ZooKeeper:    Replaced by KRaft mode (Kafka 3.x)
```

### Why 9 Brokers?

At 50,000 msg/sec × avg 2 KB payload = 100 MB/sec ingest. With replication factor 3, each byte is written 3 times = 300 MB/sec total cluster throughput. A single modern broker node can sustain ~500 MB/sec I/O. 9 brokers gives ample headroom and tolerates 1 full AZ failure.

---

## Complete Topic Registry

### Primary Topics

| Topic | Partitions | RF | Retention | Compression | Purpose |
|-------|-----------|----|-----------|-----------|---------| 
| `notification.request.submitted` | 30 | 3 | 7 days | LZ4 | Main intake for single notifications |
| `notification.batch.submitted` | 6 | 3 | 7 days | LZ4 | Batch campaign intake |
| `email.dispatch.requested` | 60 | 3 | 3 days | LZ4 | Email delivery jobs |
| `sms.dispatch.requested` | 30 | 3 | 3 days | LZ4 | SMS delivery jobs |
| `push.dispatch.requested` | 60 | 3 | 3 days | LZ4 | Push delivery jobs |
| `inapp.dispatch.requested` | 20 | 3 | 3 days | LZ4 | In-app delivery jobs |
| `delivery.attempt.completed` | 60 | 3 | 7 days | LZ4 | All delivery outcomes |
| `user.preference.updated` | 10 | 3 | 3 days | Snappy | Preference change events |

### Retry Topics (Delayed Processing)

| Topic | Partitions | RF | Retention | Delay (simulated) |
|-------|-----------|----|-----------|--------------------|
| `notification.retry.email.1m` | 30 | 3 | 1 day | 1 minute retry |
| `notification.retry.email.5m` | 30 | 3 | 1 day | 5 minutes |
| `notification.retry.email.15m` | 20 | 3 | 1 day | 15 minutes |
| `notification.retry.email.60m` | 10 | 3 | 2 days | 60 minutes |
| `notification.retry.email.4h` | 10 | 3 | 7 days | 4 hours |
| `notification.retry.sms.*` | Similar structure | | | |
| `notification.retry.push.*` | Similar structure | | | |

### DLQ Topics

| Topic | Partitions | RF | Retention | Purpose |
|-------|-----------|----|-----------|---------| 
| `notification.dlq.email` | 10 | 3 | 30 days | Email exhausted retries |
| `notification.dlq.sms` | 10 | 3 | 30 days | SMS exhausted retries |
| `notification.dlq.push` | 10 | 3 | 30 days | Push exhausted retries |
| `notification.dlq.inapp` | 5 | 3 | 30 days | In-app exhausted retries |

---

## Delivery Guarantees

### Producer Side: `acks=all`

```
Producer config:
  acks = all          → wait for all ISR replicas to acknowledge
  retries = Integer.MAX_VALUE → retry indefinitely on broker failure
  max.in.flight.requests.per.connection = 1  → prevents out-of-order if retry needed
  enable.idempotence = true    → exactly-once producer semantics (no duplicate from producer)
```

**Why `max.in.flight = 1` when idempotence is enabled?**
Kafka's idempotent producer requires `max.in.flight <= 5` for ordering guarantees. Setting it to 1 with partition key = `user_id` ensures strict ordering per user in `notification.request.submitted`.

### Consumer Side: At-Least-Once with Application Idempotency

```
Consumer config:
  enable.auto.commit = false    → manual offset commit
  max.poll.records = 100        → batch processing
  isolation.level = read_committed  → only read committed transactions
```

**Offset Commit Strategy:**
1. Consumer polls batch of 100 messages
2. Processes all 100 (render template + call provider)
3. Commits offsets only after all 100 are processed or sent to retry topics
4. If pod crashes after processing but before commit: Kafka re-delivers the batch
5. Idempotency at application layer (Redis `SET NX {attempt_id}`) prevents double delivery

**Why not exactly-once (Kafka transactions)?**
Exactly-once with Kafka transactions requires the consumer to publish output messages and commit offsets in a single transaction. This is complex and adds latency. For a notification system, at-least-once + application idempotency is the standard industry approach. Exactly-once is reserved for financial transaction systems.

---

## Retry Topology in Detail

### Why Multiple Retry Topics Instead of Kafka's `retry.backoff.ms`?

Kafka's native retry mechanism re-processes messages immediately without the ability to implement per-attempt delay at different intervals. A 60-minute retry delay with a single retry topic would cause the consumer to poll, see undue messages, skip them, and commit nothing — wasting poll cycles.

The industry pattern for variable-delay retries in Kafka is **separate delay topics per attempt tier**.

### Retry Flow Architecture

```
email.dispatch.requested
    │
    ├── SUCCESS → delivery.attempt.completed {DELIVERED}
    │
    └── FAILURE (retriable, attempt 1)
            │
            └──> notification.retry.email.1m
                        │ (1 minute passes)
                        └──> Email Dispatcher (re-consumes from retry topic)
                                │
                                ├── SUCCESS → delivery.attempt.completed {DELIVERED}
                                └── FAILURE (attempt 2) → notification.retry.email.5m
                                        │
                                        └── ... (repeat up to attempt 5)
                                                │
                                                └── FAILURE (attempt 6) → notification.dlq.email
```

### Delay Simulation for Retry Topics

Kafka cannot delay message delivery natively. The retry consumer implements the delay check:

```
Retry Consumer Logic:
  Poll from notification.retry.email.1m
  For each message:
    If (now - message.timestamp) >= 1 minute:
      Re-publish to email.dispatch.requested
    Else:
      Pause consumer for (1_minute - elapsed) duration
      Do NOT commit offset
```

**Alternative approach:** Use Redis sorted set for delay scheduling (see Event Flow document). The Kafka retry topic approach is simpler operationally but wastes consumer cycles during the delay window.

---

## Consumer Group Configuration

| Consumer Group | Topic | Instances | Partition Assignment |
|---------------|-------|-----------|---------------------|
| `fanout-cg` | `notification.request.submitted` | 30 | Sticky (reduces rebalance churn) |
| `campaign-fanout-cg` | `notification.batch.submitted` | 6 | Round robin |
| `email-dispatch-cg` | `email.dispatch.requested` + `notification.retry.email.*` | 60 | Sticky |
| `sms-dispatch-cg` | `sms.dispatch.requested` + `notification.retry.sms.*` | 30 | Sticky |
| `push-dispatch-cg` | `push.dispatch.requested` + `notification.retry.push.*` | 60 | Sticky |
| `inapp-dispatch-cg` | `inapp.dispatch.requested` | 20 | Round robin |
| `delivery-log-cg` | `delivery.attempt.completed` | 60 | Sticky |
| `retry-orchestrator-cg` | `delivery.attempt.completed` | 20 | Sticky |
| `dlq-inspector-cg` | `notification.dlq.*` | 5 | Manual |

### Sticky Partition Assignment
Sticky assignment keeps consumers attached to the same partitions across rebalances (unless necessary to rebalance). For dispatchers, this is critical: each dispatcher builds a local cache of provider rate-limit tokens. Re-assignment would lose this state. Sticky assignment minimizes cache thrashing.

---

## Consumer Lag Monitoring and Alerting

| Alert | Threshold | Severity | Action |
|-------|-----------|---------|--------|
| `email.dispatch.requested` lag > 10,000 | Sustained 5 min | P2 | Scale up Email Dispatcher pods |
| `sms.dispatch.requested` lag > 1,000 | Sustained 5 min | P2 | Scale up SMS Dispatcher pods |
| `notification.request.submitted` lag > 5,000 | Sustained 2 min | P1 | Scale up Fanout Service + investigate |
| Any DLQ topic depth > 1,000 | Immediate | P2 | Alert ops team |
| Any DLQ topic depth > 10,000 | Immediate | P1 | Major incident — likely provider outage |
| Consumer group has dead members (lag growing, no progress) | 5 min | P1 | Pod crash — trigger Kubernetes restart |

Monitoring via **KEDA** (Kubernetes Event-Driven Autoscaling):
- KEDA scales dispatcher pods based on `kafka_consumergroup_lag` metric
- Scale-up trigger: lag > 5,000
- Scale-down trigger: lag < 500 sustained for 5 minutes
- Min replicas: 5 (never scale to 0 — notification delivery must be always-on)
- Max replicas: 60 (per-channel)

---

## Schema Registry

- **Confluent Schema Registry** deployed alongside Kafka
- All topics use Avro schemas registered in the Schema Registry
- Compatibility setting: `BACKWARD` — new schema versions must be readable by old consumers
- Producers validate schema before publishing (avro-serializer rejects invalid messages)
- Schema evolution: add optional fields freely; removing or renaming fields requires a new major schema version

---

## Kafka Security

| Layer | Configuration |
|-------|-------------|
| Authentication | SASL/SCRAM-SHA-512 per client |
| Authorization | Kafka ACLs per topic per service |
| Encryption in transit | TLS 1.3 on all broker-client connections |
| Encryption at rest | Disk-level encryption on broker nodes |
| ACL Examples | Email Dispatcher: READ on `email.dispatch.requested`, WRITE on `delivery.attempt.completed` |

Producers and consumers authenticate with Kafka using service-specific credentials managed in Vault. Each service can only access the topics it legitimately needs.

---

## Operational Runbooks

### Runbook: Email Provider Outage

1. Circuit breaker opens on Email Dispatcher (>20% 5xx in 60s)
2. Email Dispatcher stops consuming `email.dispatch.requested`
3. Messages accumulate in topic (3-day retention gives recovery window)
4. Ops alert: P1 incident
5. On provider recovery: Circuit breaker resets, consumers resume
6. Kafka replay catches up (KEDA scales to 60 dispatcher instances for faster drain)

### Runbook: Kafka Broker Failure (Single Node)

1. KRaft detects leader unavailability
2. New leader elected within ~10–30 seconds (ISR contains 2 other replicas)
3. `min.insync.replicas = 2` means at most 1 partition becomes temporarily unavailable
4. Producers: Kafka client retries internally until leader election completes
5. Consumers: brief pause, then resume from last committed offset

### Runbook: Consumer Group Rebalance During High Load

1. Pod restart triggers consumer group rebalance
2. Rebalance duration with 60-instance group: ~2–5 seconds
3. During rebalance: all consumers in the group pause consumption
4. Risk: 2–5 second spike in unprocessed messages
5. Mitigation: Incremental Cooperative Rebalancing (Kafka 2.4+ default) — only affected partitions are reassigned, not all; other consumers continue

---

## Interview Discussion Points

- Why set `max.in.flight.requests.per.connection = 1` for the Outbox relay publisher even when idempotence is enabled?
- What is the risk of a single slow consumer in a consumer group (e.g., one email dispatcher pod hitting SendGrid timeouts)?
- How does `read_committed` isolation level on consumers interact with the Outbox pattern's transactional writes?
- Why does Kafka's partition-per-consumer model naturally provide back-pressure, and what happens when you have more partitions than consumers?
- When would you consider moving from Kafka retry topics to a Redis sorted set for retry scheduling?
- What is "unclean leader election" in Kafka and why is it disabled by default in production?
