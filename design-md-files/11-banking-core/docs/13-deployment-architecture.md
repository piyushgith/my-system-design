# 13 — Deployment Architecture: Banking Core System

---

## Objective

Define deployment strategy for a banking system where availability is a regulatory requirement, where deployments must be zero-downtime, and where change management is governed by RBI and internal audit requirements.

---

## Regulatory Deployment Constraints

Banking deployments operate under regulatory constraints:

| Requirement | Source | Implication |
|---|---|---|
| 24x7 availability | RBI internet banking guidelines | Zero-downtime deployments mandatory |
| Change management | RBI IT framework / ISO 27001 | Every production change documented, approved |
| Audit trail for changes | Internal audit | Deployment pipeline must be auditable |
| DR capability | RBI | Must have tested disaster recovery plan |
| Security patching SLA | RBI / CERT-In | Critical patches within 72h, high within 7 days |
| Data residency | RBI | All customer data must stay in India |

**Data residency**: AWS Mumbai (ap-south-1) region only for production. Backup region: AWS Hyderabad (ap-south-2). No data leaves India.

---

## Environment Strategy

| Environment | Purpose | Network | Data |
|---|---|---|---|
| Local | Developer testing | Localhost only | H2 / Docker Compose |
| SIT (System Integration Test) | Feature integration | Private VPC | Synthetic data only |
| UAT (User Acceptance Test) | Business validation | Private VPC | Anonymized prod data |
| Pre-Production | Final validation | Prod-mirror VPC | Masked prod data, prod volumes |
| Production | Live banking | Private VPC, CDE isolated | Real customer data |

### Pre-Production Must Match Production

Pre-prod requirements:
- Same Kubernetes version
- Same Postgres version (same minor version)
- Same Kafka version
- Same Redis version
- Same network topology (CDE isolation)
- Traffic volume: 10% of production (scale testing)

"Works in pre-prod" must predict "works in prod" with very high confidence.

---

## Change Management Process

Every production deployment follows Change Advisory Board (CAB) process:

```
1. Change Request raised (CRQ) — 5 business days before deployment
   - What: description of change
   - Why: business justification
   - Risk: risk assessment (Low/Medium/High)
   - Impact: affected systems, affected customers
   - Rollback: rollback plan documented
   - Test evidence: test results from SIT + UAT

2. Technical review — Engineering lead + Security team

3. CAB approval — IT Head + CISO + Operations Head

4. Change freeze check:
   - No deployments during: RBI audit periods, quarter-end, year-end processing
   - Restricted window: only emergency changes during festive season (Diwali, Holi)

5. Deployment — Approved window only

6. Post-deployment validation — 30-minute validation before closure

7. Change ticket closure — With deployment evidence, monitoring confirmation
```

Emergency change (P1 fix): condensed process with verbal approval → documented post-facto.

---

## Kubernetes Architecture

```
AWS EKS Cluster (ap-south-1)
├── Namespace: banking-prod
│   ├── account-service     (5 pods)
│   ├── transfer-service    (5 pods)
│   ├── maker-checker-svc   (3 pods)
│   ├── aml-service         (3 pods)
│   ├── notification-svc    (3 pods)
│   ├── statement-service   (2 pods)
│   └── batch-orchestrator  (1 pod — singleton with lease)
│
├── Namespace: banking-infra
│   ├── pgbouncer           (3 pods)
│   └── kafka-consumers     (variable)
│
└── Namespace: monitoring
    ├── prometheus, grafana, jaeger, alertmanager
    └── banking-reconciliation-jobs (CronJobs)

Managed Services:
  AWS RDS Postgres (Multi-AZ) — ap-south-1
  AWS ElastiCache Redis Cluster — ap-south-1
  AWS MSK Kafka — ap-south-1 (3 brokers, 3 AZs)
```

### Singleton Pattern for Batch Orchestrator

EOD batch orchestrator must run as exactly one instance:

```yaml
# Kubernetes leader election via lease
apiVersion: apps/v1
kind: Deployment
metadata:
  name: batch-orchestrator
spec:
  replicas: 3          # 3 pods, but only 1 is "leader" via lease
  # Leader election: first pod acquires lease; others are hot standby
  # On leader crash: standby acquires lease in < 30s
  # Prevents duplicate EOD batch execution
```

---

## CI/CD Pipeline (Banking-Grade)

```
Developer pushes to feature branch
        │
        ▼
Automated gates:
  ├── Unit tests (must pass: 100%)
  ├── Integration tests (Testcontainers)
  ├── Transaction integrity tests (ledger double-entry)
  ├── Security scan (SAST, dependency check)
  ├── Code coverage (minimum 85% for banking-critical paths)
  └── Contract tests (API backward compatibility)
        │ (all pass)
        ▼
Code Review:
  ├── 2 approvers required (senior engineer + tech lead)
  ├── Security review for any auth/encryption change
  └── Compliance review for any regulatory flow change
        │
        ▼
Deploy to SIT (auto):
  └── Automated smoke tests + transaction flow tests
        │
        ▼
UAT (manual business validation):
  └── Business team validates banking flows
        │
        ▼
Pre-Production (auto deploy + load test):
  └── Gatling load tests + chaos testing
        │
        ▼
CAB Change Request (5 days in advance):
  └── Document change, risk, rollback plan
        │
        ▼ (CAB approved)
Production Deployment (approved window):
  └── Canary: 5% → 25% → 100% (with 30-min hold at each step)
        │
        ▼
Post-deployment validation (30 minutes):
  └── Smoke tests, monitoring dashboards, transaction test
```

---

## Deployment Strategy: Zero-Downtime Mandatory

### Canary Release (Standard Deployments)

```
Step 1: Deploy v2 to 5% of pods
  Monitor 30 minutes:
    - Transaction success rate > 99.9%
    - No ledger anomalies
    - No double debits
    - Latency within SLO

Step 2: 25% if Step 1 clean
  Monitor 30 minutes

Step 3: 50% → 100%

Automatic rollback trigger:
  - Transaction success rate drops below 99.5%
  - Ledger imbalance detected (any)
  - Double debit detected (any)
  → Route 100% to v1 immediately
```

### Blue-Green (Major Architecture Changes)

For: new ledger schema, new transaction flow, major service restructure.

```
Blue (current): 100% traffic
Green (new): deployed, 0% traffic

Validation steps:
  1. Transaction integrity tests against green (test accounts)
  2. Performance tests: 100% production load simulation
  3. CAB sign-off on green
  4. Compliance validation

Cutover:
  - Route 1% traffic to green (canary: 5 minutes)
  - Monitor: ledger integrity, success rate
  - 1% → 100% via ALB target group switch
  - Blue: kept for 24 hours (instant rollback capability)

Rollback:
  - ALB switch back to blue: < 30 seconds
```

---

## Database Migration Strategy

Banking DB migrations are the highest-risk operations.

### Migration Rules (Non-Negotiable)

1. **No data-destructive migrations**: never DROP TABLE or DROP COLUMN with data
2. **Expand-contract only**: always add before removing
3. **No blocking schema changes during business hours**: run during 11 PM - 5 AM window
4. **`CREATE INDEX CONCURRENTLY`**: always non-blocking index creation
5. **Test migration timing**: run against prod-size replica first; if > 30 minutes, plan accordingly
6. **Rollback script**: every migration has tested rollback SQL

### Migration Execution

```
11 PM (start of batch window):
  1. Run schema migration via Flyway (on maintenance pod)
  2. Migration monitored in real-time
  3. If migration fails or takes too long: rollback immediately
  4. Application deployment: after migration confirmed complete
  5. Test: post-migration transaction flow test
  6. If test fails: rollback deployment + migration
```

### Ledger Migration (Special Care)

Ledger table migrations are highest risk:

- Any lock on ledger_entries prevents transaction writes
- Strategy: add columns as nullable (no table scan, no lock)
- Backfill: update existing rows in chunks of 10,000 during off-hours
- Never a single `UPDATE ledger_entries SET new_col = ...` — this locks the entire table

---

## Disaster Recovery

### Business Continuity Requirements

| Scenario | RTO | RPO |
|---|---|---|
| Single pod failure | 30s (K8s reschedule) | 0 |
| AZ failure | 60s (K8s multi-AZ) | 0 |
| Postgres primary failure | 60s (RDS Multi-AZ) | 0 |
| Full region failure | 4 hours | 30 seconds |

### DR Site (AWS Hyderabad ap-south-2)

```
ap-south-1 (Mumbai — Primary):
  Active: all writes + reads
  
ap-south-2 (Hyderabad — DR):
  Passive: RDS read replica (< 30s replication lag)
  Passive: Kafka MirrorMaker 2 replication
  Passive: Redis replica (async)
  Standby: EKS cluster (pre-provisioned, no traffic)
```

### DR Activation Process

1. Detect: Mumbai region unavailable (Route 53 health check fails)
2. Decision: Incident Commander declares DR (not automatic — financial system)
3. Activate: promote Hyderabad RDS replica to primary
4. Update: Route 53 DNS to Hyderabad ALB
5. Validate: run transaction test suite against Hyderabad
6. Communicate: status page update, branch notification, RBI notification
7. Customer access: available after DNS propagation (~5 minutes)

**Why not automatic DR?** Banking requires human judgment before rerouting real money operations. False positive automatic DR could cause split-brain if Mumbai partially recovers.

---

## Secret Rotation (Production)

| Secret | Rotation | Method |
|---|---|---|
| Postgres passwords | 30 days | AWS Secrets Manager auto-rotation |
| Redis auth token | 90 days | Secrets Manager + zero-downtime rotation |
| Kafka SSL certificates | 90 days | PKI auto-renewal |
| Staff signing keys | On device replacement | YubiKey re-issue ceremony |
| Encryption master keys | Annual | Key ceremony with Security Officer |
| TLS certificates | 90 days | ACM auto-renewal |
| JWT signing key | 7 days | Vault PKI |

**Key rotation ceremony** (annual for master key):
- 2 Security Officers present
- Physical access to HSM
- Documented procedure
- Board-level notification

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Manual DR activation | Prevents split-brain | 4-hour RTO vs 60s for automatic |
| 5-day CAB lead time | Reduces deployment risk | Slower feature delivery |
| Canary with conservative triggers | Low blast radius | Longer deployment windows |
| Separate AZ data residency | RBI compliance | Higher inter-AZ data transfer cost |
| Batch in separate time window | No OLTP impact | Complexity of window management |

---

## Interview Discussion Points

- **"How do you deploy zero-downtime to a banking system?"** → Canary: 5% → 25% → 100% with automatic rollback on any ledger anomaly or transaction success rate drop; stateless services enable instant traffic shift
- **"Why is your DR RTO 4 hours for region failure?"** → Manual decision required for banking; false positive automatic DR with split-brain = double transactions. Human judgment is worth the 4-hour trade-off. During 4 hours: ATMs and UPI still work (separate systems).
- **"How do you handle DB migrations in a 24x7 system?"** → During batch window (11 PM - 5 AM); expand-contract pattern; CREATE INDEX CONCURRENTLY; test migration timing on prod-size replica; rollback script mandatory
- **"What's the CAB process and why?"** → Change Advisory Board: 5-day advance notice, risk assessment, rollback plan. RBI IT governance requirement. Also: second pair of eyes prevents costly mistakes on financial systems.
- **"How do you meet data residency requirements?"** → All infrastructure in ap-south-1 (Mumbai) with DR in ap-south-2 (Hyderabad). No cross-border data transfer. AWS GovCloud not needed; standard regions with VPC isolation sufficient.
