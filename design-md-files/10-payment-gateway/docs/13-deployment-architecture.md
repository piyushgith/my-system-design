# 13 — Deployment Architecture: Payment Gateway / Wallet System

---

## Objective

Define deployment strategy for a payment system where deployment-related downtime or failures have direct financial impact — covering zero-downtime deployments, strict environment controls, PCI DSS-compliant infrastructure, and disaster recovery.

---

## PCI DSS Deployment Constraints

PCI DSS imposes strict requirements on how payment systems are deployed:

| Requirement | Implication |
|---|---|
| Cardholder Data Environment (CDE) isolation | Separate network segment for payment services |
| Change management | Every production change documented and approved |
| No direct access to production | Jump box + MFA required for production access |
| File integrity monitoring | Detect unauthorized changes to production systems |
| Quarterly vulnerability scans | External ASV scans required |
| Annual penetration test | Red team engagement |

These constraints make payment deployments slower and more controlled than typical web services. This is intentional.

---

## Environment Strategy

| Environment | Purpose | Access | Data |
|---|---|---|---|
| Local | Development | Developer workstation | Mock payment APIs, H2 DB |
| Development | Integration testing | Dev team | Test payment gateway sandbox, dev DB |
| Staging | Pre-production validation | Tech lead + QA | Staging payment gateway (no real money), anonymized data |
| Production | Live system | SRE + authorized ops | Real money, PCI-compliant |

### Staging Must Match Production

Staging payment gateway:
- Stripe Test Mode (Stripe sandbox with test card numbers)
- Full webhook delivery to staging
- Same Kafka topics (prefixed `staging.`)
- Same Redis Cluster configuration (smaller scale)

A bug in staging payment flow = will be a bug in production. No exceptions.

---

## Network Architecture (CDE Isolation)

```
Internet
    │
    ▼
[WAF + DDoS Protection] (Cloudflare / AWS WAF)
    │
    ▼
[Public ALB] (TLS termination, certificate pinning enforced)
    │
    ▼ (HTTPS only, port 443)
[API Pods — Payment API] ← Non-CDE network segment
    │
    ▼ (restricted internal)
[CDE Network Segment] ← Cardholder Data Environment
    ├── Tokenization service (card number → token)
    ├── Postgres (payment data, ledger)
    ├── HSM (key management)
    └── PCI Audit Log store

[Non-CDE Services]
    ├── Notification service
    ├── Analytics service
    ├── Merchant portal
    └── Fraud scoring service
```

CDE rules:
- No internet access from CDE (only outbound to card networks via private link)
- No SSH to CDE machines directly — jump box only
- All CDE access logged and audited
- Payment API pods are NOT in CDE — they call tokenization service but never handle raw PAN

---

## Kubernetes Setup

### Namespaces

```
namespace: payments-prod
  ├── payment-api (5 pods)
  ├── wallet-service (3 pods)
  ├── fraud-service (3 pods)
  ├── notification-service (2 pods)
  └── settlement-service (2 pods)

namespace: payments-infra
  ├── pgbouncer (3 pods)
  └── kafka-consumers (configurable replicas)

namespace: monitoring
  ├── prometheus
  ├── grafana
  ├── jaeger
  └── alertmanager
```

### Pod Security

- All pods run as non-root user
- Read-only root filesystem where possible
- Network policies: pods can only communicate with explicitly allowed destinations
- No privileged containers

### Resource Limits

| Service | CPU Request | CPU Limit | Memory Request | Memory Limit |
|---|---|---|---|---|
| payment-api | 500m | 2000m | 512Mi | 2Gi |
| wallet-service | 500m | 1000m | 512Mi | 1Gi |
| fraud-service | 250m | 500m | 256Mi | 512Mi |
| notification-service | 250m | 500m | 256Mi | 512Mi |

Hard limits prevent one service from starving others (noisy neighbor problem).

---

## CI/CD Pipeline

```
Developer pushes feature branch
        │
        ▼
GitHub Actions (automated):
  ├── Unit tests
  ├── Integration tests (Testcontainers — real Postgres, real Redis)
  ├── Contract tests (Pact — payment gateway API contracts)
  ├── Security scan (Trivy, Snyk)
  ├── SAST (SonarQube — code security analysis)
  └── License compliance check
        │ (all pass)
        ▼
Pull Request:
  ├── Code review (2 approvers required for payment service)
  ├── Security review (for any payment flow changes)
  └── Architecture review (for major changes)
        │ (approved + merged to main)
        ▼
Build Pipeline:
  ├── Build Docker image
  ├── Tag: {service}:{git-sha}
  ├── Push to ECR
  ├── Container image scan (ECR scan + Trivy)
  └── Sign image (Cosign) — verify in deployment
        │
        ▼
Deploy Development (automatic):
  └── Helm upgrade; smoke tests
        │
        ▼
Deploy Staging (automatic):
  ├── Helm upgrade
  ├── Smoke tests
  └── Payment flow integration tests (test card, test webhook)
        │
        ▼
Change Advisory Board (CAB) Review:
  ├── PCI DSS change documentation
  ├── Risk assessment
  └── Rollback plan documented
        │ (approved)
        ▼
Deploy Production (scheduled maintenance window):
  └── Canary: 5% → 25% → 100%
      Monitor 30min at each step
      Auto-rollback on payment success rate < 98%
```

**CAB review** is not bureaucracy — it's PCI DSS requirement. Every production change must be documented with risk assessment and rollback plan.

---

## Deployment Strategy: Zero-Downtime Mandatory

Payment systems must deploy with zero downtime. "Scheduled maintenance" means 99.99% SLA breach.

### Canary Deployment (Standard)

```
Production state: v1 (100% traffic)

Step 1: Deploy v2 as canary (5% traffic)
  Monitor 30 minutes:
  - Payment success rate: must remain > 99.5%
  - No double charges (0 tolerance)
  - Latency: p99 must remain < 3s

Step 2: Expand to 25% traffic
  Monitor 30 minutes (same checks)

Step 3: 50% → 100%

Automatic rollback trigger:
  - Payment success rate drops below 99% for 2 minutes
  - Any double charge detected
  - p99 latency > 5s for 5 minutes
  → Immediately route 100% to v1
```

### Database Migration Strategy (Most Critical)

Payment DB migrations are the highest-risk operations:

**Rules**:
1. **Never drop columns** in same release as code change
2. **Never add NOT NULL column** without default to existing table
3. **Expand-contract only**:
   - Add new column (nullable)
   - Deploy code that writes to both old + new column
   - Backfill existing rows
   - Deploy code that reads from new column
   - Drop old column (separate release, weeks later)
4. **Test migration timing**: run on prod-size data copy in staging first
5. `CREATE INDEX CONCURRENTLY`: never blocking index creation
6. Long-running migrations: run during lowest traffic window (3-5 AM)

**Ledger table migrations are especially dangerous** — any locking on ledger_entries means payments can't be recorded.

---

## Blue-Green Deployment (Major Releases)

For major architectural changes (new payment flow, new ledger structure):

```
Blue (current): handles all traffic
Green (new): deployed, zero traffic

Validation:
  1. Run synthetic payment tests against green (test mode)
  2. QA manual validation
  3. Security review of changes
  4. PCI DSS change documentation complete

Cutover:
  - Route 1% real traffic to green (canary phase)
  - If clean: full cutover via ALB target group switch
  - Keep blue running for 24h (rollback ready)
  - Blue decommissioned after 24h

Rollback:
  - Switch ALB target group back to blue: < 30 seconds
```

---

## Disaster Recovery

### RTO / RPO Targets

| Failure | RTO (Recovery Time) | RPO (Data Loss) |
|---|---|---|
| Single pod failure | < 30s (K8s reschedule) | 0 |
| AZ failure | < 60s (K8s multi-AZ) | 0 |
| Region failure | < 30 minutes | < 30 seconds (replication lag) |
| Postgres primary failure | < 60s (RDS Multi-AZ) | 0 |
| Complete region loss | < 4 hours | < 5 seconds |

### DR Runbook

1. Detect: CloudWatch alarm, Route 53 health check failure
2. Declare DR: SRE + management decision
3. Activate secondary region: promote read replica to primary (RDS failover)
4. Update DNS: Route 53 health check already routes to secondary
5. Validate: synthetic payment test in secondary
6. Communicate: status page update, merchant notification

### Backup Strategy

| Data | Backup Method | Frequency | Retention |
|---|---|---|---|
| Postgres | RDS automated snapshots + PITR | Continuous | 35 days |
| Kafka | Multi-AZ replication + S3 backup | Continuous | 90 days |
| Audit logs | S3 with Object Lock | Write-time | 7 years |
| Configuration | Git (IaC) | Per commit | Forever |

---

## Secret Rotation in Production

PCI DSS requires regular rotation of cryptographic keys and credentials:

| Secret | Rotation Frequency | Method |
|---|---|---|
| DB passwords | 30 days | AWS Secrets Manager auto-rotation |
| API keys (merchant) | On compromise; annual default | Merchant-initiated via portal |
| Encryption keys (DEK) | 90 days | Envelope encryption — reencrypt DEK with new master key |
| Master key (HSM) | Annual | HSM ceremony with security officer present |
| TLS certificates | 90 days | ACM auto-renewal |

Application retrieves secrets from Secrets Manager on startup; supports hot-reload without restart.

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| CAB review for prod changes | PCI compliance, reduced risk | Slower deployment cadence |
| Canary with auto-rollback | Low blast radius | More complex deployment pipeline |
| CDE network isolation | PCI compliance | More complex network topology |
| Synchronous DB replication | RPO = 0 | Higher write latency (~10ms across AZs) |
| Image signing (Cosign) | Supply chain security | Build pipeline complexity |

---

## Interview Discussion Points

- **"How do you deploy to production without downtime?"** → Canary: 5% → 25% → 100% with automatic rollback on payment success rate drop; stateless pods enable instant traffic shifting
- **"What's your DR plan?"** → Active-passive: Route 53 health-check-based failover to secondary region; RDS Multi-AZ for DB; < 60s automated failover; 4h full region DR
- **"How do you handle DB migrations in a live payment system?"** → Expand-contract only: add nullable column first, backfill, switch reads, drop old column in separate release; test migration timing on prod-size data
- **"Why do you need a CAB review?"** → PCI DSS requirement; also: payment system changes affect real money; second pair of eyes catches risks; rollback plan documented before every change
- **"How do you rotate encryption keys without downtime?"** → Envelope encryption: DEK encrypted with master key; rotate master key → re-encrypt DEKs; existing data readable with old DEK until re-encrypted
