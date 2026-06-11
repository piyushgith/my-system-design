# 17 — Testing Strategy: Loan Origination & Servicing System

## Objective

Define test layers and release gates. Loan-system bugs are slow-motion financial bugs: a wrong amortization rounding or DPD computation compounds over months across the whole book before anyone notices. The suite centers on financial-math golden tests, saga failure injection, and idempotent money movement.

---

## Test Pyramid

| Layer | Scope | Tooling | Runs |
|---|---|---|---|
| Unit | Amortization math, fee/penalty rules, DPD/NPA classification, state machines | JUnit 5 | Every commit |
| Integration | Sagas and EMI pipeline on real PostgreSQL + Kafka | Testcontainers | Every commit |
| API / E2E | Full lifecycle journeys per role (borrower, officer, underwriter, ops) | RestAssured | Every PR |
| Batch | EMI batch, DPD daily job, reporting jobs on multi-month seeded books | Spring Batch harness | Nightly |
| Failure injection | Saga and reconciliation drills from doc 11 | Chaos suite | Weekly staging |

## Financial Math Golden Tests (release-gating)

1. **Amortization schedules**: hand-verified golden fixtures per product (personal/home/BNPL) × tenure × rate, including day-count and rounding conventions — paise-exact, with the rounding remainder absorbed in the final EMI (sum of schedule = principal + total interest, exactly)
2. **Prepayment recomputation**: partial and full prepayment → recomputed schedule against golden fixtures; prepayment fee bands
3. **Penalty/grace**: late-fee accrual across grace boundary, including partial payments and bounced-then-paid sequences
4. **Restructuring**: tenure extension/EMI reduction → new schedule correct, old schedule frozen and auditable, restructured flag set (reportable per F27)
5. **DPD and asset classification**: daily DPD job over multi-month seeded histories with partial payments, restructuring, and clock edge cases (month-end, leap day) → SMA-0/1/2 and NPA transitions per RBI IRACP norms at exactly 30/60/90 DPD

## Saga & Money-Movement Tests (release-gating)

1. **Disbursement saga failure matrix**: injected failure at every step boundary (before bank call, after transfer without webhook, bank API down during timeout query) → saga ends EXECUTED or COMPENSATED, never stuck without an alert; ledger reservation always released or consumed (Failure 1)
2. **Double-EMI prevention**: replay the NACH result file and redeliver Kafka result messages → `ON CONFLICT (idempotency_key) DO NOTHING` yields exactly one repayment record per NACH row (Failure 2)
3. **Ledger contract**: every financial event posts balanced entries via the ledger service contract (see 01, Relationship to Sibling Core Systems) — integration test against the ledger API stub asserting idempotency-key namespacing `loan:<saga_id>:<step>`
4. **Maker-checker**: above-threshold decisions require two distinct actors; same-actor approval rejected; SLA-breach escalation fires (Failure 5)
5. **Reconciliation harness**: seeded month of EMI activity → ledger vs `repayment_records` vs amortization status three-way match; injected mismatch is caught by the reconciliation job (Failure 9 as a test)

## Integration & Contract Tests

- Bureau, scoring, and KYC integrations tested against their published contracts (sibling-system docs), including the scoring fallback rule: `FALLBACK_CACHE` + stale age → manual underwriting routing, never auto-decision
- NACH file generation: produced files validate against the NPCI format spec; submission failure → retry/regeneration without duplicate mandates (Failure 4)
- Webhooks to borrower systems: signed, retried, idempotent on the receiver contract

## Batch & Reporting Tests

- EMI batch scale: V3 target book (2M loans) date-partitioned run completes in window; checkpoint restart resumes without double-debit submission
- CIC reporting (F23): generated submission file against format fixtures; every reported record traceable to source events; rejected-record reprocessing path
- Provisioning postings (F24) match classification buckets after every classification change

## CI Quality Gates

1. Financial math golden suite + saga matrix + double-debit suite green (hard gates)
2. Migration test from previous release snapshot; partitioned tables verified
3. Kafka schema compatibility; outbox relay exactly-once-effect test

## What NOT to Test

- Bank/NPCI production rails — sandbox and recorded contracts only; production verification is a release smoke check with a ₹1 transaction
- Exhaustive product × tenure × rate grids — golden fixtures at boundaries plus property checks (schedule sums) cover the space
- Notification copy/rendering

## Ownership

Servicing team owns financial-math and batch suites; origination team owns saga and underwriting suites; the reconciliation harness is shared with whoever operates the ledger integration. A change to any interest, fee, or classification rule requires a new golden fixture in the same PR — reviewers reject rule changes without fixture changes.
