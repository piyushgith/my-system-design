# 13 — Deployment Architecture: E-Commerce Platform

---

## Objective

Define containerization, Kubernetes setup, CI/CD pipeline, environment strategy, blue-green and canary deployment patterns, and infrastructure-as-code approach for the e-commerce platform.

---

## Environment Strategy

| Environment | Purpose | Scale | Data |
|---|---|---|---|
| Local (dev) | Feature development | Single process | H2/Docker Compose |
| Development | Integration testing | Minimal (1 pod) | Shared dev DB |
| Staging | Pre-prod validation | 50% of prod | Anonymized prod snapshot |
| Production | Live traffic | Full scale | Real data |

### Staging Parity

Staging must match production:
- Same Kubernetes version
- Same Docker images (no code differences)
- Same Kafka, Redis versions
- Different scale and anonymized data only

"It works in staging" should predict "it works in prod" with high confidence.

---

## Containerization

### Docker Image Strategy

```dockerfile
# Multi-stage build (build stage)
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN ./mvnw package -DskipTests

# Runtime stage (lean image)
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- Multi-stage: compile in JDK image, run in JRE image (~200MB vs ~400MB)
- Non-root user in container (security)
- Health check endpoint: `/actuator/health`
- One service per container

### Image Registry

- ECR (AWS) or GCR (GCP) for image storage
- Images tagged with: `{service}:{git-sha}` (not `latest` in prod)
- Image scanning on push (Trivy / ECR built-in)

---

## Kubernetes Architecture

```
┌─────────────────────────────────────────────────────┐
│                    AWS EKS Cluster                   │
│                                                     │
│  ┌───────────────────┐  ┌────────────────────────┐ │
│  │  Namespace: prod  │  │  Namespace: monitoring │ │
│  │                   │  │                        │ │
│  │  catalog-service  │  │  prometheus            │ │
│  │  order-service    │  │  grafana               │ │
│  │  payment-service  │  │  jaeger                │ │
│  │  search-service   │  │  alertmanager          │ │
│  │  cart-service     │  └────────────────────────┘ │
│  │  notification-svc │                             │
│  └───────────────────┘                             │
│                                                     │
│  Managed: RDS, ElastiCache (Redis), MSK (Kafka)    │
└─────────────────────────────────────────────────────┘
```

### Pod Sizing (Production)

| Service | Replicas | CPU Request | Memory Request |
|---|---|---|---|
| catalog-service | 5 | 500m | 512Mi |
| order-service | 5 | 500m | 512Mi |
| payment-service | 3 | 500m | 512Mi |
| search-service | 3 | 250m | 256Mi |
| cart-service | 3 | 250m | 256Mi |
| notification-service | 2 | 250m | 256Mi |

### Horizontal Pod Autoscaler (HPA)

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: order-service-hpa
spec:
  scaleTargetRef:
    kind: Deployment
    name: order-service
  minReplicas: 3
  maxReplicas: 20
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
```

HPA triggers:
- Scale up: CPU > 70% for 1 minute
- Scale down: CPU < 30% for 5 minutes (slower to prevent thrashing)

### Flash Sale Pre-Scaling

Before flash sale:
```
kubectl scale deployment order-service --replicas=20
kubectl scale deployment payment-service --replicas=10
```

Done manually or via scheduled CronJob 30 minutes before sale.

---

## CI/CD Pipeline

```
Developer pushes to feature branch
        ↓
GitHub / GitLab CI
  ├── Unit Tests
  ├── Integration Tests (Testcontainers)
  ├── Code Coverage check (min 80%)
  ├── Static analysis (SonarQube)
  └── Security scan (Trivy, SAST)
        ↓ (all pass)
Pull Request → Code Review
        ↓ (approved + merged to main)
Build Pipeline:
  ├── Build Docker image
  ├── Tag with git SHA
  ├── Push to ECR
  └── Image vulnerability scan
        ↓
Deploy to Development (auto):
  └── Helm upgrade --install
        ↓
Deploy to Staging (auto):
  └── Helm upgrade + smoke tests
        ↓
Manual approval (product/tech lead)
        ↓
Deploy to Production:
  └── Canary deploy (5% → 25% → 100%)
```

### Helm Charts

- Each service has own Helm chart
- Values files per environment: `values-dev.yaml`, `values-staging.yaml`, `values-prod.yaml`
- Chart versioned alongside service

---

## Deployment Strategies

### Blue-Green Deployment (for major changes)

```
Current: Blue (v1) serving 100% traffic
Deploy:  Green (v2) deployed, zero traffic

Test green:
  - Smoke tests against green pods
  - QA manual verification

Switch:
  - ALB target group switch: Blue (0%) → Green (100%)
  - Instant, no downtime

Rollback:
  - Switch back to Blue: instant
  - Blue retained for 30 minutes post-cutover

Cleanup:
  - Decommission Blue after confidence window
```

Used for: DB schema changes, major feature releases, architecture changes.

### Canary Release (for routine changes)

```
Deploy canary: 5% traffic → new version
  Monitor 15 minutes:
    - Error rate stable?
    - Latency stable?
    - Business metrics stable?

If OK → 25% traffic
  Monitor 15 minutes

If OK → 50% → 100%

At any step: error rate spikes → automatic rollback to 0% canary
```

Implementation: Kubernetes traffic splitting via Istio or NGINX Ingress annotations.

Used for: most production deployments; continuous delivery.

---

## Database Migration Strategy

Migrations are the highest-risk deployment artifact.

### Schema Migration Rules

1. **Never drop columns in same deploy as code that stops using them**
2. **Expand-contract pattern**:
   - Deploy 1: Add new column (nullable), code writes to both old + new column
   - Deploy 2: Backfill data, code reads from new column
   - Deploy 3: Drop old column
3. **Never add NOT NULL column without default to table with existing rows**
4. **Index creation**: `CREATE INDEX CONCURRENTLY` — non-blocking in Postgres

### Migration Tool

Flyway with versioned SQL migrations:
```
V1__create_orders_table.sql
V2__add_payment_method_to_orders.sql
V3__create_order_items_table.sql
```

Migrations run on startup before app accepts traffic. Kubernetes init containers used for migration execution.

---

## Multi-Region Architecture

At FAANG scale: active-active multi-region.

```
Region: ap-south-1 (Mumbai)    ← Indian users (primary)
Region: ap-southeast-1 (Singapore) ← SE Asia users
Region: us-east-1 (Virginia)   ← US users

Traffic routing: Route 53 latency-based routing
  → User request → routed to nearest region

Data strategy:
  - Postgres: primary in ap-south-1, read replicas in all regions
  - Writes: all regions write to primary (cross-region write latency 40-80ms)
  - OR: active-active with conflict resolution (complex — avoid until necessary)
  
Kafka: MSK per region; MirrorMaker 2 for cross-region replication
```

**Startup advice**: Single region until 100K+ users. Multi-region adds enormous complexity.

---

## Infrastructure as Code

- **Terraform**: AWS infrastructure (VPC, EKS, RDS, MSK, ElastiCache, S3)
- **Helm**: Kubernetes workloads
- **Kustomize**: environment-specific overrides on top of Helm

All infrastructure changes via IaC PRs — no manual console changes in prod.

---

## Rollback Strategy

| Change Type | Rollback Method | Time |
|---|---|---|
| Docker image | Deploy previous image tag | < 2 minutes |
| Helm config | Helm rollback | < 1 minute |
| DB migration | Flyway rollback script (if written) | Varies |
| DB migration (no rollback) | Expand-contract: safe forward only | N/A |
| Kafka consumer code | Deploy previous image | < 2 minutes |

DB rollbacks are hardest — expand-contract pattern avoids most rollback scenarios.

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Canary over blue-green | Lower blast radius, gradual validation | Longer deployment window |
| Helm for K8s | Templating, reusable charts | YAML complexity, helm chart management |
| Managed services (RDS, MSK) | Less ops burden | Higher cost, less control |
| Multi-stage Docker build | Smaller image, faster pulls | Slightly more complex Dockerfile |

---

## Interview Discussion Points

- **"How do you deploy without downtime?"** → Canary: 5% → 25% → 100% with automatic rollback on error rate spike
- **"How do you handle DB migrations safely?"** → Expand-contract, never drop columns same deploy as code change, CREATE INDEX CONCURRENTLY
- **"How do you handle flash sale traffic spikes?"** → Pre-scale pods, HPA min replicas increased before sale, Redis-first for purchase decisions
- **"How does your CI/CD pipeline look?"** → Push → test → build image → deploy dev → staging → manual gate → canary prod
- **"What's your rollback time?"** → Image rollback < 2 min; DB rollback is the risk — mitigated by expand-contract migrations
