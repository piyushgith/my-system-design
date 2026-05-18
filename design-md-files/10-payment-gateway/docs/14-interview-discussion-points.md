# 14 — Interview Discussion Points: Payment Gateway / Wallet System

---

## Objective

Prepare for Taking/fintech-level system design interviews on payment systems — covering the hardest distributed systems problems (idempotency, double-entry, distributed transactions, consistency) with real-world production depth.

---

## Expected Interviewer Opening Questions

- "Design a payment gateway like Stripe / Razorpay"
- "Design a digital wallet like Paytm / PhonePe"
- "How would you handle distributed transactions across the payment and order service?"
- "How do you ensure a payment is processed exactly once?"

**Critical first question to ask**: "What's our scale? 100 TPS or 50,000 TPS changes the architecture significantly."

---

## Hardest Questions and Strong Answers

### Q: How do you prevent double charging?

Shallow answer (wrong): "We check if the payment exists before processing."

**Strong answer**:
> Three layers working together. First: idempotency key — client generates UUID per payment intent, sends as header. Server stores `(idempotencyKey, response)` in Redis for 24h. Duplicate request → return cached response; no processing. Second: if Redis fails, idempotency table in Postgres with unique constraint on idempotency key — duplicate insert fails with constraint violation. Third: payment gateway itself has idempotency (Stripe supports it natively). Defense in depth.

### Q: How do you implement double-entry bookkeeping in a distributed system?

**Strong answer**:
> Double-entry: every financial movement creates two ledger entries — debit from one account, credit to another. In a monolith, this is a single DB transaction: INSERT two ledger rows atomically. In a distributed system, the sender and receiver may be on different services. Solution: Saga pattern with compensation. Step 1: debit sender (in sender service DB). Step 2: credit receiver (event consumed by receiver service). If Step 2 fails: compensation — credit sender back. The key invariant: at steady state, SUM(all debits) = SUM(all credits). Monitor this every 5 minutes. Any imbalance = P1 incident.

### Q: How do you handle a payment that's in an ambiguous state (gateway timeout)?

**Strong answer**:
> The "unknown" state is the hardest case. Payment was submitted to Stripe but we don't know if it succeeded. Solution: async payment flow. (1) We store payment as PROCESSING state and return "payment submitted" to user immediately. (2) We have a job that polls the gateway status API for PROCESSING payments older than 30s. (3) Gateway also sends webhooks on state change (authorized, failed, captured). (4) Reconciliation with bank settlement file nightly catches anything that slipped through. The key: never lose the payment ID. With it, we can always query the external gateway to determine final state.

### Q: Explain your idempotency implementation in detail.

**Strong answer**:
> Client generates UUID (idempotency-key) before the first request. Sends as header `Idempotency-Key: {uuid}`. Server side: (1) Check Redis `GET idempotency:{key}`. If value is response JSON → return it immediately. (2) If value is "PROCESSING" → another request is in-flight; return 202 with retry-after. (3) If nil → set `idempotency:{key} = "PROCESSING"` with 30s TTL (NX — only if not exists). (4) Process payment. (5) On completion, set `idempotency:{key} = {response_json}` with 24h TTL. (6) Return response. The NX ensures only one request wins the race. Client keeps same key on all retries.

### Q: How do you do wallet-to-wallet transfer atomically?

**Strong answer**:
> This is a classic distributed transaction problem. Three approaches: (1) XA/2PC — works but distributed lock, coordinator failure risk, too slow for payments. (2) Single DB transaction — both wallets in same Postgres — simple and correct, but limits horizontal scaling. (3) Saga pattern — debit sender atomically with idempotency ID, publish event, credit receiver as compensatable step. If credit fails → compensate by crediting sender back. This is what production payment systems at scale use. The trick: make each step idempotent, make compensation idempotent. Then any failure mode can be retried or compensated.

---

## Tradeoff Discussions

### ACID vs BASE for Payments

| Scenario | Consistency Required | Why |
|---|---|---|
| Debit/credit operation | Strong (ACID) | Can't have partial debit with no credit |
| Balance display to user | Eventual OK | 30s stale balance is acceptable |
| Settlement processing | Strong | Regulatory accuracy required |
| Notification delivery | Eventual OK | Slight delay in receipt email is fine |
| Fraud scoring | Eventual OK | Post-transaction review is acceptable for low risk |

**Key insight**: Not everything in a payment system needs ACID. Being selective about where you apply strong consistency is what allows payment systems to scale.

### Synchronous vs Asynchronous Payment Flow

| Aspect | Sync Flow | Async Flow |
|---|---|---|
| User experience | Instant result | "Payment processing" → email confirmation |
| Failure handling | Simple retry | Complex state machine |
| Gateway timeout | Failed payment | Payment may still succeed |
| Scalability | Limited by gateway latency | Decoupled from gateway latency |
| Implementation | Simple | Complex (state machine, Kafka, reconciliation) |

**Recommendation**: start synchronous (simpler), migrate to async when gateway latency becomes a bottleneck or when high availability > instant feedback.

### Own Payment Gateway vs Integrate Third-Party

| Factor | Own Gateway | Third-Party (Stripe/Razorpay) |
|---|---|---|
| Card network access | Direct (need licenses) | Via gateway (instant) |
| Cost at scale | Lower per-transaction | 1.5-3% per transaction |
| Compliance burden | Full PCI DSS SAQ D | Reduced scope |
| Time to market | 12-18 months | 1-2 weeks |
| Control | Full | Limited by provider API |

**Decision**: until processing > ₹100 crore/month, integrate third-party. Build own gateway only for cost + control at extreme scale.

---

## Senior Engineer Discussion Points

### Exactly-Once Semantics in Practice

Kafka claims "exactly-once semantics" via idempotent producers + transactions. In practice:

- Exactly-once in Kafka: no duplicate messages in the Kafka log ✓
- Exactly-once end-to-end: includes consumer processing, which can fail mid-way ✗

Reality: "at-least-once + idempotent consumer" is more practical and achieves the same result. Implement consumer-side idempotency: check if ledger entry already exists before creating. This is simpler and more robust than relying on Kafka's transactional API alone.

### Settlement Architecture

Settlement is harder than payments:
- Aggregate: sum all successful captures per merchant per day
- Net of refunds: subtract refunds from gross
- Fee deduction: platform fee per transaction (2.5% → ₹X owed to platform)
- Net transfer: merchant receives gross - refunds - fees
- Bank transfer: NEFT/RTGS to merchant bank account

Timing: T+1 or T+2 settlement is regulatory requirement (RBI). Miss this → merchant complaint + regulatory action.

### Chargeback Management

Chargebacks are when card issuer reverses a payment (customer disputes charge):
- Arrive 60-90 days after payment
- Platform responsible for providing evidence (order details, delivery confirmation)
- High chargeback rate (> 0.1%) → payment network fines + potential account termination
- Fraud chargebacks vs friendly fraud (legitimate customer claiming fraud)

This is why 90-day Kafka retention matters — to retrieve evidence for disputes.

---

## Staff Engineer Discussion Points

### Regulatory Complexity

Payment systems operate under multiple regulators:
- RBI: payment aggregator license, settlement timelines, KYC requirements
- PCI DSS: card data standards (managed by card networks)
- SEBI: if investment products involved
- FATF/AML: anti-money laundering transaction monitoring

Architecture must accommodate: data retention per regulator, geographic data residency, transaction reporting APIs.

**Conway's Law in payments**: compliance team shapes architecture. Compliance requirements = service boundaries. AML = separate service (different team, different risk tolerance).

### Building Your Own vs Buying

At scale:
- Fraud model: buy (Sift/Kount) until team has ML expertise and data volume to beat vendor
- Card tokenization: buy (Spreedly/Stripe vault) until processing justifies HSM investment
- Card network connectivity: impossible to self-serve; need acquirer license (years + millions of dollars)
- Notification (email/SMS): buy (SendGrid/Twilio) forever — not core competency

Knowing what NOT to build is as important as knowing what to build.

---

## Common Mistakes Interviewers Watch For

| Mistake | Why It's Wrong |
|---|---|
| Using float for money amounts | Floating point precision errors (0.1 + 0.2 ≠ 0.3) |
| Synchronous saga without compensation | Partial failure leaves money in limbo |
| Caching balance for debit authorization | Stale cache → double spend |
| No idempotency on payment APIs | Double charge on network retry |
| Sync payment flow only | Gateway timeout = broken payment |
| PCI-scope creep | Handling raw card data accidentally brings full PCI burden |
| No reconciliation | Silent financial inconsistency builds up undetected |
| Shared ledger with other systems | Audit trail contaminated; compliance risk |

---

## "What Would Break First?" Analysis

| Scale Multiplier | First Failure | Fix |
|---|---|---|
| 2x | Postgres connection pool | PgBouncer |
| 5x | Ledger table write contention | Partition by account_id |
| 10x | Payment gateway rate limits | Multiple gateway accounts; load balance |
| 50x | Single Postgres write throughput | DB sharding by account_id range |
| 100x | Network bandwidth to card networks | Multi-region with local acquiring |
| 1000x | Regulatory compliance per country | Separate legal entities per jurisdiction |

---

## Numbers to Know

| Metric | Value |
|---|---|
| Stripe processing (2023) | ~$817 billion/year |
| NPCI UPI transactions (2024) | ~100 million/day |
| Redis throughput | ~100K ops/sec (single node) |
| Postgres write throughput | ~10K TPS (well-tuned) |
| PCI DSS SAQ D audit cost | $50K-$500K annually |
| Chargeback limit (Visa) | 1% of transactions |
| RBI settlement mandate | T+1 for UPI, T+2 for cards |
| Kafka throughput | ~1M messages/sec per broker |

---

## Interview Tips (Payment-Specific)

1. **Always mention idempotency first** — it's the #1 correctness requirement; interviewers probe this
2. **Draw the ledger** — show double-entry: debit source, credit destination
3. **Distinguish display vs transactional consistency** — show nuance; not everything needs ACID
4. **Know PCI DSS basics** — tokenization, CDE, SAQ levels; Taking interviewers expect this
5. **Mention reconciliation** — how you detect silent failures; most candidates miss this
6. **Distinguish wallet vs card gateway** — different risk profiles, different regulations
7. **Quote real numbers** — RBI T+1 mandate, Visa chargeback threshold; shows operational experience
