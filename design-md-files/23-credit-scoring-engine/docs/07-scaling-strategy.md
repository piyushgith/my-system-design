# 07 — Scaling Strategy: Credit Scoring Engine

---

## Objective

Define the phased scaling strategy from MVP (50 RPS) to large-scale (500+ RPS real-time, 100M batch nightly). Identify bottlenecks at each phase and prescribe the architectural evolution needed.

---

## Current Scale Targets (Phase 1 Baseline)

| Metric | Value |
|---|---|
| Real-time scoring | 50 RPS peak |
| Batch scoring | 5M users/night |
| Score cache hit rate | ~80% (5-min TTL) |
| Feature store users | 10M profiles |
| score_history growth | 5M rows/month |
| Redis feature store size | ~10M users × 20 features × 100 bytes = ~20 GB |

---

## Phase 1: Single-Region Modular Monolith (0–50 RPS)

**Target:** MVP. Handle current load with minimal infra.

### Architecture

```
[Loan/Card Service]
      ↓
[Scoring Engine — 3 pods (Spring Boot)]
      ↓              ↓              ↓
[Redis Feature]  [Redis Score]  [PostgreSQL]
  Store            Cache          (single primary)
```

### Configuration

- 3 pods, 4 vCPU / 8 GB each
- ONNX Runtime: 1 model session per pod, warm on startup
- Redis: single 16 GB instance (feature store + score cache on same cluster)
- PostgreSQL: 1 primary, 1 read replica
- Spring Batch: 5 parallel partition threads per job execution

### Bottleneck Analysis

| Component | Bottleneck | Threshold |
|---|---|---|
| ONNX inference | Single-threaded per request | ~200 RPS per pod (CPU-bound) |
| Redis feature MGET | Network bandwidth | ~10K RPS before saturation |
| PostgreSQL score_history | Insert throughput | ~2K inserts/sec (async, batched) |
| Score cache hit rate | 80% → 20% cache misses need full inference | 50 RPS × 0.2 = 10 real computations/sec |

### Scaling Actions at This Phase

- Async score storage (non-blocking — already designed)
- Score cache TTL tuning (5 min for most callers)
- Monthly partition pre-creation for score_history

---

## Phase 2: Horizontal Pod Scaling (50–200 RPS)

**Trigger:** P99 latency > 100ms consistently, or CPU > 70% across pods.

### Changes

1. **HPA (Horizontal Pod Autoscaler):** scale scoring pods 3 → 12 based on CPU (target 60%)
2. **Redis separation:** split feature store and score cache to separate Redis clusters (different access patterns, avoid cache evictions competing)
3. **Read replica routing:** all score history reads (audit, history API) → read replica; writes → primary
4. **ONNX thread pool:** increase `OrtEnvironment` thread count to 4 per pod (parallelizes batch inference within a single pod)
5. **Score cache warm-up:** pre-populate score cache on pod startup for top 10K users (loan approval candidates)

```
[Load Balancer]
    ↓
[Scoring Pods × 12]
    ↓          ↓
[Redis Feature]  [Redis Score Cache]
   Cluster         Cluster (separate)
    ↓
[PostgreSQL Primary + Read Replica]
```

### Bottleneck Analysis

| Component | New Bottleneck | Threshold |
|---|---|---|
| Feature store Redis | Concurrent MGET at 200 RPS × 20 keys = 4000 reads/sec | Cluster can handle 100K reads/sec |
| PostgreSQL primary | Async inserts from 12 pods = ~200 inserts/sec | Fine |
| Batch scoring job | 5M users / 5 workers takes 42 min | Not yet bottleneck |

---

## Phase 3: Feature Pipeline Decoupling (200–500 RPS)

**Trigger:** Feature pipeline Kafka lag increasing, feature freshness degrading during peak traffic.

### Changes

1. **Separate Feature Pipeline service:** extract `FeaturePipeline` into standalone pods (separate from scoring engine). Scoring engine is read-only to feature store.
2. **Kafka Streams application:** dedicated pod group for real-time behavioral features (not mixed with scoring engine)
3. **Spring Batch dedicated node:** separate EKS node pool for batch scoring jobs (avoids resource contention with real-time scoring)
4. **Redis feature store cluster mode:** 3-node Redis cluster with 6 shards (supports horizontal scaling)
5. **PgBouncer connection pooling:** add PgBouncer in transaction mode between scoring pods and PostgreSQL (prevents connection exhaustion at 12+ pods × 5 threads = 60+ connections)

```
[Loan/Card/CRM]                  [Feature Pipeline Pods]
      ↓                                    ↓
[Scoring Engine ×12] ←[Redis Feature Store Cluster (3 nodes)]
      ↓                          ↑
[PostgreSQL]           [Kafka: bureau.data.refreshed,
                               transaction.posted]
```

### Batch Scoring Scaling

**Problem:** 5M users at Phase 3 scale → batch scoring competes with real-time traffic for Redis MGET bandwidth.

**Fix:** batch scoring reads from a **dedicated Redis read replica** (or separate ElastiCache cluster) populated by the feature pipeline. Real-time scoring hits the primary cluster. Batch scoring isolated to off-peak hours (2–6 AM).

---

## Phase 4: CQRS Split and Model Server (500+ RPS)

**Trigger:** 500+ RPS requires dedicated model serving infrastructure. In-process ONNX becomes CPU bottleneck.

### Changes

1. **Dedicated Model Serving pods:** extract `ModelInferenceService` into separate pods using ONNX Runtime Server (or Triton Inference Server). Scoring engine calls via gRPC
2. **GPU-backed inference nodes:** for large ensemble models, move to GPU-based inference (A10G instances). ONNX model → TensorRT optimization → GPU inference < 1ms
3. **Feature Store v2:** migrate from flat Redis keys to a proper feature store (Feast with Redis backend). Enables point-in-time feature retrieval for training + serving consistency
4. **CQRS for scoring commands/queries:** separate scoring write path (POST /scores → compute + store) from query path (GET /scores/history → read replica PostgreSQL)
5. **Score history CQRS projection:** maintain a denormalized summary table in Redis for fast "latest score per user" lookup (avoid PostgreSQL query on every score request from downstream)

```
[Scoring API Pods ×20]
      ↓              ↓
[Model Serving]   [Feature Store]
[Pods ×8 (GPU)]    [Feast + Redis]
      ↓
[Triton Inference]  [PostgreSQL CQRS]
                   [Write: Primary]
                   [Read: Replica × 3]
```

---

## Phase 5: Multi-Region Active-Active (1000+ RPS)

**Trigger:** 1000+ RPS, regulatory requirements for data residency, or global deployment.

### Changes

1. **Regional feature stores:** each region maintains its own Redis feature store. Bureau data and behavioral features replicated via Kafka MirrorMaker 2 from canonical region
2. **Model registry global replication:** ONNX model files in S3 replicated across regions. Model registry PostgreSQL replicated via logical replication. Champion/challenger config synchronized globally
3. **Score event global fan-out:** `credit.score.computed` events published to global Kafka → consumed by downstream services in each region
4. **Cross-region audit:** score_history partitions archived to S3 Parquet. Cross-region replication via S3 replication rules. Aurora Global Database considered for multi-region PostgreSQL write capability

**Data residency:** Indian user data (bureau features, score history) must remain in ap-south-1 (Mumbai). Cross-border transfer only for anonymized model performance metrics.

---

## Batch Scoring Scaling Trajectory

| Phase | Users | Duration | Strategy |
|---|---|---|---|
| 1 | 5M | 42 min (5 workers) | Single Spring Batch job |
| 2 | 10M | 60 min (10 workers) | Increase parallel partitions |
| 3 | 20M | 45 min (50 workers) | Kubernetes Job, 50 pods |
| 4 | 50M | 90 min (100 workers) | Spark on EMR, columnar reads |
| 5 | 100M | 3 hours | Spark on EMR + pre-warmed feature cache |

**Phase 4+ transition to Spark:** Spring Batch cannot efficiently process 50M+ users. Apache Spark on EMR reads features from Redis (or Parquet snapshot), distributes inference across 100 executors, writes score_history via JDBC batch inserts.

---

## Hot Partition and Hot User Problems

### Problem: High-Demand User

A celebrity or large business account with high inquiry volume → many callers requesting score simultaneously → Redis score cache stampede.

**Fix:** distributed lock on score computation. First caller acquires `SETNX scoring_lock:{user_id} TTL=10s`. Subsequent callers wait and poll (short-poll, max 3 retries at 5ms intervals). Score cache populated by winner → subsequent requests served from cache.

### Problem: Model Version Skew During Hot-Reload

During hot-reload: pod A loads new model, pod B still runs old model. Same user_id hash → routes to pod A (new champion) one request, pod B (old champion) next request within seconds.

**Fix:** score_history stores `model_version` per row. Downstream consumers receive `model_version` in score event. Skew window is < 30 seconds (time for all pods to load new model). Accepted as operational reality — no data correctness risk, only brief model version inconsistency.

### Problem: Batch Score Competing with Real-Time

Batch scoring job reading 5M MGET from Redis at 2 AM → Redis CPU spike → P99 latency spikes for overnight real-time traffic (loan applications in other time zones).

**Fix:** batch scoring uses separate Redis cluster (read replica). If cost-prohibited: batch scoring throttled at 500 MGET/sec via Guava RateLimiter. Real-time scoring always takes priority.

---

## Scaling Decision Summary

| RPS Range | Key Change | Cost Driver |
|---|---|---|
| 0–50 | Single modular monolith, Redis, PostgreSQL | Minimal |
| 50–200 | HPA, Redis cluster split, read replica | +$500/month |
| 200–500 | Feature pipeline decoupled, PgBouncer, batch isolation | +$2K/month |
| 500–1000 | Dedicated model serving, Feast, CQRS split | +$10K/month |
| 1000+ | Multi-region, Spark batch, Aurora Global | +$50K+/month |

---

## Interview Discussion Points

- **Why not serve 50 RPS with serverless (Lambda + RDS)?** ONNX model must be loaded into memory on startup (model file ~50MB, initialization ~2s). Lambda cold starts would exceed latency SLA. Serverless is viable only if model is pre-loaded in a container (Lambda with SnapStart, or Fargate with pre-warmed containers) — operational complexity similar to EKS at that point. EKS wins
- **How do you scale batch scoring to 100M users without Spark?** You don't. Spring Batch is a single-JVM framework — parallelism is limited to available threads on one node. At 20M+ users, Kubernetes Job with 50 independent pods (each processing 400K users) is viable. At 50M+, Spark is necessary for distributed shuffle, parallel reads, and optimized columnar I/O. Don't over-engineer for 5M
- **What's the feature store bottleneck at scale?** Redis cluster throughput is not the bottleneck — Redis can handle 1M ops/sec per shard. The bottleneck is network bandwidth: 500 RPS × 20 features × 100 bytes = 1 MB/s per pod. At 20 pods = 20 MB/s → manageable. At 500 pods (extreme scale) → move to binary feature encoding (protobuf instead of JSON string values) — 10× reduction in network bytes
- **How do you handle feature store cold start for a new user?** New user onboards → zero features in Redis → score request arrives before feature pipeline runs. Fix: `FeatureAssemblyService.validateCompleteness()` checks which features are missing. Missing bureau features → trigger async bureau pull + set `is_thin_file=true`. Use NTC (New To Credit) model instead of bureau model. Return score within 200ms using behavioral + account features only
