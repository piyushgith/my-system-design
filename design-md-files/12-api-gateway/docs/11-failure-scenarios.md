# 11 — Failure Scenarios: API Gateway

---

## Objective

Analyze failure modes in an API gateway — the single point through which all traffic flows — and define detection, recovery, and prevention strategies. Gateway failures affect all downstream services simultaneously.

---

## Gateway Failure Impact is Amplified

Unlike a single service failure (affects that service's users), gateway failure affects:
- Every API consumer
- Every backend service (all become unreachable)
- Every mobile app, web app, and partner integration

This makes gateway reliability the highest-priority infrastructure concern.

---

## Scenario 1: Gateway Pods Crash

**Trigger**: OOM kill, JVM crash, application bug, Kubernetes node failure.

**Impact**: Reduced capacity → increased latency → some requests fail.

**Mitigation**:
- Minimum 3 pods across 3 AZs (no single AZ failure = total outage)
- Pod disruption budget: never bring all pods to 0 during rolling update
- ALB health check: every 5 seconds; unhealthy pods removed from rotation within 15s
- HPA: scale up on CPU spike (catches gradual degradation before it becomes outage)

**Recovery**:
- Kubernetes: auto-restarts crashed pods (typically < 30s)
- ALB: stops sending traffic to crashed pod immediately
- Remaining pods: absorb additional load (HPA pre-scales for 70% headroom)

**What breaks if all 3 pods crash simultaneously?** This is why you run minimum 3 (or more) pods. Three simultaneous AZ failures = major AWS incident affecting everyone.

---

## Scenario 2: Redis Failure (Rate Limiting + JWT Cache Down)

**Trigger**: Redis Cluster loses majority of masters.

**Impact**:
- JWT validation: falls back to cryptographic verification (CPU spike)
- Rate limiting: no state → fail-open (allow more requests than policy)
- API key lookup: falls back to DB (latency spike per request)

**Mitigation**:
- Redis Cluster (3 masters + 3 replicas): single master failure handled automatically
- Circuit breaker around Redis: if Redis fails, gateway falls back gracefully

**Fallback behavior on Redis failure**:

| Gateway Function | Redis Down Behavior |
|---|---|
| JWT validation | Verify signature cryptographically (slow but correct) |
| API key auth | DB lookup (50ms vs 0.3ms) |
| Rate limiting (public) | Fail-open: allow requests (brief abuse window) |
| Rate limiting (payment API) | Fail-closed: return 503 until Redis recovers |

**Alert**: Redis failure → P1 alert → SRE response within 5 minutes. Rate limiting is important security control.

---

## Scenario 3: Backend Service Down (Order Service Unavailable)

**Trigger**: Order service pods all crash, DB connection exhausted, deployment failed.

**Impact**: All requests to `/api/orders/*` return errors.

**Gateway responsibility**: don't cascade failure to unrelated services.

**Circuit Breaker**:
```
Order service circuit breaker:
  CLOSED: all requests pass through
  OPEN condition: 5 failures in last 10 requests → OPEN
  OPEN state: all order requests return 503 immediately (no upstream call)
  HALF_OPEN: after 30s, try 1 test request
  CLOSE condition: 3 successes → CLOSED

Benefit: 
  - Prevents thundering herd when order service recovers
  - Frees gateway thread pool from blocked upstream connections
  - Unrelated services (catalog, search) unaffected
```

**Fallback response** when circuit open:
```json
{
  "error": "SERVICE_TEMPORARILY_UNAVAILABLE",
  "message": "Order service is temporarily unavailable. Please try again in 30 seconds.",
  "retryAfter": 30
}
```

---

## Scenario 4: Thundering Herd on Circuit Close

**Trigger**: Backend service recovers. Circuit transitions to HALF_OPEN.

**Problem**: 10,000 queued/retrying clients all send requests simultaneously when service comes back.

**Mitigation**:
- Rate limit at circuit HALF_OPEN: allow only 1 request per 5 seconds initially
- Gradual open: increase allowed traffic 10% per 10 seconds until back to 100%
- This prevents: recovered service → immediately overwhelmed by pent-up demand → crashes again → never recovers

Implementation: Kubernetes-based canary for new service deployment; gradual ALB target weight increase.

---

## Scenario 5: Config Service Down (Routes Can't Be Updated)

**Trigger**: Config management service crashes. Gateway can't fetch new route config.

**Impact**: No route config updates. Existing config works; new config changes can't be applied.

**Mitigation**:
- Gateway loads config at startup into memory → operational on cached config
- Config service down = no config changes, not a gateway outage
- Health check endpoint excludes config service dependency

**Risk**: if gateway pods restart during config service outage → load config from local disk cache (written on last successful fetch).

**Recovery**: config service recovers → all gateway pods receive pending config-change Kafka events → apply in order.

---

## Scenario 6: JWT Signing Key Rotation During Traffic

**Trigger**: Auth service rotates JWT signing key pair (security best practice).

**Problem**: 
- Old tokens signed with key v1
- New tokens signed with key v2
- Gateway must accept BOTH during transition period

**Without handling**: gateway rejects old tokens (key not found) → all logged-in users get 401 → forced re-login → massive support tickets.

**Solution: Multiple keys in JWKS**:
```json
{
  "keys": [
    { "kid": "key-v1", "n": "...", "e": "AQAB" },   ← old key (keep for token_expiry duration)
    { "kid": "key-v2", "n": "...", "e": "AQAB" }    ← new key
  ]
}
```

JWT header specifies `kid` (key ID). Gateway selects correct key for verification.
Old key removed from JWKS only after all tokens signed with it have expired (15 minutes after rotation if 15-min token expiry).

---

## Scenario 7: DDoS Attack on Gateway

**Trigger**: 500K RPS from botnet targeting one endpoint.

**Layers of protection**:

```
Layer 1: Cloudflare / AWS Shield (before traffic reaches gateway)
  - Volume-based DDoS mitigation
  - IP reputation block
  - Blocks 90%+ of bot traffic

Layer 2: WAF rules (at ALB)
  - Block malformed requests
  - Rate limit per IP: 1,000 req/min
  - Block known attack patterns

Layer 3: Gateway rate limiting (Redis)
  - Per-IP + per-user limits
  - Circuit breaker on burst

Layer 4: HPA scaling
  - Kubernetes auto-scales gateway pods under sustained load
  - Target: 70% CPU; scale up to maintain headroom
```

**What happens when all layers are overwhelmed?**
- Scale gateway horizontally until HPA max reached
- Notify CDN to enable "under attack mode" (CAPTCHA challenge all requests)
- Identify attack pattern → add specific IP/ASN block at Cloudflare

---

## Scenario 8: Cascading Failure: One Slow Service Blocks Thread Pool

**Trigger**: Inventory service starts responding in 30s instead of 200ms.

**Without mitigation**:
- 100K RPS × 30s timeout = gateway holds 100K open connections to inventory service
- Thread pool exhausted: 0 threads available for other services
- All services (orders, catalog, search) return 503 — even though they're healthy

**Mitigation: Bulkhead Pattern**

```
Gateway thread pools (separate, isolated):
  Catalog service: 200 threads
  Order service: 200 threads
  Inventory service: 100 threads
  Payment service: 100 threads
  Search service: 100 threads

Inventory slowness → exhausts only its 100-thread pool
Other services: unaffected — still have their own threads
```

**Additional mitigation**: timeout per upstream (2s for most; 10s for payment):

| Service | Timeout | On Timeout |
|---|---|---|
| Catalog | 2s | 503 with retry hint |
| Order | 5s | 503 with retry hint |
| Payment | 10s | 503 (async flow option) |
| Search | 1s | Fallback to cached results |
| Inventory | 2s | 503 or cached inventory |

---

## Scenario 9: Hot Deployment Failure (Canary Goes Wrong)

**Trigger**: Canary deployment of gateway v2 has a bug that returns 500 for POST requests.

**Impact**: 5% of POST requests fail (5% canary traffic).

**Detection**: 
- Alert: error rate for POST requests to specific routes > 2%
- Alert: p99 latency spike (broken response processing)
- ALB access logs: 500 responses on gateway v2 pods

**Automatic rollback**:
- If error rate > 5% for 2 minutes → route 0% traffic to canary
- Gateway deploys using traffic splitting (5% to new, 95% to old)
- Rollback: change split to 0% new, 100% old → instant

**Manual rollback**:
```
kubectl rollout undo deployment/api-gateway
→ Immediately routes all traffic to previous version
→ Time: < 60 seconds
```

---

## Scenario 10: Certificate Expiry

**Trigger**: TLS certificate expires. All HTTPS connections fail.

**Impact**: All API calls fail with certificate error. System appears down to all clients.

**Prevention**:
- ACM auto-renewal: certificate renewed 60 days before expiry
- Alert: certificate expiry < 30 days (safety net for ACM failure)
- Monitoring: certificate expiry date in Prometheus → alert in Grafana

**Detection**: if it somehow expires:
- Client receives SSL certificate error
- ALB returns TLS handshake failure
- Alert: ALB 5xx spike

**Recovery**:
- Manually trigger ACM certificate renewal (< 5 minutes)
- Deploy new certificate to ALB (< 2 minutes)
- Total: < 10 minutes from detection

---

## Recovery Runbooks Summary

| Failure | Detection | Recovery | Time |
|---|---|---|---|
| Gateway pod crash | ALB health check, 503 spike | K8s auto-restart | < 30s |
| Redis down | Redis health check, auth latency spike | Redis Cluster auto-failover | < 30s |
| Backend service down | Circuit breaker opens, 503 on route | Circuit breaker + graceful degradation | Instant |
| DDoS attack | RPS spike + error rate spike | Cloudflare under-attack mode + IP block | 5-15 min |
| Canary failure | Error rate spike on new pods | Rollback traffic split to 0% | < 60s |
| Certificate expiry | SSL errors in access logs | ACM renew + ALB update | < 10 min |
| Config service down | Config change Kafka events stall | Gateway runs on cached config; no new changes | Ongoing until fix |

---

## Interview Discussion Points

- **"What happens if your API gateway goes down?"** → Multi-pod across AZs prevents single point of failure. K8s auto-restarts pods. ALB reroutes. If all pods down: CDN serves cached responses for public APIs; users see stale but functional content.
- **"How do you prevent one slow backend from killing everything?"** → Bulkhead: separate thread pool per backend. Timeout: max 2-10s per upstream. Circuit breaker: open after 5 failures. Together: slow inventory doesn't affect catalog.
- **"How do you handle rolling key rotation without logging users out?"** → JWKS publishes both old and new keys simultaneously; JWT `kid` header selects correct key; old key removed only after its tokens naturally expire (15 min).
- **"How do you detect a bad canary deployment?"** → Alert on error rate spike for traffic hitting canary pods (detectable via pod label in metrics). Auto-rollback: shift weight back to stable version when threshold breached.
- **"What's your failover strategy if Redis dies?"** → JWT: fall back to crypto verification (slower but correct). Rate limiting: fail-open for most APIs, fail-closed for payment. API keys: fall back to DB. Gateway stays up; latency increases; abuse protection temporarily reduced.
