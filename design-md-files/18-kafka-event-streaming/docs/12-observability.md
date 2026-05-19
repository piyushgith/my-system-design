# 12 — Observability: Kafka-like Event Streaming System

---

## Objective

Define logging, metrics, tracing, alerting, and SLI/SLO strategy for a distributed event streaming platform. Unlike a web API, Kafka's observability challenge is tracking data flows across producers, brokers, and consumers — often owned by different teams.

---

## Observability Stack

| Layer | Tool | Purpose |
|---|---|---|
| Metrics | Prometheus + JMX Exporter | Broker, producer, consumer metrics |
| Dashboards | Grafana | Visualize throughput, lag, replication |
| Alerting | Prometheus Alertmanager | Pagerduty/Slack for critical conditions |
| Logging | ELK / OpenSearch | Broker logs, audit logs, error events |
| Distributed Tracing | OpenTelemetry + Jaeger | End-to-end message flow tracing |
| Consumer Lag | Kafka Burrow / kminion | Dedicated consumer lag monitoring |

---

## Key Metrics

### Broker-Level Metrics (via JMX → Prometheus)

**Throughput:**

| Metric | JMX Bean | Alert Threshold |
|---|---|---|
| `bytes_in_per_sec` | `kafka.server:type=BrokerTopicMetrics,name=BytesInPerSec` | > 80% NIC capacity |
| `bytes_out_per_sec` | `kafka.server:type=BrokerTopicMetrics,name=BytesOutPerSec` | > 80% NIC capacity |
| `messages_in_per_sec` | `kafka.server:type=BrokerTopicMetrics,name=MessagesInPerSec` | Baseline deviation |

**Replication Health:**

| Metric | JMX Bean | Alert Threshold |
|---|---|---|
| `under_replicated_partitions` | `kafka.server:type=ReplicaManager,name=UnderReplicatedPartitions` | > 0 for > 30s |
| `isr_shrinks_per_sec` | `kafka.server:type=ReplicaManager,name=IsrShrinksPerSec` | Any spike |
| `active_controller_count` | `kafka.controller:type=KafkaController,name=ActiveControllerCount` | != 1 → CRITICAL |
| `offline_partitions_count` | `kafka.controller:type=KafkaController,name=OfflinePartitionsCount` | > 0 → CRITICAL |
| `leader_election_rate` | `kafka.controller:type=ControllerStats,name=LeaderElectionRateAndTimeMs` | Sudden spike |

**Request Latency:**

| Metric | JMX Bean | Alert Threshold |
|---|---|---|
| `produce_request_latency_p99` | `kafka.network:type=RequestMetrics,name=TotalTimeMs,request=Produce` | > 100ms |
| `fetch_request_latency_p99` | `kafka.network:type=RequestMetrics,name=TotalTimeMs,request=FetchConsumer` | > 100ms |
| `request_queue_size` | `kafka.network:type=RequestChannel,name=RequestQueueSize` | > 400 (of 500 max) |

**Storage:**

| Metric | JMX Bean | Alert Threshold |
|---|---|---|
| `log_flush_rate_ms_p99` | `kafka.log:type=LogFlushStats,name=LogFlushRateAndTimeMs` | > 1000ms |
| `log_end_offset` | per partition | Used for lag calculation |
| Disk usage (node exporter) | `node_filesystem_avail_bytes` | < 20% free → page, < 10% → critical |

---

### Consumer Group Metrics (Burrow / kminion)

**Consumer Lag** is the most business-critical consumer metric:

```
consumer_group_lag{group="analytics-service", topic="orders", partition="0"} = 12345
consumer_group_lag_sum{group="analytics-service", topic="orders"} = 98765
```

**Lag calculation:**
```
lag = log_end_offset(partition) - last_committed_offset(group, partition)
```

**Alert:**
```yaml
- alert: ConsumerGroupHighLag
  expr: kafka_consumer_group_lag > 100000
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "Consumer group {{ $labels.group }} lag > 100K on {{ $labels.topic }}"
```

**Lag Growth Rate** (more actionable than absolute lag):
```
lag_growth_rate = rate(kafka_consumer_group_lag[5m])
Alert if lag_growth_rate > 1000/sec for > 2min (lag growing faster than consuming)
```

---

### Producer Metrics (Client-Side)

| Metric | SDK Name | Meaning |
|---|---|---|
| `record-send-rate` | `kafka.producer:type=producer-metrics,name=record-send-rate` | Message production throughput |
| `record-error-rate` | `kafka.producer:type=producer-metrics,name=record-error-rate` | Failed sends (alert if > 0) |
| `batch-size-avg` | `...name=batch-size-avg` | Avg batch efficiency |
| `record-queue-time-avg` | `...name=record-queue-time-avg` | Time in RecordAccumulator buffer |
| `request-latency-avg` | `...name=request-latency-avg` | End-to-end produce latency |
| `buffer-exhausted-rate` | `...name=buffer-exhausted-rate` | Producer backpressure events |

---

## Distributed Tracing (OpenTelemetry)

Tracing end-to-end message flow is non-trivial — producer and consumer are separate processes, often owned by different teams.

### Trace Propagation via Headers

```
Producer side:
  span = tracer.start("kafka.produce", parent=currentSpan)
  record.headers.add("traceparent", W3C_trace_context)
  record.headers.add("tracestate", optional)
  producer.send(record)
  span.end()

Consumer side:
  traceContext = W3C.extract(record.headers["traceparent"])
  span = tracer.start("kafka.consume", parent=traceContext)
  process(record)
  span.end()
```

### Trace Events Captured

| Event | Attributes |
|---|---|
| `kafka.produce` | topic, partition, key_hash, batch_size, acks_mode |
| `kafka.broker.append` | topic, partition, offset, compression |
| `kafka.broker.replicate` | partition, follower_id, replication_latency |
| `kafka.consume` | topic, partition, offset, consumer_group, lag |
| `kafka.offset_commit` | topic, partition, offset, consumer_group |

**Sampling strategy:** 100% for errors and high-lag events; 1% for normal traffic (reduces overhead).

---

## Logging Strategy

### Broker Log Categories

| Logger | Purpose | Level |
|---|---|---|
| `kafka.server.KafkaServer` | Startup/shutdown events | INFO |
| `kafka.controller.KafkaController` | Leader election, partition changes | INFO |
| `kafka.log.LogCleaner` | Compaction progress, errors | INFO |
| `kafka.log.Log` | Segment rolling, deletion | DEBUG (prod: INFO) |
| `kafka.network.RequestChannel` | Request queue saturation | WARN |
| `kafka.authorizer.AclAuthorizer` | ACL denials | WARN (all denials) |
| `state.change.logger` | Leader/follower state transitions | INFO |

### Structured Log Format

```json
{
  "timestamp": "2024-01-15T10:30:00.123Z",
  "level": "WARN",
  "logger": "kafka.controller.KafkaController",
  "message": "Broker 2 failed to respond within timeout",
  "context": {
    "brokerId": 2,
    "timeoutMs": 30000,
    "epoch": 5,
    "correlationId": "ctrl-123"
  }
}
```

### Correlation IDs
Every client request carries `correlationId` (client-generated int32). Broker logs this ID. Enables tracing a specific produce/fetch request through broker logs.

```
# Find all log lines for correlationId 12345
grep '"correlationId":12345' /var/log/kafka/server.log
```

---

## Dashboards (Grafana)

### Dashboard 1: Cluster Health Overview
- Cluster-wide `bytes_in_per_sec` and `bytes_out_per_sec`
- `offline_partitions_count` (should always be 0)
- `under_replicated_partitions` (should always be 0)
- `active_controller_count` (should always be 1)
- Per-broker disk usage %

### Dashboard 2: Topic/Producer Performance
- Per-topic: messages_in_per_sec, bytes_in_per_sec
- Produce request latency heatmap (p50/p95/p99)
- Request queue depth over time
- Producer error rate per topic
- Batch size distribution

### Dashboard 3: Consumer Group Lag
- Consumer lag per group + per partition (heatmap)
- Lag growth rate (slope indicates falling behind)
- Offset commit rate
- Consumer rebalance events

### Dashboard 4: Replication Health
- ISR size per partition (any < replication_factor = under-replicated)
- Leader election events
- Replication latency (follower lag in ms)
- Preferred replica election status

---

## SLI / SLO / SLA

### SLIs (Service Level Indicators)

| SLI | Measurement |
|---|---|
| Produce availability | % of produce requests succeeding (non-5xx) over 5min window |
| Produce latency | p99 latency of ProduceRequest (acks=all) |
| Consumer lag | p99 consumer group lag across all production groups |
| Replication health | % of partitions with full ISR |

### SLOs (Service Level Objectives)

| SLO | Target |
|---|---|
| Produce availability | ≥ 99.95% |
| Produce latency (acks=all, p99) | < 20ms |
| Consumer lag (p95 of groups) | < 10,000 messages |
| Under-replicated partitions | 0 for > 99.9% of time |
| Partition leader election time | < 30 seconds |

### SLO Burn Rate Alerting

```yaml
# Fast burn (5% budget in 1 hour)
- alert: ProduceAvailabilityFastBurn
  expr: |
    (1 - rate(kafka_produce_requests_success[5m]) / rate(kafka_produce_requests_total[5m]))
    > (1 - 0.9995) * 14.4
  for: 2m
  annotations:
    severity: critical

# Slow burn (10% budget in 6 hours)
- alert: ProduceAvailabilitySlowBurn
  expr: |
    (1 - rate(kafka_produce_requests_success[30m]) / rate(kafka_produce_requests_total[30m]))
    > (1 - 0.9995) * 6
  for: 15m
  annotations:
    severity: warning
```

---

## Operational Runbooks

### Runbook: Under-Replicated Partitions Alert

1. Check which partitions: `kafka-topics.sh --describe --under-replicated-partitions`
2. Identify lagging broker: `kafka-replica-verification.sh --topics-include <topic>`
3. Check broker logs for disk errors, GC pauses, network issues
4. If broker is alive but lagging: check disk I/O (`iostat`), heap usage (`jcmd`)
5. If broker is down: wait for restart + replication catch-up
6. If ISR shrunk to 1 and writes failing: temporary — `min.insync.replicas` decrease (emergency only)

### Runbook: Consumer Group Lag Growing

1. Identify which group/topic: Burrow dashboard
2. Check consumer CPU and GC metrics
3. Check if consumer is making progress (offset advancing despite lag)
4. If offset not advancing: consumer stuck — check consumer logs for processing errors
5. If advancing but lag growing: processing rate < produce rate → scale consumers
6. Max consumers = partition count for that topic

### Runbook: No Active Controller

```
kafka.controller:type=KafkaController,name=ActiveControllerCount = 0
```

1. Check all controller nodes: `kafka-metadata-quorum.sh --describe`
2. Look for Raft election issues in controller logs
3. Restart one controller node if all stuck
4. Check ZooKeeper connectivity (if ZK mode)
5. Brokers continue data plane operations — focus on controller recovery

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| JMX metrics | Kafka native, rich metrics | JMX scraping adds overhead; complex bean naming |
| Dedicated lag monitor (Burrow) | Specialized for consumer lag semantics (understands offset commit patterns) | Extra service to maintain |
| Structured logging | Machine-parseable, easy to filter | More verbose than plain text |
| Trace header in Kafka records | Full E2E tracing | ~100 bytes overhead per record; producers must implement header injection |

---

## Interview Discussion Points

- **What is the most important Kafka metric?** `under_replicated_partitions` — if this is > 0, data durability is compromised. Consumer lag is second — indicates processing pipeline is falling behind
- **How do you distinguish "consumer is processing slowly" from "producer is producing faster"?** Compare lag growth rate (increasing = falling behind) vs absolute lag. Also compare produce rate to consume rate. If produce rate doubled → lag growth expected, not a consumer problem
- **How do you trace a specific message through the system?** Embed trace context in message headers at producer. Consumer extracts and creates child span. Requires both teams to instrument. Without this: correlation is only possible via timestamps + partition/offset
- **What causes produce latency spikes?** Main causes: ISR shrinkage (waiting for slower replicas), JVM GC pause on leader, disk I/O saturation, network latency to replicas. Identify via produce request latency breakdown (`LocalTimeMs`, `RemoteTimeMs`, `ThrottleTimeMs`)
- **How do you detect a slow consumer before it causes problems?** Lag growth rate > threshold for sustained period. Better: compare `consume_rate` to `produce_rate` per partition. If produce_rate > consume_rate for > 10 minutes, consumer will never catch up without scaling
