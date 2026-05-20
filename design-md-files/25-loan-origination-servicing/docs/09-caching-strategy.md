# 09 — Caching Strategy: Loan Origination & Servicing System

## Objective

Define caching for a system where financial correctness is paramount. Unlike trading systems, the constraint is not latency — it is avoiding stale financial data causing incorrect decisions (e.g., EMI already paid but system tries to debit again).

---

## Caching Philosophy for Financial Systems

**Golden rule: never cache data that drives a financial write decision.**

A loan's outstanding balance, EMI payment status, and disbursement state must always come from the source of truth (PostgreSQL primary). Caching these for write operations risks:
- Double-debiting a borrower (read stale "unpaid" status, debit again)
- Approving a loan for a borrower who already has an active loan (stale portfolio view)
- Disbursing twice (stale saga status check)

Cache only for: read-only UI display, configuration data, idempotency, rate limiting.

---

## Cache Layer 1: Idempotency (Redis — Critical)

Every state-changing API endpoint uses Redis for idempotency.

```
Key:    idem:{idempotency_key}
Value:  {response_body_json}
TTL:    24 hours
```

**Operations covered:**
- Application submission
- Document upload
- Offer acceptance
- Prepayment submission
- Repayment recording
- Any maker/checker decision

**Implementation:**
1. Check `GET idem:{key}` before processing
2. If exists: return stored response (no re-processing)
3. If not exists: process, then `SET idem:{key} {response} EX 86400 NX`

`SETNX` atomically prevents race condition (two concurrent identical requests only one processes).

---

## Cache Layer 2: Loan Summary (Redis — Read Display)

The borrower portal's most frequent query: "What's the status of my loan?"

```
Key:    loan_summary:{loanAccountId}
Value:  {status, outstandingPrincipal, nextDueDate, nextEmiAmount, dpd}
TTL:    5 minutes
Invalidation: explicit on LoanAccountUpdated event
```

**When to invalidate:**
- `EMIPaymentReceived` event → evict `loan_summary:{loanAccountId}`
- `LoanRestructured` event → evict
- `LoanClosed` event → evict
- Nightly DPD update job → bulk evict all loan_summary keys (acceptable — they refill from DB on next request)

**Staleness risk:** If TTL expires before invalidation event arrives, borrower may see outdated summary for 5 minutes. Acceptable — loan status is not a real-time financial decision; it's display data.

---

## Cache Layer 3: Amortization Schedule (Redis — Read Display)

Schedule is generated once at loan activation and changes only on:
- Prepayment (recalculation)
- Restructuring
- Each EMI payment (status update for paid installment)

```
Key:    loan_schedule:{loanAccountId}
Value:  [full schedule JSON array]
TTL:    1 hour
Invalidation: explicit on RepaymentRecorded, LoanRestructured
```

**Why 1-hour TTL?** Schedule changes are infrequent (once per month at best). 1-hour stale data is acceptable for display — borrower's schedule won't change between portal visits within an hour.

**Do NOT serve cached schedule to EMI batch job.** Batch job reads from PostgreSQL primary — must see actual current status of each installment.

---

## Cache Layer 4: Rate Cards and Credit Policy (Application-Level Local Cache)

Interest rates and credit policies change infrequently (weekly or monthly).

```java
// Caffeine local cache per pod
Cache<ProductType, List<RateCard>> rateCardCache
  .maximumSize(100)
  .expireAfterWrite(15, MINUTES)
  .build();
```

On rate change (via admin API):
- Update database
- Publish `RateCardUpdated` event to Kafka
- Each pod consumes event → evict local cache
- Next request repopulates from DB

**Why local cache (not Redis)?** Rate cards are tiny (< 10 KB), rarely change, and read on every application submission. Local cache = zero network latency.

**Risk:** Up to 15-minute window where new and old rate is served (during cache refresh). Acceptable for rates (apply-now to get current rate, not retroactive). NOT acceptable for credit policy rules (a tighter DTI limit must apply immediately) → credit policy loaded fresh on every underwriting run (no caching for policy rules).

---

## Cache Layer 5: Back-Office Counters (Redis — Dashboard)

Loan officer dashboard shows pending task counts:

```
Key:    pending_tasks:{teamId}:count
Value:  integer
TTL:    30 seconds
Invalidation: triggered on task state change
```

Acceptable staleness: 30 seconds. A loan officer seeing "45 pending" vs "47 pending" is not a financial decision — it's workload visibility.

---

## Cache Layer 6: Bureau Report Cache (Redis — Cost Control)

Credit bureau charges per pull (₹5-50 per inquiry). Caching within a session prevents redundant charges.

```
Key:    bureau_report:{pan_number}:{date}
Value:  bureau response JSON (encrypted at rest)
TTL:    24 hours (bureau data valid for 24 hours)
```

If same borrower applies twice in 24 hours (or loan officer re-pulls for a review), return cached report. Save bureau cost; report is still current.

**Sensitivity:** Bureau report contains highly sensitive financial data. Stored encrypted (application-level AES-256-GCM). Key managed by Vault.

---

## What NOT to Cache

| Data | Why Never Cached for Writes |
|------|----------------------------|
| Loan outstanding balance (for EMI debit) | Must read authoritative value; stale = double debit |
| Disbursement saga status | Must read authoritative value; stale = double disbursement |
| NACH mandate active status | Must read authoritative; stale = debit on cancelled mandate |
| NPA classification status | Must read authoritative; stale = wrong action (e.g., waiving fee on written-off account) |
| Maker-checker task status | Must read authoritative; stale = maker approving already-decided task |

**Absolute rule:** Before any write operation that moves money, always read from PostgreSQL primary.

---

## Cache Invalidation Patterns

### Event-Driven Invalidation (Preferred)

Kafka consumer in each application pod listens to domain events:
- `EMIPaymentReceived` → evict `loan_summary:{loanAccountId}`
- `LoanActivated` → warm `loan_schedule:{loanAccountId}`
- `RateCardUpdated` → evict local `rateCardCache`

This keeps cache fresh within seconds of state change.

### TTL-Based (Fallback)

All cache entries have TTL regardless of event-driven invalidation. This handles cases where the invalidation event is lost (Kafka consumer down briefly).

### Cache Stampede Prevention

On high-traffic events (campaign launch, 1st of month):
- Many keys evicted simultaneously
- All pod miss same key → thundering herd to DB

Prevention:
- **Probabilistic early expiration:** start refreshing before TTL expires (jitter-based)
- **Lock on miss:** acquire distributed lock before DB read, other requests wait; first requester populates cache

---

## Cache Warming

On service startup:
1. Load rate cards → populate local cache
2. Load active credit policies → local cache
3. **Do NOT** pre-warm loan summaries (too large, fresh data preferred)

On pod replacement during campaign surge:
- New pods start cold → small DB load spike as caches warm
- Acceptable: K8s rolling update is slow (one pod at a time), cache warms before next pod replaces
