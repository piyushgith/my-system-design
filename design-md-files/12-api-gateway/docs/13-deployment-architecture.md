# 13 — Deployment Architecture: API Gateway

---

## Objective

Define deployment strategy for an API gateway — the highest-availability component in the architecture — covering zero-downtime deployments, global distribution, infrastructure topology, and the operational practices that keep it running at 99.99%.

---

## Gateway Deployment Principles

| Principle | Implication |
|---|---|
| Zero-downtime mandatory | Rolling updates with pod disruption budget |
| Stateless | No sticky sessions; any pod can serve any request |
| Fail fast | New pod must be healthy before old pod removed |
| Config hot-reload | Route config updates without pod restart |
| Global edge | PoP-based for sub-50ms latency worldwide |

---

## Infrastructure Topology

```
Internet → Cloudflare (DDoS, WAF, CDN edge)
              │
              ▼
         Route 53 (DNS, health-check based failover)
              │
              ▼
         ALB (AWS Application Load Balancer)
           - TLS termination
           - Path-based routing (if multiple gateway clusters)
           - Sticky: NONE (stateless gateway)
              │
              ▼ (HTTP, internal)
    ┌────────────────────────────────────┐
    │         EKS Cluster               │
    │                                   │
    │  AZ-1: Gateway Pod 1, Pod 2       │
    │  AZ-2: Gateway Pod 3, Pod 4       │
    │  AZ-3: Gateway Pod 5, Pod 6       │
    │                                   │
    │  Min: 3 pods (one per AZ)         │
    │  Max: 50 pods (HPA)               │
    └────────────────────────────────────┘
              │
              ▼ (HTTP, private subnet)
       Backend Services (separate namespaces)
```

---

## Kubernetes Configuration

### Deployment Spec

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-gateway
spec:
  replicas: 6   # 2 per AZ, 3 AZs
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0    # Never remove a pod before new one is ready
      maxSurge: 2          # Create 2 extra pods during update
  template:
    spec:
      affinity:
        podAntiAffinity:
          requiredDuringSchedulingIgnoredDuringExecution:
          - topologyKey: topology.kubernetes.io/zone  # One pod per AZ (minimum)
      containers:
      - name: api-gateway
        image: api-gateway:{git-sha}
        resources:
          requests:
            cpu: "500m"
            memory: "512Mi"
          limits:
            cpu: "2000m"
            memory: "2Gi"
        readinessProbe:
          httpGet:
            path: /health/ready
            port: 8080
          initialDelaySeconds: 10
          periodSeconds: 5
          failureThreshold: 3
        livenessProbe:
          httpGet:
            path: /health/live
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 10
```

`maxUnavailable: 0`: never remove old pod until new pod passes health checks. Zero-downtime guaranteed.

### Pod Disruption Budget

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: api-gateway-pdb
spec:
  minAvailable: 3    # At least 3 pods must be available at all times
  selector:
    matchLabels:
      app: api-gateway
```

Prevents: Kubernetes node drain from evicting too many gateway pods simultaneously.

### HPA Configuration

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef:
    kind: Deployment
    name: api-gateway
  minReplicas: 6
  maxReplicas: 50
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Pods
    pods:
      metric:
        name: gateway_requests_per_second
      target:
        type: AverageValue
        averageValue: "5000"   # 5K RPS per pod before scaling
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 0   # Scale up immediately
    scaleDown:
      stabilizationWindowSeconds: 300  # Wait 5 min before scaling down
```

Scale up immediately (traffic spike); scale down slowly (avoid yo-yo scaling).

---

## Deployment Strategy

### Standard Deployment: Rolling Update

```
Current: 6 pods (v1) serving 100% traffic

Step 1: Create 2 extra pods (v2) — maxSurge
  Total: 6 v1 + 2 v2

Step 2: v2 pods pass readiness probe → receive traffic

Step 3: Remove 2 v1 pods (maxUnavailable: 0 enforced)

Step 4: Repeat until all 6 pods are v2

Rollback:
  kubectl rollout undo deployment/api-gateway
  → Same process in reverse
  → Time: < 3 minutes
```

**Monitoring during rollout**: watch error rate per pod label (v1 vs v2). If v2 pods show higher error rate → stop rollout, investigate.

### Canary for High-Risk Changes

For: new auth mechanism, new routing logic, protocol changes.

```
Step 1: Deploy gateway-v2 as separate deployment (5% traffic via ALB weighted target groups)
  gateway-v1: 95% traffic
  gateway-v2: 5% traffic

Step 2: Monitor 30 minutes
  - Error rate: v1 vs v2 (should be equal)
  - Latency: v1 vs v2 (should be equal or better)
  - Auth failure rate: should be equal

Step 3: Increase to 25% → 50% → 100%

Rollback:
  ALB weight: gateway-v1 = 100%, gateway-v2 = 0%
  Instant, no pod restart
```

---

## Configuration Deployment

Route config changes independently from code changes:

```
Config stored in: ConfigMap (Kubernetes) or Config Service (separate microservice)

Change process:
  1. Engineer updates route config in Git
  2. PR review + approval
  3. Merge to main
  4. CI/CD applies ConfigMap to Kubernetes
  5. Config change event published to Kafka (gateway.config-change)
  6. All gateway pods receive Kafka event
  7. Each pod hot-reloads config in memory atomically
  8. No pod restart, no traffic interruption

Propagation time: Kafka consumer lag + processing = typically < 5 seconds
```

**Why not ConfigMap volume mount with auto-reload?**
- Volume mount changes are not atomic — pod may see partial config during update
- Kafka-based reload: pod processes complete config atomically

---

## Multi-Region Deployment

```
Region: ap-south-1 (Mumbai)       ← Primary
Region: ap-southeast-1 (Singapore) ← APAC users
Region: eu-west-1 (Ireland)        ← European users

Traffic routing:
  Route 53 latency-based routing: users go to nearest healthy region

Each region:
  - Independent EKS cluster
  - Independent ALB
  - Independent Redis Cluster (rate limiting)
  - Shared: backend services (cross-region replication or shared DB)
  
Rate limiting across regions:
  - Per-region limit: 70% of global limit
  - If user hits both Mumbai and Singapore: each region's limit independent
  - Accepts: brief over-limit window in edge cases
```

### Region Failover

```
Route 53 health check:
  - Check gateway /health endpoint every 30 seconds
  - If 3 consecutive failures: fail over to next region
  - Automatic, no manual intervention needed for gateway-level failure

Failover time: 30s health check interval × 3 = 90s to detect + DNS TTL 60s = ~3 min
```

---

## CI/CD Pipeline

```
Developer pushes to feature branch
        │
        ▼
Automated:
  ├── Unit tests (filter rules, auth logic, rate limit logic)
  ├── Integration tests (actual HTTP requests through gateway)
  ├── Contract tests (backward compatible route changes)
  ├── Performance tests (Gatling: 10K RPS load test vs baseline)
  └── Security scan
        │ (pass)
        ▼
Code review: 1 approver (gateway is shared infra — needs careful review)
        │
        ▼
Deploy to dev: automatic
Deploy to staging: automatic + smoke tests
        │
        ▼
Deploy to production:
  Rolling update (standard) OR canary (high-risk change)
  Monitored rollout with automatic rollback
```

---

## Image and Artifact Management

```
Dockerfile (multi-stage):
  Build stage: maven:3.9-eclipse-temurin-21 → build JAR
  Runtime stage: eclipse-temurin:21-jre-alpine → run JAR
  
Image: {registry}/api-gateway:{git-sha}
  - Never use :latest in production
  - Git SHA = immutable reference to exact code
  
Image scanning:
  - Trivy scan on push
  - ECR enhanced scanning
  - Block deployment if CRITICAL CVE found
  
Image signing:
  - Cosign signs image on build
  - Kubernetes admission controller verifies signature before pod start
  - Prevents: tampered image from running
```

---

## Gateway Versions and API Lifecycle

### Gateway Versioning

```
Semantic versioning for gateway major versions:
  /api/v1/* → routes to v1 backend services
  /api/v2/* → routes to v2 backend services
  /api/v3/* → routes to v3 backend services

Gateway handles: version routing (which version of backend to call)
Backend services: can serve multiple API versions simultaneously
Deprecation: v1 deprecated with 6-month notice → sunset date in response headers
```

### Backward Compatibility Enforcement

Contract tests (Pact) run on every PR:
- Consumer: backend service defines expected request/response format
- Provider: gateway verifies it produces compatible format
- Fail build if contract broken

---

## Secret Rotation in Production

| Secret | Rotation | Zero-Downtime Method |
|---|---|---|
| JWT public keys | On private key rotation (quarterly) | JWKS endpoint publishes both old + new keys; gateway fetches via TTL refresh |
| Redis auth token | Annual | Rolling: update Secret, Kubernetes auto-updates pod env, pods restart one at a time |
| TLS certificates | 90 days (ACM auto) | ACM renews automatically; ALB picks up new cert |
| API key pepper | Annual | Re-hash all keys with new pepper in background job |

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| maxUnavailable: 0 | Zero request loss during deploy | Deployment takes longer (extra pods needed) |
| PodDisruptionBudget | Prevents accidental total disruption | Node drains take longer |
| HPA scale-down delay (5 min) | No yo-yo scaling | Brief over-provisioning during load reduction |
| Multi-region active-active | Low global latency | Higher ops complexity, partial rate limit state |
| Config via Kafka | Hot reload, no restart | Config propagation delay (seconds) |

---

## Interview Discussion Points

- **"How do you deploy to the gateway without downtime?"** → Rolling update with `maxUnavailable: 0`: new pod must be ready before old pod removed. Readiness probe ensures new pod handles traffic before traffic shifts.
- **"How many gateway pods do you need?"** → At least 1 per AZ (3 AZs = 3 pods minimum for HA). Size: if 100K RPS target, 5K RPS per pod (conservative) = 20 pods. Add HPA for burst up to 50 pods.
- **"How do you update route config without restarting pods?"** → Config change event via Kafka; each pod hot-reloads config atomically in memory; no restart, no traffic interruption; propagation delay < 5 seconds.
- **"How do you handle multi-region rate limiting?"** → Per-region limit at 70% of global. Users can technically exceed global limit in edge case (hitting two regions simultaneously). Accepted trade-off: correctness vs low-latency (no cross-region Redis round-trip).
- **"What's your rollback plan for a bad gateway deployment?"** → `kubectl rollout undo deployment/api-gateway` → < 3 minutes. For canary: ALB weight shift to 0% canary → instant. Automatic: error rate alert → rollout paused → engineer triggers undo.
