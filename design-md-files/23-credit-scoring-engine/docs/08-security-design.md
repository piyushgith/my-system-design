# 08 — Security Design: Credit Scoring Engine

---

## Objective

Design the security model for a regulated credit scoring system. Covers authentication, authorization, data protection, model security, consent enforcement, audit logging, and regulatory compliance (RBI Credit Information Companies Regulations, ECOA, DPDP Act 2023).

---

## Threat Model

| Threat Actor | Attack Vector | Impact |
|---|---|---|
| External API caller | Unauthenticated score requests | Free scoring, model extraction |
| Malicious internal service | Scoring without consent | Regulatory violation, fine |
| Attacker with DB access | Read feature snapshots containing PII | Privacy breach |
| Model extraction attack | High-volume repeated queries | Model IP theft, scoring model reverse-engineered |
| Insider threat | Export score history for all users | Mass data exfiltration |
| Race condition attacker | Parallel score requests to manipulate model routing | Challenger model targeted queries |

---

## Authentication

### Service-to-Service (mTLS)

All internal service calls use mTLS via Istio service mesh:
- Every pod issues a SPIFFE/X.509 identity certificate
- Istio enforces mTLS for all east-west traffic (scoring → Redis, scoring → PostgreSQL, scoring → Kafka)
- Certificates rotated every 24 hours automatically

### External Caller Authentication (JWT + OAuth2)

Loan Service, Card Service, CRM authenticate via JWT:

```
OAuth2 Client Credentials flow:
  1. Caller → Identity Provider: client_credentials grant
  2. Identity Provider → Caller: JWT {sub: loan-service, scope: scoring:read, exp: +1h}
  3. Caller → Scoring API: Authorization: Bearer <JWT>
  4. Scoring API → Validate: signature, exp, scope, client_id
```

**JWT claims checked on every request:**
- `sub`: calling service identity
- `scope`: must include required scope for endpoint
- `exp`: not expired (clock skew tolerance: 30 seconds)
- `consent_verified`: bureau consent flag (checked against Consent Management service)

---

## Authorization (RBAC Scopes)

| Scope | Who Has It | Allows |
|---|---|---|
| `scoring:read` | Loan Service, Card Service | POST /scores, GET /scores/{id} |
| `scoring:read_history` | Compliance Team, Audit Service | GET /scores/history |
| `scoring:read_explainability` | Data Science Team | SHAP values in response |
| `scoring:read_adverse_action` | Loan Service, CRM | GET /scores/{id}/adverse-action-notice |
| `scoring:admin` | Data Science, Ops | GET /features/{user_id}, feature inspection |
| `scoring:model_admin` | Risk Team, ML Platform | POST /models/{v}/promote, model registry writes |
| `scoring:batch` | Batch Job Service Account | POST /scores batch endpoint |

**Principle of least privilege:** Loan Service has `scoring:read` only. It cannot access raw feature values, SHAP explanations, or the model registry.

---

## Consent Enforcement

Bureau data (CIBIL score, DPD history, etc.) can only be used with valid user consent per RBI KYC Master Direction and DPDP Act.

```
ConsentCheckService.validate(userId, BUREAU_DATA_CONSENT)
├── Check Consent Management Service: hasValidConsent(userId, consent_type=BUREAU)
├── Check consent_expiry > now()
├── Check consent_ref_id matches request.consent_reference_id
└── On invalid consent:
    → Exclude ALL bureau.* features from FeatureVector
    → Set is_thin_file=true (bureau features unavailable)
    → Return "no-bureau" score (lower than bureau-backed score, typically)
    → Store consent_ref_id=null in score_history
```

**Consent token lifetime:** 6 months. After expiry, bureau features silently excluded. Caller receives `X-Bureau-Consent-Status: EXPIRED` header — loan service responsible for triggering re-consent flow.

---

## Data Protection

### Feature Store Data Classification

| Feature Group | PII? | Protection |
|---|---|---|
| `bureau.*` (CIBIL score, DPD, utilization) | No (numerical signals) | None beyond Redis AUTH |
| `behavior.*` (UPI count, credit amounts) | Pseudonymous (user_id only) | user_id as pseudonym |
| `meta.*` (bureau_as_of, is_thin_file) | No | None |

**Principle:** no PII (name, PAN, Aadhaar, address) stored in feature store. Only numerical aggregates.

### score_history Data Protection

`feature_snapshot` JSONB contains feature values — no PII. `reason_codes` JSONB contains standardized codes — no PII.

Exception: `consent_ref_id` is a reference to the consent record. This is a foreign key, not PII itself.

### PostgreSQL Column Encryption

Model registry contains S3 paths and `approved_by` (approver name — PII). `approved_by` stored as plaintext (internal operational data, not customer PII). No additional column encryption required beyond database-level encryption at rest (AWS RDS encryption with KMS-managed keys).

---

## Model Security

### Model Extraction Attack Prevention

An attacker submitting thousands of carefully crafted score requests can reconstruct the scoring model's decision boundary (model inversion attack).

**Countermeasures:**

1. **Rate limiting per caller:** Loan Service 200 RPS, but anomaly detection on score request patterns (repeated requests for synthetic user_ids not in customer base)
2. **No raw_pd in API response:** only the 300–900 score is returned. Harder to reverse-engineer the exact probability threshold
3. **No SHAP values by default:** requires `scoring:read_explainability` scope. SHAP values directly reveal feature importance weights
4. **Request anomaly detection:** alert if any caller sends > 10K score requests for non-existent user_ids within 1 hour (model probing pattern)
5. **Model file never exposed:** ONNX model files in S3 with no public access. Pre-signed URLs expire in 60 seconds (for hot-reload only). IAM policy: only scoring engine pod role can access model S3 bucket

### Model Integrity

- ONNX model file SHA-256 hash stored in `model_registry.s3_model_path` (alongside path)
- On hot-reload: scoring engine validates downloaded file SHA-256 against registry before loading
- Prevents model file tampering in S3 (supply chain attack)

---

## Audit Logging

### Regulatory Audit Requirements

| Event | Retention | Required by |
|---|---|---|
| Every score computation | 7 years | RBI Credit Information Companies Regulations |
| Every adverse action issued | 7 years | ECOA (India: Credit Information Act 2005) |
| Model promotion/retirement | Indefinite | Risk management policy |
| Feature store access (admin endpoints) | 3 years | DPDP Act 2023 |
| Consent usage per score | 7 years | RBI KYC Master Direction 2016 |

### Audit Kafka Topic

```
Topic: credit.audit.log
Partitions: 20 (high write throughput)
Retention: 10 years (long-lived topic for compliance)
Replication: 3
```

**Audit event schema:**
```json
{
  "event_id": "uuid",
  "event_type": "SCORE_COMPUTED | SCORE_ACCESSED | FEATURE_INSPECTED | MODEL_PROMOTED",
  "actor": {
    "service": "loan-service",
    "user_id": "usr_abc123",
    "request_id": "req_uuid"
  },
  "resource": {
    "type": "CREDIT_SCORE",
    "id": "req_uuid"
  },
  "outcome": "SUCCESS | DENIED",
  "consent_ref_id": "consent_uuid",
  "model_version": "xgb-v2.3.1",
  "timestamp": "2024-01-15T10:30:00Z",
  "metadata": {
    "score": 750,
    "product_type": "PERSONAL_LOAN"
  }
}
```

All `scoring:admin` endpoint access logged with actor identity. Feature store inspection audited separately — compliance teams can detect unauthorized feature snooping.

---

## Secrets Management

| Secret | Store | Rotation |
|---|---|---|
| PostgreSQL credentials | AWS Secrets Manager | 90 days, auto-rotated |
| Redis AUTH password | AWS Secrets Manager | 90 days |
| Kafka SASL credentials | AWS Secrets Manager | 90 days |
| JWT public keys | AWS Secrets Manager | On rotation by Identity Provider |
| S3 model bucket access | IAM role (no credentials) | N/A (role assumption) |
| ONNX model SHA hashes | model_registry table | Updated on each model upload |

**No secrets in environment variables or pod specs.** AWS Secrets Manager CSI driver mounts secrets as files in pod filesystem.

---

## Network Security

### Egress Controls

- Scoring engine pods: egress only to Redis, PostgreSQL, Kafka, S3, Identity Provider
- No direct internet egress (bureau API calls go through Bureau Integration Service — separate network segment)
- Kubernetes NetworkPolicy: deny-all default, allow-list specific pod-to-pod connections

### API Gateway Security

- TLS termination at API Gateway (ALB with ACM certificate)
- WAF rules: block SQL injection patterns in request body, block unusually large SHAP value requests
- DDoS protection: AWS Shield Standard
- Per-IP rate limiting at gateway level (before JWT validation): 1000 req/min per IP

---

## Regulatory Compliance Summary

| Regulation | Requirement | Implementation |
|---|---|---|
| RBI Credit Information Companies (Regulation) Act 2005 | Bureau data only with consent | ConsentCheckService enforces per request |
| DPDP Act 2023 | Right to access (feature data) | `scoring:admin` endpoint with audit log |
| DPDP Act 2023 | Data minimization | No PII in feature store (only numerical aggregates) |
| ECOA (India: Consumer Protection) | Adverse action reason codes | Mandatory ReasonCode in declined score response |
| RBI KYC Master Direction 2016 | Consent expiry | 6-month consent TTL enforced |
| IT Act 2000 / Data Localization | Data in India | All infra in ap-south-1 (Mumbai) |

---

## Interview Discussion Points

- **Why is raw_pd not returned in the API response?** Two reasons: (1) raw probability values are model proprietary information — returning raw_pd allows an attacker to more precisely reverse-engineer the model's decision function; (2) regulatory compliance: consumer-facing credit decisions use the 300–900 scale (aligned with CIBIL convention). raw_pd is internal model performance monitoring only, stored in score_history but never exposed
- **How do you enforce consent without adding latency to the scoring path?** Consent validation is pre-checked during feature assembly (not a blocking call to Consent Service on hot path). Consent state is cached in Redis: `consent:{user_id}:bureau TTL=5min`. If cached consent valid → assemble bureau features. If not cached → synchronous call to Consent Service (adds ~5ms, rare). Consent expiry events invalidate the Redis cache entry via Kafka consumer
- **What is the model promotion approval chain?** Model promotion requires: (1) data science team generates validation report (Gini, KS, PSI); (2) risk team reviews and signs off (approval_token issued by risk portal); (3) engineering submits `POST /models/{v}/promote` with approval_token; (4) API validates approval_token is unused + not expired (24-hour window). The approval_token is single-use — prevents replay attacks on model promotion
- **How do you detect insider threats accessing score data?** All `scoring:admin` and `scoring:read_history` calls are logged to `credit.audit.log` with actor identity. Monthly audit: Elasticsearch aggregation on admin endpoint access per actor. Alert: any actor accessing > 1000 distinct user profiles via admin endpoint per day triggers security review. SIEM integration for real-time alerting
