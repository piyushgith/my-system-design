# 04 — API Design: Credit Scoring Engine

---

## Objective

Define the REST and gRPC API for real-time scoring, batch scoring, score history, and model management. Address latency requirements, idempotency, and regulatory response fields.

---

## API Style

**Real-time scoring hot path:** gRPC — binary protocol, < 2ms serialization overhead, strongly typed feature vector contracts.

**Administrative endpoints:** REST — model registry, score history, reason code lookup. Human-readable for compliance teams.

---

## gRPC API (Hot Path)

```protobuf
service CreditScoringService {
    // Real-time single score
    rpc ComputeScore(ScoreRequest) returns (ScoreResponse);

    // Batch scoring (streaming)
    rpc ComputeScoreBatch(stream BatchScoreRequest) returns (stream BatchScoreResponse);

    // Score history query
    rpc GetScoreHistory(ScoreHistoryRequest) returns (ScoreHistoryResponse);
}

message ScoreRequest {
    string user_id = 1;
    string product_type = 2;       // PERSONAL_LOAN, CREDIT_CARD, etc.
    bool force_refresh = 3;         // bypass cache
    string consent_reference_id = 4; // bureau data consent token
    string request_id = 5;          // idempotency
}

message ScoreResponse {
    string request_id = 1;
    string user_id = 2;
    int32 score = 3;                // 300–900
    string score_band = 4;          // EXCELLENT, GOOD, FAIR, POOR
    string model_version = 5;
    repeated ReasonCode reason_codes = 6;
    bool is_thin_file = 7;
    string source = 8;              // REAL_TIME, CACHE, BATCH
    string computed_at = 9;         // ISO-8601
    map<string, double> shap_values = 10;  // explainability (optional, gated by scope)
}

message ReasonCode {
    string code = 1;
    string description = 2;
    string direction = 3;           // NEGATIVE, POSITIVE
    int32 rank = 4;
}
```

---

## REST API

### 1. Real-Time Score

#### `POST /api/v1/scores`

**Request:**
```json
{
  "user_id": "usr_abc123",
  "product_type": "PERSONAL_LOAN",
  "force_refresh": false,
  "consent_reference_id": "consent_uuid",
  "request_id": "req_loan_abc123"
}
```

**Response `200 OK`:**
```json
{
  "request_id": "req_loan_abc123",
  "user_id": "usr_abc123",
  "score": 750,
  "score_band": "EXCELLENT",
  "model_version": "xgb-v2.3.1",
  "product_type": "PERSONAL_LOAN",
  "reason_codes": [
    {
      "code": "03",
      "description": "Too many recent credit inquiries",
      "direction": "NEGATIVE",
      "rank": 1
    },
    {
      "code": "01",
      "description": "Delinquency on accounts",
      "direction": "POSITIVE",
      "rank": 2
    }
  ],
  "is_thin_file": false,
  "source": "REAL_TIME",
  "computed_at": "2024-01-15T10:30:00.015Z",
  "feature_freshness": {
    "bureau": "2024-01-01T00:00:00Z",
    "behavioral": "2024-01-15T00:00:00Z"
  }
}
```

**Note:** `shap_values` not returned by default — requires `scoring:read_explainability` scope (adds 1–2ms to response). Requested via header: `X-Include-Shap: true`.

**Latency targets:**
- Cache hit: < 2ms
- Cache miss (real-time compute): < 30ms P50, < 200ms P99

---

### 2. Score History

#### `GET /api/v1/scores/history?user_id={id}&from={date}&to={date}&limit=20&cursor={cursor}`

Returns historical scores for a user. Requires `scoring:read_history` scope.

**Response `200 OK`:**
```json
{
  "user_id": "usr_abc123",
  "items": [
    {
      "request_id": "req_uuid",
      "score": 750,
      "model_version": "xgb-v2.3.1",
      "product_type": "PERSONAL_LOAN",
      "computed_at": "2024-01-15T10:30:00Z",
      "source": "REAL_TIME"
    }
  ],
  "next_cursor": "abc123"
}
```

**No feature_snapshot or SHAP values in history listing.** Full detail (with feature snapshot) available via:

#### `GET /api/v1/scores/{request_id}`

Returns full score detail including feature snapshot for audit/compliance use.

---

### 3. Feature Store Inspection (Admin)

#### `GET /api/v1/features/{user_id}`

Returns the current feature store values for a user. Requires `scoring:admin` scope.

**Response:**
```json
{
  "user_id": "usr_abc123",
  "features": {
    "bureau.cibil_score": 720,
    "bureau.dpd_last_6m": 0,
    "bureau.credit_utilization": 0.35,
    "behavior.upi_txn_count_last_30d": 45,
    "behavior.avg_monthly_credit": 85000,
    "performance.current_emi_dpd": 0
  },
  "is_thin_file": false,
  "bureau_as_of": "2024-01-01T00:00:00Z",
  "behavior_as_of": "2024-01-15T00:00:00Z"
}
```

Used by: data science team (debugging model decisions), compliance team (explaining declined applications).

---

### 4. Model Registry

#### `GET /api/v1/models`

List all model versions and their current roles.

**Response:**
```json
{
  "models": [
    {
      "model_version": "xgb-v2.3.1",
      "role": "CHAMPION",
      "product_types": ["PERSONAL_LOAN", "CREDIT_CARD"],
      "deployed_at": "2024-01-01T00:00:00Z",
      "champion_since": "2024-01-01T00:00:00Z"
    },
    {
      "model_version": "xgb-v2.4.0",
      "role": "CHALLENGER",
      "challenger_traffic_pct": 10,
      "deployed_at": "2024-01-10T00:00:00Z"
    }
  ]
}
```

#### `POST /api/v1/models/{model_version}/promote`

Promote a challenger to champion. Requires `scoring:model_admin` scope + risk team approval token.

**Request:**
```json
{
  "approval_token": "risk_approval_uuid",
  "promotion_reason": "Challenger showed 3% better Gini coefficient over 45 days"
}
```

---

### 5. Adverse Action Notice

#### `GET /api/v1/scores/{request_id}/adverse-action-notice`

Returns the regulatory-compliant adverse action notice for a declined application. Requires `scoring:read_adverse_action` scope.

**Response:**
```json
{
  "request_id": "req_uuid",
  "user_id": "usr_abc123",
  "score": 520,
  "decision": "DECLINED",
  "adverse_action_reasons": [
    "Too many delinquencies on accounts in the past 24 months",
    "High utilization of revolving credit accounts",
    "Insufficient length of credit history"
  ],
  "generated_at": "2024-01-15T10:30:00Z",
  "regulatory_reference": "ECOA 12 CFR Part 202 / RBI KYC Master Direction 2016"
}
```

---

## Idempotency

| Operation | Idempotency Key | Behavior on Repeat |
|---|---|---|
| `POST /scores` | `request_id` field | Returns cached score if request_id seen within 24h |
| `POST /models/{v}/promote` | `approval_token` (single-use) | 409 if already promoted |
| Batch scoring job | `job_id` (batch job idempotency) | Skip already-computed users in same batch run |

---

## Error Handling

| HTTP Status | Error Code | Description |
|---|---|---|
| `400` | `MISSING_CONSENT` | Bureau data requested but no valid consent |
| `400` | `INVALID_PRODUCT_TYPE` | Product type not supported by current model |
| `404` | `USER_FEATURES_NOT_FOUND` | No feature profile for user (not onboarded) |
| `503` | `MODEL_NOT_LOADED` | Model inference service not ready |
| `503` | `FEATURE_STORE_UNAVAILABLE` | Redis feature store unreachable |
| `429` | `RATE_LIMIT_EXCEEDED` | Too many score requests from caller |

**Fallback on `FEATURE_STORE_UNAVAILABLE`:** Return last cached score (from score_history PostgreSQL) with header `X-Score-Source: FALLBACK_CACHE` and age indication. Do NOT block loan applications because Redis is down.

---

## Rate Limiting

| Caller | Real-time Scoring Limit | Batch API Limit |
|---|---|---|
| Loan Service | 200 RPS | Dedicated batch endpoint, no rate limit |
| Card Service | 100 RPS | Dedicated batch endpoint |
| CRM/Pre-screening | 50 RPS | Limited to off-peak hours |
| Data Science (debugging) | 10 RPS | Via admin endpoints only |

---

## Interview Discussion Points

- **Why separate the reason code endpoint from the score endpoint?** Regulatory reason codes are needed only when a decision is made (approve/decline). Pre-screening queries (CRM checking 100K users for a marketing campaign) don't need reason codes. Separating them allows the scoring API to be 1–2ms faster for non-regulatory callers
- **Why is `force_refresh=true` a parameter?** Most score requests are fine with a 5-minute-old cached score. But for a final loan decision (the moment the user clicks "Submit Application"), the lender wants the freshest possible score. `force_refresh=true` bypasses the Redis score cache and reads fresh features from the feature store. Still < 30ms
- **What does the adversarial caller look like?** A caller submitting hundreds of score requests per second to profile the scoring model (extract the model via repeated queries). Rate limiting + anomaly detection on score request patterns. Also: the score response never includes raw_pd (only the 300–900 score) — harder to reverse-engineer the model's decision boundary
- **How do you version the API when model feature sets change?** Model v2.4 adds a new feature (`bureau.default_count_2y`) that v2.3 doesn't have. The API contract doesn't change — the request/response schema is stable. The feature store adds the new feature key. The model registry tracks which features each model version requires. Old model versions simply don't request the new feature key during assembly
