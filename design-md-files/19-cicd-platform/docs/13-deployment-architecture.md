# 13 — Deployment Architecture: CI/CD Platform

---

## Objective

Define Kubernetes-based deployment topology, CI/CD pipeline for the platform itself (dogfooding), rolling upgrade strategy, environment separation, and local development setup.

---

## Deployment Topology

### Kubernetes Namespaces

```
cluster: cicd-production
  ├── cicd-control/         ← control plane services (low-count, high-reliability)
  │   ├── webhook-service (3 pods)
  │   ├── pipeline-api (3 pods)
  │   ├── scheduler (2 pods, leader elected)
  │   ├── log-service (auto-scaled 5–50 pods)
  │   ├── secret-service (3 pods, strict NetworkPolicy)
  │   ├── artifact-service (3 pods)
  │   ├── notification-service (3 pods)
  │   └── pr-status-service (2 pods)
  │
  ├── cicd-runners/         ← ephemeral execution (high-count, auto-scaled)
  │   └── runner-* (0–10,000 pods, HPA-managed)
  │
  ├── cicd-data/            ← stateful backing services
  │   ├── kafka (StatefulSet, 6 nodes)
  │   └── redis-cluster (StatefulSet, 6 nodes)
  │
  └── cicd-monitoring/      ← observability stack
      ├── prometheus
      ├── grafana
      ├── jaeger
      └── alertmanager
```

### Network Policies

```yaml
# Runners cannot access control plane services directly
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: isolate-runners
  namespace: cicd-runners
spec:
  podSelector: {}
  policyTypes: [Ingress, Egress]
  ingress: []  # No inbound to runners
  egress:
  - to:
    - namespaceSelector:
        matchLabels:
          name: cicd-control
      podSelector:
        matchLabels:
          app: secret-service    # Runners ONLY talk to Secret Service
  - to:
    - namespaceSelector:
        matchLabels:
          name: cicd-control
      podSelector:
        matchLabels:
          app: log-service       # And Log Service
  - to:
    - ipBlock:
        cidr: 0.0.0.0/0
        except: [169.254.169.254/32]  # Internet (for npm, apt) but NOT metadata API
```

---

## Service Deployment Specs

### Webhook Service

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: webhook-service
  namespace: cicd-control
spec:
  replicas: 3
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0  # zero-downtime during deploys
  template:
    spec:
      containers:
      - name: webhook-service
        image: cicd/webhook-service:1.2.3
        resources:
          requests: {cpu: "500m", memory: "512Mi"}
          limits: {cpu: "2", memory: "1Gi"}
        env:
        - name: KAFKA_BOOTSTRAP_SERVERS
          valueFrom:
            configMapKeyRef: {name: kafka-config, key: bootstrap-servers}
        - name: DATABASE_URL
          valueFrom:
            secretKeyRef: {name: db-credentials, key: url}
        readinessProbe:
          httpGet: {path: /health/ready, port: 8080}
          periodSeconds: 5
        livenessProbe:
          httpGet: {path: /health/live, port: 8080}
          periodSeconds: 10
```

### Secret Service (Hardened)

```yaml
spec:
  replicas: 3
  template:
    spec:
      serviceAccountName: secret-service-sa  # minimal K8s RBAC
      securityContext:
        runAsNonRoot: true
        runAsUser: 10001
        readOnlyRootFilesystem: true
      containers:
      - name: secret-service
        image: cicd/secret-service:1.2.3
        ports:
        - containerPort: 9090  # gRPC only, no HTTP
        resources:
          requests: {cpu: "1", memory: "1Gi"}
          limits: {cpu: "2", memory: "2Gi"}
        env:
        - name: KMS_KEY_ID
          valueFrom:
            secretKeyRef: {name: kms-config, key: key-id}
```

### Runner Pool (Dynamic)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: runner-pool
  namespace: cicd-runners
spec:
  replicas: 10  # base pool; HPA adjusts
  template:
    spec:
      serviceAccountName: runner-sa
      automountServiceAccountToken: false  # runners don't need K8s API access
      securityContext:
        runAsNonRoot: true
        runAsUser: 1000
      containers:
      - name: runner-agent
        image: cicd/runner-agent:1.2.3
        resources:
          requests: {cpu: "2", memory: "4Gi"}
          limits: {cpu: "4", memory: "8Gi"}
        env:
        - name: JOB_QUEUE_BOOTSTRAP_SERVERS
          value: "kafka.cicd-data.svc:9092"
        - name: SECRET_SERVICE_ENDPOINT
          value: "secret-service.cicd-control.svc:9090"
        # Runner gets job token via JobDispatchEvent — no static credentials
      tolerations:
      - key: "cicd-runner"
        operator: "Exists"
        effect: "NoSchedule"
      nodeSelector:
        workload-type: cicd-runner  # dedicated runner nodes
```

---

## Node Pools

```
Node Pool: control-plane
  Machine type: 4 CPU / 8 GB RAM
  Count: 5–10 (cluster autoscaler)
  Taint: cicd-control=true:NoSchedule
  Runs: all cicd-control namespace pods

Node Pool: runner-pool  
  Machine type: 4 CPU / 8 GB RAM (matches runner resource request)
  Count: 10–10,000 (cluster autoscaler, aggressive scale-up)
  Taint: cicd-runner=true:NoSchedule
  Runs: cicd-runners namespace pods only
  Spot/Preemptible: YES (cost reduction — jobs re-queue on preemption)
```

**Spot instance handling for runners:**
Kubernetes SIGTERM on spot preemption → runner agent receives SIGTERM → graceful shutdown in 30s → reports job status `PREEMPTED` to Scheduler → Scheduler re-queues job. No data loss (logs partially flushed to S3 before termination).

---

## CI/CD for the Platform (Dogfooding)

The CI/CD platform runs its own CI/CD pipelines for deployments:

```yaml
# .github/workflows/deploy.yml (platform uses itself)
name: Deploy CI/CD Platform

on:
  push:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-22.04
    steps:
    - uses: checkout@v3
    - run: ./gradlew test
    - uses: upload-artifact@v2
      with: {name: test-results, path: build/reports/}

  build:
    needs: [test]
    runs-on: ubuntu-22.04
    steps:
    - uses: checkout@v3
    - run: docker buildx build --push -t cicd/webhook-service:${{ commitSha }} .
    
  deploy-staging:
    needs: [build]
    environment: staging
    runs-on: ubuntu-22.04
    steps:
    - uses: checkout@v3
    - run: helm upgrade --install cicd-staging ./helm --set image.tag=${{ commitSha }}
    - run: ./scripts/smoke-test.sh https://staging.cicd.example.com

  deploy-production:
    needs: [deploy-staging]
    environment: production
    runs-on: ubuntu-22.04
    steps:
    - run: helm upgrade cicd-production ./helm --set image.tag=${{ commitSha }}
         --atomic --timeout 5m
```

---

## Rolling Upgrade Strategy

### Blue-Green for Control Plane Services

Control plane services are stateless — full blue-green is practical:

```
Current: webhook-service v1.2.3 (3 pods)
Deploy: webhook-service v1.2.4

1. Deploy v1.2.4 pods (3 new pods, not yet receiving traffic)
2. Health checks pass on new pods
3. Traffic switch: update Service selector to v1.2.4 pods
4. Monitor: error rate on v1.2.4 for 5 minutes
5. Cleanup: delete v1.2.3 pods
6. Rollback: flip Service selector back to v1.2.3 (instant)
```

Implementation: Kubernetes Deployment rolling update with `maxUnavailable=0, maxSurge=3`.

### Canary for High-Risk Changes

For Scheduler upgrades (stateful leader election):

```
1. Deploy Scheduler v2 as a 2nd Deployment (3 replicas, no active leader yet)
2. Scheduler v2 instances: standby only (wait for leader lock)
3. Shut down 1 Scheduler v1 pod → v2 standby acquires leader lock
4. Monitor: is v2 dispatching jobs correctly? (5 minute window)
5. Shut down remaining v1 pods
6. Rollback: shut down v2, restart v1 (acquires leader lock immediately)
```

### Runner Upgrades (No-Downtime)

Runners are ephemeral — upgrade is natural:
1. Deploy new runner image to `runner-pool` Deployment
2. HPA scales down idle pods (old image)
3. New runners start with new image on next scale-up
4. In-progress jobs on old-image pods complete normally
5. Drain: after 10 minutes, manually cordon old-image nodes → no new pods scheduled

---

## Environment Separation

```
Environment: production
  Cluster: cicd-production (dedicated cluster)
  Database: RDS PostgreSQL Multi-AZ
  Kafka: MSK (managed, 3 AZs)
  Domain: api.cicd.example.com
  
Environment: staging
  Cluster: cicd-staging (shared with QA)
  Database: RDS PostgreSQL single AZ (cost)
  Kafka: self-managed 3-node (reduced redundancy)
  Domain: api.staging.cicd.example.com
  
Environment: development
  Docker Compose (local)
  PostgreSQL single container
  Kafka single container
  No Kubernetes
```

---

## Local Development Setup

```yaml
# docker-compose.yml
version: '3.8'
services:
  postgres:
    image: postgres:15
    environment:
      POSTGRES_DB: cicd_dev
      POSTGRES_PASSWORD: devpassword
    ports: ["5432:5432"]
    volumes: ["postgres-data:/var/lib/postgresql/data"]

  redis:
    image: redis:7-alpine
    ports: ["6379:6379"]

  kafka:
    image: confluentinc/cp-kafka:7.5.0
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
    ports: ["9092:9092"]
    depends_on: [zookeeper]

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    ports: ["2181:2181"]

  minio:
    image: minio/minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports: ["9000:9000", "9001:9001"]
    # MinIO = local S3-compatible storage

  scheduler:
    build: ./scheduler
    depends_on: [postgres, kafka]
    environment:
      DATABASE_URL: postgresql://postgres:devpassword@postgres:5432/cicd_dev
      KAFKA_BOOTSTRAP: kafka:9092

  webhook-service:
    build: ./webhook-service
    ports: ["8080:8080"]
    depends_on: [kafka]
```

**Developer workflow:**
1. `docker-compose up` — full local stack
2. `ngrok http 8080` — expose local webhook endpoint to GitHub
3. Configure webhook in GitHub repo settings → `https://xyz.ngrok.io/webhooks/github`
4. Push to GitHub → webhook fires locally → full pipeline executes with local runner

---

## Helm Chart Structure

```
helm/cicd/
  Chart.yaml
  values.yaml               ← default values
  values-staging.yaml
  values-production.yaml
  templates/
    webhook-deployment.yaml
    scheduler-deployment.yaml
    runner-deployment.yaml
    runner-hpa.yaml
    secrets-config.yaml
    network-policies.yaml
    service-accounts.yaml
```

**Helm release management:**
```bash
# Staging deploy
helm upgrade --install cicd-staging ./helm \
  -f values-staging.yaml \
  --set image.tag=abc123 \
  --namespace cicd-control

# Production deploy with rollback safety
helm upgrade cicd-production ./helm \
  -f values-production.yaml \
  --set image.tag=abc123 \
  --atomic --timeout 5m \  # auto-rollback if deployment fails
  --namespace cicd-control
```

---

## Tradeoffs

| Decision | Why | Cost |
|---|---|---|
| Dedicated runner node pool | Page cache isolation; predictable performance | Higher cost; cluster autoscaler complexity |
| Spot instances for runners | 70% cost reduction | Preemption → job re-queue (acceptable if idempotent) |
| Blue-green for control plane | Instant rollback capability | Double resource usage during cutover (brief) |
| Namespace separation (control vs runners) | Network isolation; RBAC boundaries | Multi-namespace complexity |
| Dogfooding (platform runs its own CI) | Validates platform with real-world use | If platform is broken, can't deploy fix via platform; emergency deploy path needed |

---

## Interview Discussion Points

- **How do you deploy the CI/CD platform itself?** Dogfooding: platform uses itself. Emergency path: `kubectl apply -f` directly with cluster admin credentials (used only when platform is broken)
- **What is the PodDisruptionBudget for control plane?** `minAvailable: 2` for all 3-replica services. Ensures rolling upgrade/node maintenance never takes more than 1 pod offline simultaneously
- **How do you handle secrets in Kubernetes for the platform services?** External secrets operator (ESO) pulls from AWS Secrets Manager → creates K8s Secrets. Rotated values automatically synced. Never store secrets in Helm values files or Git
- **What is the maximum runner scale and what limits it?** K8s cluster autoscaler max node count (e.g., 500 nodes × 20 runners/node = 10,000 runners). Beyond that: multi-cluster or cloud provider limits. AWS: up to 5000 nodes per EKS cluster
- **How do you handle a bad deploy that breaks the platform?** HPA detects increased error rate → auto-rollback if using Helm `--atomic`. Manual rollback: `helm rollback cicd-production` → rolls back to previous release (stored in K8s secrets by Helm)
