# 13 — Deployment Architecture: Kafka-like Event Streaming System

---

## Objective

Define the Kubernetes-based deployment, infrastructure topology, CI/CD pipeline, rolling upgrade strategy, and local development setup for a production-grade event streaming platform.

---

## Infrastructure Philosophy

**Kafka is stateful infrastructure — it is NOT a typical stateless microservice.**

| Aspect | Web Service | Kafka Broker |
|---|---|---|
| State | Stateless (DB holds state) | Heavy stateful (log files on local disk) |
| Pod scheduling | Any node | Pinned to nodes with fast NVMe SSDs |
| Scaling | Add pods freely | Add brokers + rebalance partitions (planned) |
| Upgrades | Rolling restart (seconds) | Rolling restart (minutes, must wait for ISR sync) |
| Persistent storage | None (or shared NFS) | Local NVMe (NOT network-attached — too slow) |

---

## Kubernetes Deployment (StatefulSet)

### Why StatefulSet?

| Feature | StatefulSet | Deployment |
|---|---|---|
| Stable pod names | `kafka-0`, `kafka-1`, `kafka-2` | Random hashes |
| Stable DNS | `kafka-0.kafka-svc.kafka.svc.cluster.local` | Not guaranteed |
| Ordered rollout | One pod at a time | All at once |
| PVC per pod | Yes (local storage) | Shared or none |

Kafka requires stable network identity (brokers registered by hostname) and per-pod storage — StatefulSet is mandatory.

### Manifest Overview

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: kafka
  namespace: kafka
spec:
  serviceName: kafka-svc
  replicas: 3
  selector:
    matchLabels:
      app: kafka
  template:
    metadata:
      labels:
        app: kafka
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
          - labelSelector:
              matchExpressions:
              - key: app
                operator: In
                values: [kafka]
            topologyKey: kubernetes.io/hostname  # one broker per node
        nodeAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
            nodeSelectorTerms:
            - matchExpressions:
              - key: node-type
                operator: In
                values: [kafka-broker]  # dedicated nodes with NVMe
      containers:
      - name: kafka
        image: confluentinc/cp-kafka:7.5.0
        ports:
        - containerPort: 9092  # external clients
        - containerPort: 9093  # inter-broker
        - containerPort: 9094  # KRaft controller
        resources:
          requests:
            cpu: "4"
            memory: "16Gi"
          limits:
            cpu: "8"
            memory: "32Gi"
        env:
        - name: KAFKA_HEAP_OPTS
          value: "-Xmx6g -Xms6g"
        volumeMounts:
        - name: kafka-data
          mountPath: /var/kafka/logs
        readinessProbe:
          exec:
            command: ["kafka-broker-api-versions.sh", "--bootstrap-server", "localhost:9092"]
          initialDelaySeconds: 30
          periodSeconds: 10
  volumeClaimTemplates:
  - metadata:
      name: kafka-data
    spec:
      accessModes: ["ReadWriteOnce"]
      storageClassName: local-nvme    # local storage class — must use local PV
      resources:
        requests:
          storage: 4Ti
```

### Node Topology

```
┌─────────────────────────────────────────────────────────────────┐
│  AZ-A Node Pool (kafka-broker nodes)                             │
│  Node 1: kafka-0, kafka-3, kafka-6 (3 brokers per node = bad) │
│  → Actually: 1 broker per node (podAntiAffinity enforces this) │
│                                                                  │
│  Node 1: kafka-0  (NVMe: /dev/nvme0, 4TB)                      │
│  Node 2: kafka-1  (NVMe: /dev/nvme0, 4TB)                      │
│  Node 3: kafka-2  (NVMe: /dev/nvme0, 4TB)                      │
└─────────────────────────────────────────────────────────────────┘
```

**Why dedicated nodes?** Kafka page cache competes with other workloads for RAM. Shared nodes cause cache eviction → disk reads → latency spikes. Dedicate nodes with `node-type=kafka-broker` taint.

---

## KRaft Controller Deployment

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: kafka-controller
  namespace: kafka
spec:
  replicas: 3  # Raft quorum needs odd number
  # Similar structure to broker StatefulSet
  # But lighter resources (no data storage needed — just metadata log)
  resources:
    requests:
      cpu: "2"
      memory: "8Gi"
```

Controllers and brokers can be combined in a mixed-mode deployment for smaller clusters (< 10 brokers). For production at scale, separate deployments prevent controller load from affecting data path.

---

## Kubernetes Services

```yaml
# Headless service for stable DNS per pod
apiVersion: v1
kind: Service
metadata:
  name: kafka-svc
  namespace: kafka
spec:
  clusterIP: None  # headless
  selector:
    app: kafka
  ports:
  - name: kafka
    port: 9092
  - name: kafka-internal
    port: 9093

---
# LoadBalancer for external client access
apiVersion: v1
kind: Service
metadata:
  name: kafka-external
  namespace: kafka
spec:
  type: LoadBalancer
  selector:
    app: kafka
  ports:
  - port: 9092
    targetPort: 9092
```

**External client routing challenge:** `kafka-external` LB routes to random broker. Clients then receive metadata pointing to specific brokers (by hostname). External DNS must resolve `kafka-0.kafka-svc.kafka.svc.cluster.local` to the external LB IP of that specific pod. Solution: use per-pod NodePort or external-dns with pod annotations.

---

## Local Development Setup

```
docker-compose.yml

services:
  zookeeper:       # or kraft-controller
    image: confluentinc/cp-zookeeper:7.5.0
    ports: ["2181:2181"]

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    depends_on: [zookeeper]
    ports: ["9092:9092"]
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092

  schema-registry:
    image: confluentinc/cp-schema-registry:7.5.0
    depends_on: [kafka]
    ports: ["8081:8081"]

  kafka-ui:
    image: provectuslabs/kafka-ui:latest
    ports: ["8080:8080"]
    environment:
      KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS: kafka:9092
      KAFKA_CLUSTERS_0_SCHEMAREGISTRY: http://schema-registry:8081
```

**Developer workflow:**
1. `docker-compose up` — local Kafka + Schema Registry + UI
2. Service connects to `localhost:9092`
3. `kafka-ui` at `localhost:8080` — visual topic/consumer inspection
4. Testcontainers for integration tests (ephemeral Kafka per test class)

---

## CI/CD Pipeline

```
Feature Branch
     │
     ▼
┌──────────────────────────────────────────────────────────┐
│  PR Validation                                            │
│  1. Unit tests (Testcontainers for Kafka integration)    │
│  2. Schema compatibility check (Schema Registry CLI)     │
│  3. Code quality (SonarQube, SpotBugs)                  │
│  4. Docker image build + scan (Trivy)                    │
└──────────────────────────────┬───────────────────────────┘
                               │ Merge to main
                               ▼
┌──────────────────────────────────────────────────────────┐
│  Dev Deploy                                               │
│  1. Helm upgrade to dev cluster                          │
│  2. Smoke tests (produce/consume/schema validate)        │
│  3. Consumer group lag check (< 100 messages after test) │
└──────────────────────────────┬───────────────────────────┘
                               │ Approval gate
                               ▼
┌──────────────────────────────────────────────────────────┐
│  Staging Deploy                                           │
│  1. Rolling restart (wait for ISR sync between pods)     │
│  2. 30-minute soak test                                  │
│  3. Consumer lag regression check                        │
│  4. Schema backward compatibility validation             │
└──────────────────────────────┬───────────────────────────┘
                               │ Manual approval
                               ▼
┌──────────────────────────────────────────────────────────┐
│  Production Deploy (Rolling)                              │
│  1. Canary: update kafka-2 (last broker) first            │
│  2. Monitor: under_replicated_partitions = 0             │
│  3. Proceed with kafka-1, then kafka-0                   │
│  4. Post-deploy: verify all ISRs restored               │
└──────────────────────────────────────────────────────────┘
```

---

## Rolling Upgrade Strategy

Kafka requires careful ordering to maintain availability during broker upgrades:

### Step-by-step Rolling Broker Upgrade

1. **Pre-upgrade check:** `offline_partitions_count = 0`, `under_replicated_partitions = 0`
2. Upgrade broker N (last broker first, not leader-heavy brokers first)
3. **Wait:** broker N restarts, rejoins cluster, ISR catches up
4. **Verify:** `under_replicated_partitions = 0` before proceeding
5. Repeat for broker N-1, N-2, ...
6. **Post-upgrade:** `kafka-preferred-replica-election.sh` to restore preferred leaders

**Why restart last broker first?** If last broker has partitions as follower — restarting it causes brief under-replication but zero client impact (clients use leader). If you restart the leader first → leader election → brief produce unavailability.

### Inter-broker Protocol Versioning
Kafka supports running mixed broker versions temporarily:
- `inter.broker.protocol.version=3.5` on both old and new brokers during upgrade
- Complete upgrade, then bump `inter.broker.protocol.version` on all brokers
- Finally bump `log.message.format.version` (if relevant)

---

## Blue-Green Deployment

Full blue-green is impractical for Kafka brokers (topic data is on specific brokers). Instead:

### Schema Registry Blue-Green
- Schema Registry is stateless (data in PostgreSQL)
- Full blue-green: deploy new version alongside old, switch LB, tear down old
- Zero-downtime: clients briefly see either version (both serve same data from DB)

### Admin API Blue-Green
- Admin REST API is separate service — supports full blue-green
- `kafka-admin-v2` alongside `kafka-admin-v1`; switch Load Balancer

---

## Kubernetes Resource Management

### JVM Tuning

```
# GC tuning for minimal pause times
KAFKA_JVM_PERFORMANCE_OPTS: >
  -server
  -XX:+UseG1GC
  -XX:MaxGCPauseMillis=20
  -XX:InitiatingHeapOccupancyPercent=35
  -XX:+ExplicitGCInvokesConcurrent
  -Djava.awt.headless=true
```

**Why G1GC?** Predictable pause times (< 20ms target). ZGC is an alternative for ultra-low latency (< 1ms GC pauses) but higher memory overhead. GC pauses > replica.lag.time.max.ms cause ISR shrinkage — minimize pauses.

### Resource Requests vs Limits

| Resource | Request | Limit | Reason |
|---|---|---|---|
| CPU | 4 cores | 8 cores | Burst for replication catch-up |
| Memory | 16 GB | 32 GB | JVM 6 GB + page cache as much as possible |
| Storage | 4 TB NVMe | Fixed | Provisioned at node level |

**Do NOT limit memory too tightly** — Kubernetes OOM-killing a Kafka broker pod causes unexpected partition leader elections.

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| StatefulSet over Deployment | Stable identity required for broker registration | Complex upgrade procedure |
| Local NVMe over EBS/network storage | 10x lower latency, sequential write performance | Non-trivial node provisioning |
| Dedicated broker nodes | Page cache isolation from other workloads | Higher infrastructure cost |
| Rolling restart (not blue-green) | Stateful data — can't just spin up new cluster | Brief under-replication per broker restart |
| Mixed-mode controller+broker (small cluster) | Fewer nodes to manage | Controller contention from broker load |

---

## Interview Discussion Points

- **Why can't you use network-attached storage (EBS, NFS) for Kafka?** Network storage adds 1–5ms per write + unpredictable latency spikes. Kafka needs sequential writes at 500 MB/sec — NVMe SSD (~500 MB/sec) barely keeps up; network storage bottlenecks at 100–200 MB/sec
- **How do you handle Kubernetes pod scheduling for Kafka?** `podAntiAffinity` ensures one broker per node. `nodeAffinity` pins brokers to dedicated nodes with NVMe. `taint/toleration` prevents non-Kafka workloads on broker nodes
- **What is the risk of restarting a partition leader broker?** Leader election triggers. Clients get `NOT_LEADER` error → metadata refresh → retry on new leader. Impact: ~10 second gap in produce availability for affected partitions. Minimize by restarting non-leaders first
- **How does Kubernetes handle persistent volumes with local NVMe?** `StorageClass: local-storage` with `volumeBindingMode: WaitForFirstConsumer`. LocalPV manager provisions volumes tied to specific nodes. Pod always scheduled to the node with its PV
- **What happens when a Kafka StatefulSet pod is evicted?** Pod deschedules, PVC remains. New pod claims same PVC on same node (local storage binding). Broker rejoins, replication resumes. If node is gone → must manually handle data recovery
