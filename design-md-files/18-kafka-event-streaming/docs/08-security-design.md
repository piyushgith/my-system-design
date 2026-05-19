# 08 — Security Design: Kafka-like Event Streaming System

---

## Objective

Define authentication, authorization, encryption, and audit mechanisms for a multi-tenant event streaming platform. Address the unique security challenges of a high-throughput messaging system where misconfigurations can expose sensitive data streams to unauthorized consumers.

---

## Threat Model

| Threat | Impact | Mitigation |
|---|---|---|
| Unauthorized producer writing to topic | Data poisoning, data injection | SASL authentication + ACLs |
| Unauthorized consumer reading sensitive topic | Data exfiltration | Consumer group ACLs |
| Network eavesdropping (credentials, message payloads) | Credential theft, data leak | TLS in-transit encryption |
| Malicious producer impersonating another service | Replay attacks, data manipulation | Mutual TLS (mTLS) or SASL SCRAM with unique credentials |
| Admin API abuse (topic deletion, partition reassignment) | Service disruption | Admin role separation + ACLs |
| Rogue broker injecting messages | Data integrity compromise | Broker authentication to controller |
| Log tampering on disk | Data corruption audit failure | Filesystem permissions + CRC per batch |
| Consumer group pollution (offset reset attack) | Processing disruption | OffsetCommit ACL |
| Schema injection (malformed schemas) | Consumer deserialization crash | Schema Registry ACL + compatibility rules |

---

## Authentication

### SASL Mechanisms

**SASL/PLAIN** (development only)
- Username + password in plaintext over SASL
- Never use without TLS — credentials visible on wire
- Suitable for local dev / single-machine testing only

**SASL/SCRAM-SHA-256 (recommended for on-premise)**
- Challenge-response — credentials never sent in plaintext
- Stored as salted SCRAM verifier in ZooKeeper/KRaft
- Credentials rotatable without broker restart
- Supports multiple users with different credentials

**SASL/OAUTHBEARER**
- JWT tokens from identity provider (Keycloak, Okta, Auth0)
- Tokens have TTL — auto-expire, short-lived credentials
- Best for cloud-native multi-tenant environments
- Custom callback validates JWT signature against IdP JWKS endpoint

**mTLS (Mutual TLS)**
- Both client and broker present X.509 certificates
- Client certificate CN/SAN used as principal identity
- No password management — PKI manages identity
- Best for service-to-service in zero-trust environments

### Broker-to-Broker Authentication
- Controller ↔ Broker: SASL PLAIN with internal credential (not user-facing)
- Broker ↔ Broker (replication): dedicated replication user with produce/fetch ACL on internal topics
- Separate listener for inter-broker traffic (avoids exposing to client-facing port)

---

## TLS Configuration

### Listener Architecture
```
listeners=INTERNAL://:9093,EXTERNAL://:9092,CONTROLLER://:9094

INTERNAL:  mTLS for broker-to-broker replication
EXTERNAL:  TLS + SASL for clients
CONTROLLER: TLS for KRaft controller communication
```

**Why separate listeners?** Different security policies per listener type. Client-facing listener can be publicly reachable; inter-broker listener locked to VPC/VLAN.

### Certificate Management
- CA issues broker certificates (CN = broker FQDN)
- Rotate certificates before expiry using ACME (Let's Encrypt) or internal PKI
- `ssl.client.auth=required` on inter-broker listener for mTLS
- `ssl.client.auth=requested` on client listener (optional client cert, identity from SASL)

### TLS Overhead
- Handshake: ~1–2ms per new connection (amortized over long-lived connections)
- Bulk encryption: ~5% CPU overhead for AES-GCM (hardware-accelerated on modern CPUs)
- Connection multiplexing: each client maintains few long-lived connections — handshake cost negligible

---

## Authorization (ACLs)

### ACL Model

Each ACL entry: `(Principal, ResourceType, ResourceName, Operation, PermissionType)`

**Resource Types:**
- `Topic` — message read/write
- `Group` — consumer group operations
- `Cluster` — cluster-level operations (broker registration, controller)
- `TransactionalId` — transactional producer operations
- `DelegationToken` — token management

**Operations:**
- `Topic`: Read, Write, Create, Delete, Alter, Describe
- `Group`: Read, Delete, Describe
- `Cluster`: Create, ClusterAction, DescribeConfigs, AlterConfigs

**Example ACLs:**

```
# Allow order-service to produce to orders topic
Principal=User:order-service  Resource=Topic:orders  Op=Write  Allow

# Allow analytics-service to consume from orders (any group with prefix analytics-)
Principal=User:analytics-service  Resource=Topic:orders  Op=Read  Allow
Principal=User:analytics-service  Resource=Group:analytics-*  Op=Read  Allow

# Block all others from reading PII topic by default
Principal=User:*  Resource=Topic:user-pii  Op=Read  Deny
Principal=User:compliance-service  Resource=Topic:user-pii  Op=Read  Allow

# Allow admin team to alter any topic config
Principal=User:kafka-admin  Resource=Cluster:kafka-cluster  Op=AlterConfigs  Allow
```

### ACL Storage
- ACLs stored in `__cluster_metadata` (KRaft) or ZooKeeper `/kafka-acls`
- Cached in-memory on each broker (refreshed on change via metadata propagation)
- Authorization happens in-process on broker — no external call required

### Default Deny vs Default Allow

| Mode | Risk | Use Case |
|---|---|---|
| `allow.everyone.if.no.acl=true` | Open cluster — anyone can do anything if no ACL defined | Development/testing |
| `allow.everyone.if.no.acl=false` | Closed cluster — explicit allow required | Production (recommended) |

---

## Encryption at Rest

### Approach 1: Filesystem Encryption (Recommended)
- OS-level: LUKS (Linux Unified Key Setup) on broker data volumes
- Transparent to Kafka — no code changes
- Key management via HSM or cloud KMS (AWS KMS, GCP CMEK)
- Least operational complexity

### Approach 2: Kafka-Level Encryption
- Not natively supported in Kafka
- Client-side encryption: producer encrypts value before sending, consumer decrypts after receiving
- Key management must be external (KMS)
- Prevents even broker administrators from reading message payloads
- Breaks log compaction (broker can't compare keys for compaction)

### Sensitive Data Tagging
- Header-based sensitivity flag: `x-sensitivity: PII`
- Separate topics for PII data with stricter ACLs
- Schema registry metadata: mark schema as containing PII fields
- Consumer compliance: consumers of PII topics must accept data governance agreement

---

## Secrets Management

### Broker Credentials
- SASL credentials stored in KRaft metadata (not plaintext in `server.properties`)
- TLS keystores/truststores: mounted from Kubernetes secrets or HashiCorp Vault
- OAUTHBEARER client secret: injected via environment variable from secret manager

### Credential Rotation
- SCRAM credentials: `kafka-configs.sh --alter --entity-type users --delete-config 'SCRAM-SHA-256'` then re-add — no broker restart
- TLS certificates: broker supports hot reload of keystore on certificate change (SIGHUP or Kubernetes secret rotation)
- OAuth client secrets: TTL-bound tokens rotate automatically

---

## Rate Limiting (Security Perspective)

### Quota Enforcement
```
# Per-user quota
kafka-configs.sh --alter --entity-type users --entity-name rogue-client \
  --add-config 'producer_byte_rate=1MB,consumer_byte_rate=1MB,request_percentage=25'
```

- Prevents one client from starving others (noisy neighbor)
- Quota violation → `throttleTimeMs` in response (not connection drop — allows graceful handling)
- Burst allowed up to quota × 2 for short periods (token bucket with initial burst)

### DDoS Considerations
- `max.connections` per IP: limit connection flood from single source
- `max.connections.per.ip.overrides`: whitelist trusted IPs for higher limits
- API Gateway / Nginx in front of Admin REST API for additional rate limiting

---

## Audit Logging

### What to Audit
```
AuditEvent {
  timestamp: ISO 8601
  principal: string          // authenticated user
  clientId: string
  sourceIP: string
  operation: string          // PRODUCE, FETCH, CREATE_TOPIC, etc.
  resource: string           // topic:orders, group:analytics-service
  authorized: boolean
  reason: string             // ACL rule that matched
}
```

### Audit Log Destination
- Write audit events to a dedicated Kafka topic (`_audit_log`) — ironic but powerful
  - Leverage Kafka's own durability, replication, and retention
  - Audit topic ACL: only security service can read; brokers produce-only
- Alternatively: syslog forwarding to SIEM (Splunk, Elastic SIEM)
- Do NOT write audit to same disk as data logs — audit must be durable even if broker crashes

### Authorization Event Audit
Every `DENY` result → audit log (failed access attempts indicate probing or misconfigured client)
Every `ALLOW` for sensitive topics → audit log (compliance requirement for PII access)

---

## Multi-Tenant Security Architecture

```
Tenant A (Order Service)
  Topics: orders-*, inventory-*
  ACL: write orders-*, read inventory-*
  Quota: 500 MB/sec produce, 1 GB/sec consume

Tenant B (Analytics Service)
  Topics: analytics-*
  ACL: read orders-* (cross-tenant read allowed explicitly)
  Quota: 100 MB/sec produce, 5 GB/sec consume

Schema Registry
  Subject-level ACL: only owning team can register schemas for their subjects
```

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| SASL SCRAM vs mTLS | SCRAM: simpler credential management; mTLS: stronger zero-trust | mTLS requires PKI infrastructure; SCRAM has password management burden |
| ACL per-topic vs per-topic-prefix | Per-topic is precise; prefix reduces ACL count | Prefix can over-grant if naming convention broken |
| Client-side encryption vs filesystem | Client-side: broker-blind encryption; filesystem: simpler | Client-side breaks log compaction; requires all consumers to have decryption keys |
| Closed ACL mode (default deny) | Production security posture | Ops burden: all new services need explicit ACL provisioning |

---

## Interview Discussion Points

- **How do you handle certificate rotation with zero downtime?** Broker supports multiple SSL keystores; update keystore with new cert, trigger reload via SIGHUP. Clients reconnect and get new cert on next connection
- **What happens if ACL store (ZooKeeper/KRaft) is unavailable?** Broker continues with cached ACLs. After configurable staleness threshold, it can deny all requests as safe default (configurable). Cache prevents brief coordinator outage from blocking all traffic
- **How do you prevent a compromised service from producing to all topics?** SASL per-service credentials + topic-level ACLs. Compromise of one service's credential only grants that service's permissions — blast radius limited
- **Can a broker admin read message contents?** Yes — unless client-side encryption is used. Filesystem encryption only protects against disk theft. For full broker-admin protection, encrypt at producer, decrypt at consumer, with key management external to Kafka
- **How does OAuth work with Kafka?** `sasl.mechanism=OAUTHBEARER`. Producer/consumer fetches JWT from IdP, presents in SASL handshake. Broker validates signature and expiry. No broker-IdP round-trip per request — validation is local after JWKS key fetch at startup
