# 04 — API Design: Kafka-like Event Streaming System

---

## Objective

Define the producer, consumer, admin, and schema registry APIs — covering wire protocol design for the broker-facing binary API and REST API for admin and schema management. Address versioning, idempotency, error handling, and backward compatibility.

---

## API Layers

| Layer | Protocol | Consumers |
|---|---|---|
| Kafka Wire Protocol (Binary) | Custom TCP binary | Producer/Consumer clients |
| Admin REST API | HTTP/JSON | Operators, tooling |
| Schema Registry REST API | HTTP/JSON | Producers, consumers, tooling |
| Metrics/Health API | HTTP/JSON (Prometheus) | Monitoring systems |

---

## Kafka Wire Protocol (Binary API)

### Protocol Structure

Every request/response on the Kafka wire protocol follows:

```
Request:
  [4 bytes] message_length
  [2 bytes] api_key
  [2 bytes] api_version
  [4 bytes] correlation_id
  [2 bytes] client_id (nullable string length + bytes)
  [...    ] request body (api-specific)

Response:
  [4 bytes] message_length
  [4 bytes] correlation_id (matches request)
  [...    ] response body
```

**Why custom binary over HTTP?** Binary protocol enables:
- Tight batching without HTTP framing overhead per message
- Zero-copy transfer via `sendfile` (HTTP headers break zero-copy)
- Flexible multi-request pipelining
- Compact encoding of primitive types

---

### Core API Operations

#### Produce API (ApiKey = 0)

**Request:**
```
ProduceRequest {
  transactionalId: nullable string
  acks: int16                    // 0=fire-forget, 1=leader, -1=all
  timeoutMs: int32
  topicData: [
    {
      name: string
      partitionData: [
        {
          index: int32
          records: RecordBatch   // compressed batch
        }
      ]
    }
  ]
}
```

**Response:**
```
ProduceResponse {
  responses: [
    {
      name: string
      partitionResponses: [
        {
          index: int32
          errorCode: int16
          baseOffset: int64      // assigned offset of first record in batch
          logAppendTimeMs: int64
          logStartOffset: int64
        }
      ]
    }
  ]
  throttleTimeMs: int32
}
```

**Idempotency:** With `enable.idempotence=true`, each batch includes `(producerId, producerEpoch, sequence)`. Broker deduplicates within a 5-batch window per `(producerId, partition)`. Prevents duplicate writes from producer retries.

**acks Semantics:**

| acks Value | Durability | Latency |
|---|---|---|
| 0 | No guarantee (fire-and-forget) | Lowest |
| 1 | Written to leader only | Medium |
| -1 (all) | Written to all ISR members | Highest |

---

#### Fetch API (ApiKey = 1)

**Request:**
```
FetchRequest {
  replicaId: int32              // -1 for consumers; broker ID for replica fetch
  maxWaitMs: int32              // long-poll timeout
  minBytes: int32               // wait until this many bytes available
  maxBytes: int32               // total response size limit
  isolationLevel: int8          // 0=READ_UNCOMMITTED, 1=READ_COMMITTED
  sessionId: int32              // fetch session for incremental fetch
  sessionEpoch: int32
  topics: [
    {
      topic: string
      partitions: [
        {
          partition: int32
          fetchOffset: int64
          partitionMaxBytes: int32
        }
      ]
    }
  ]
}
```

**Response:**
```
FetchResponse {
  throttleTimeMs: int32
  errorCode: int16
  sessionId: int32
  responses: [
    {
      topic: string
      partitions: [
        {
          partition: int32
          errorCode: int16
          highWatermark: int64   // consumer can read up to here
          lastStableOffset: int64
          records: RecordSet     // compressed batches
        }
      ]
    }
  ]
}
```

**Long-polling:** Consumer sets `maxWaitMs=500` — broker holds connection open up to 500ms waiting for `minBytes` of data. Eliminates busy-polling for low-volume topics. Reduces CPU load significantly.

**Fetch Sessions:** Incremental fetch protocol (KIP-227) — consumer registers interest once; subsequent fetches only include changed partitions. Reduces metadata overhead for consumers tracking many partitions.

---

#### Metadata API (ApiKey = 3)

```
MetadataRequest {
  topics: [string]              // null = all topics
  allowAutoTopicCreation: bool
  includeClusterAuthorizedOperations: bool
}

MetadataResponse {
  throttleTimeMs: int32
  brokers: [
    { nodeId, host, port, rack }
  ]
  clusterId: string
  controllerId: int32
  topics: [
    {
      errorCode: int16
      name: string
      isInternal: bool
      partitions: [
        { errorCode, partitionIndex, leaderId, leaderEpoch,
          replicaNodes, isrNodes, offlineReplicas }
      ]
    }
  ]
}
```

**Client-side caching:** Clients cache metadata and refresh on `LEADER_NOT_AVAILABLE` or `NOT_LEADER_OR_FOLLOWER` errors. Metadata refresh is not done per-request — reduces controller load.

---

#### Consumer Group APIs

**JoinGroup (ApiKey = 11):**
```
JoinGroupRequest {
  groupId: string
  sessionTimeoutMs: int32
  rebalanceTimeoutMs: int32
  memberId: string              // empty string for new member
  groupInstanceId: nullable string  // static membership
  protocolType: string          // "consumer"
  protocols: [
    { name: string, metadata: bytes }  // assignment strategies + subscription
  ]
}

JoinGroupResponse {
  throttleTimeMs, errorCode, generationId, protocolType, protocolName,
  leader: string,               // memberId of leader
  memberId: string,             // assigned memberId
  members: [                    // populated only for leader
    { memberId, groupInstanceId, metadata }
  ]
}
```

**SyncGroup (ApiKey = 14):**
Leader sends assignment; all members receive their partition list.

**OffsetCommit (ApiKey = 8):**
Atomic commit of offsets for all partitions in one request. Returns per-partition error codes.

**Heartbeat (ApiKey = 12):**
Consumer sends heartbeat every `heartbeat.interval.ms`. Missing heartbeats beyond `session.timeout.ms` triggers rebalance.

---

### API Versioning Strategy

Every API operation has an independent version number. Client negotiates max supported version via `ApiVersions` request on connection setup:

```
ApiVersionsRequest { clientSoftwareName, clientSoftwareVersion }

ApiVersionsResponse {
  errorCode: int16
  apiKeys: [
    { apiKey: int16, minVersion: int16, maxVersion: int16 }
  ]
}
```

**Rules:**
- New fields added with `NULLABLE_DEFAULT` or `DEFAULT` — older clients ignore unknown fields
- Old fields never removed until min supported version advances past the version that removed it
- Breaking changes bump major version (e.g., v3 → v4); old version supported for ~2 major releases

---

## Admin REST API

Base path: `http://broker:8080/v1`

### Topic Management

| Method | Path | Description |
|---|---|---|
| `POST` | `/topics` | Create topic |
| `GET` | `/topics` | List all topics |
| `GET` | `/topics/{name}` | Get topic config + partition info |
| `PATCH` | `/topics/{name}/config` | Update topic config |
| `DELETE` | `/topics/{name}` | Delete topic |
| `POST` | `/topics/{name}/partitions` | Increase partition count |

**Create Topic Request:**
```json
{
  "name": "user-events",
  "partitionCount": 12,
  "replicationFactor": 3,
  "config": {
    "retention.ms": 604800000,
    "cleanup.policy": "delete",
    "min.insync.replicas": 2
  }
}
```

### Consumer Group Management

| Method | Path | Description |
|---|---|---|
| `GET` | `/consumer-groups` | List all groups |
| `GET` | `/consumer-groups/{groupId}` | Group state + assignments |
| `GET` | `/consumer-groups/{groupId}/offsets` | Current committed offsets |
| `POST` | `/consumer-groups/{groupId}/reset-offsets` | Reset to earliest/latest/specific |
| `DELETE` | `/consumer-groups/{groupId}` | Delete empty group |

### Broker Management

| Method | Path | Description |
|---|---|---|
| `GET` | `/brokers` | List brokers + state |
| `GET` | `/brokers/{id}/partitions` | Partitions on broker |
| `POST` | `/partition-reassignment` | Trigger partition rebalance |
| `GET` | `/partition-reassignment` | Check ongoing reassignments |

---

## Schema Registry REST API

Base: `http://schema-registry:8081`

| Method | Path | Description |
|---|---|---|
| `GET` | `/subjects` | List all subjects |
| `POST` | `/subjects/{subject}/versions` | Register new schema |
| `GET` | `/subjects/{subject}/versions/{version}` | Get specific schema |
| `GET` | `/schemas/ids/{id}` | Get schema by ID (hot path) |
| `POST` | `/compatibility/subjects/{subject}/versions/{version}` | Test compatibility |
| `PUT` | `/config/{subject}` | Set compatibility level |

**Schema Registration:**
```json
POST /subjects/user-events-value/versions
{
  "schema": "{\"type\":\"record\",\"name\":\"UserEvent\",...}",
  "schemaType": "AVRO"
}

Response:
{ "id": 42 }
```

**Producer integration:** Producer sends `\x00` + `[4 bytes schema_id]` + `[avro bytes]`. Consumer fetches schema by ID from registry on first encounter, caches in-process. Schema registry not in hot consumer path.

---

## Error Handling Standards

### Wire Protocol Error Codes

| Code | Name | Meaning | Retriable? |
|---|---|---|---|
| 0 | NONE | Success | — |
| 3 | UNKNOWN_TOPIC_OR_PARTITION | Topic/partition doesn't exist | No |
| 5 | LEADER_NOT_AVAILABLE | Leader election in progress | Yes (backoff) |
| 6 | NOT_LEADER_OR_FOLLOWER | Stale metadata — wrong broker | Yes (refresh metadata) |
| 22 | INVALID_MSG_SIZE | Message exceeds max.message.bytes | No |
| 25 | OFFSET_OUT_OF_RANGE | Requested offset not available | No (reset policy) |
| 29 | DUPLICATE_SEQUENCE_NUMBER | Idempotent producer duplicate | No (safe to ignore) |
| 47 | KAFKA_STORAGE_ERROR | Disk error on broker | Yes (different broker) |

### REST API Errors

```json
{
  "errorCode": 40401,
  "message": "Subject 'user-events-value' not found"
}
```

HTTP status codes: 200, 201, 400, 401, 403, 404, 409 (conflict/compatibility), 422 (invalid schema), 500

---

## Pagination

Admin REST APIs use cursor-based pagination for large result sets:

```
GET /topics?limit=100&cursor=<opaque_token>

Response:
{
  "topics": [...],
  "nextCursor": "base64encodedtoken",
  "hasMore": true
}
```

Topic/partition lists are not sorted randomly — cursor encodes last-seen name alphabetically.

---

## Rate Limiting & Quotas

**Per-client quotas** (enforced by broker, configured in `__cluster_metadata`):

| Quota Type | Default | Enforcement |
|---|---|---|
| Producer byte rate | unlimited | Token bucket per clientId/user |
| Consumer byte rate | unlimited | Token bucket per groupId/user |
| Request rate | unlimited | Token bucket per connection |

When quota exceeded: broker sets `throttleTimeMs` in response. Client must wait before next request. No connection drop — graceful throttling.

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Binary wire protocol vs REST | 10–100x throughput advantage; zero-copy support | Harder to debug, no curl-friendly interface |
| Long-polling fetch | Eliminates busy-poll; reduces broker CPU | Added connection hold complexity |
| Per-client API version negotiation | Backward compatibility without flag days | Client version matrix complexity |
| Schema ID in message header vs full schema | Compact messages; schema reuse across records | Consumer must fetch schema on first encounter |

---

## Interview Discussion Points

- **Why not use gRPC for the broker protocol?** gRPC adds HTTP/2 framing overhead; zero-copy sendfile requires direct socket control. Binary TCP protocol avoids both. gRPC valid for admin APIs
- **How does a consumer know when its fetch is stale?** `highWatermark` in FetchResponse — consumer compares with its last fetched offset. Also `leaderEpoch` per partition detects stale leader info
- **What is isolation level in Fetch?** `READ_UNCOMMITTED` returns all messages including transactional ones not yet committed. `READ_COMMITTED` only returns messages from committed transactions + non-transactional messages
- **How does backpressure work with long-polling?** Consumer controls fetch rate by not sending next fetch until processing is done. Broker queue depth reflects this naturally — no special protocol needed
- **Why is offset commit separate from message processing?** Decoupling lets consumers implement at-most-once (commit before process) or at-least-once (commit after process) or exactly-once (transactions) semantics independently
