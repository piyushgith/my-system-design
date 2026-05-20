# 11 — Failure Scenarios: Loan Origination & Servicing System

## Objective

Analyze failure modes with specific focus on financial correctness. Unlike trading systems, loan platform failures span days or months, not milliseconds. The goal is: no money lost, no borrower double-charged, complete audit trail even through failures.

---

## Failure 1: Disbursement Saga Partial Failure

**Scenario:** Funds successfully transferred to borrower's bank account, but the webhook from the bank never arrives (network issue, webhook server crash). Loan account never activated.

**State:** Borrower has received money. System thinks disbursement is pending. Loan account does not exist. No EMI schedule created.

**Detection:** Disbursement saga has `bank_transfer_at` set but no `bank_confirmed_at` after 30 minutes → timeout trigger.

**Recovery:**
1. Saga timeout handler triggers
2. Query bank API: `GET /transfers/{bank_transfer_ref}` — direct status check
3. If bank confirms success: proceed with loan activation (saga continues from `bank_confirmed_at` step)
4. If bank confirms failure: reverse ledger reservation, mark saga COMPENSATED
5. If bank API is also unavailable: hold saga in PENDING state, alert ops team, retry bank query every 15 minutes

**Data risk:** Without recovery, borrower gets loan without an account → no EMI collection → financial loss. This scenario MUST have a timeout handler.

**Operational runbook:** Ops team can manually trigger saga continuation after verifying bank confirmation via bank's portal.

---

## Failure 2: Double EMI Debit

**Scenario:** EMI batch runs. NACH debit submitted. Bank processes. Result file arrives. Consumer processes result and updates `amortization_schedule`. Consumer crashes before committing Kafka offset. Kafka re-delivers the result message. Consumer processes again → tries to record payment twice.

**Prevention:**
- `repayment_records.idempotency_key` = NACH batch file row ID (unique from NACH result file)
- `INSERT INTO repayment_records ... ON CONFLICT (idempotency_key) DO NOTHING`
- Second attempt: INSERT skipped (idempotency), no double-debit

**Alternative prevention:** `amortization_schedule.status = PAID` checked before processing. If already PAID: skip silently.

**Why this works:** NACH result file is authoritative — each row has a unique reference from NPCI. Even if we re-process the result file, the DB constraint prevents double-recording.

---

## Failure 3: Bureau API Unavailable During Underwriting

**Scenario:** CIBIL API is down during application surge. 5,000 applications submitted but bureau reports cannot be fetched.

**Impact:** Underwriting stalls for all 5,000 applications. No approvals or rejections.

**Design:** Bureau check is async. Applications are accepted and queued. When CIBIL recovers, queued applications resume processing.

**Kafka consumption:**
- `LoanApplicationSubmitted` events remain in Kafka (7-day retention)
- Underwriting Service resumes consuming when bureau available
- No data loss, no rejected applications due to infrastructure issue

**SLA impact:** Applications may exceed 24-hour decision SLA during extended bureau outage. System emails applicants with delay notification.

**Mitigation for critical path:**
- Bureau report cache (24-hour TTL): if same borrower applied previously, use cached report to make decision without live bureau call
- Alternative bureau fallback: if CIBIL down, try Experian (secondary bureau agreement)

---

## Failure 4: NACH Batch File Submission Failure

**Scenario:** Monthly NACH batch generated (66,667 records). SFTP upload to NPCI fails. All EMIs for the month un-submitted.

**Detection:** SFTP upload job exits with error. Alert triggers immediately.

**Impact:** All EMIs for the 1st will miss their collection date. Significant financial exposure.

**Recovery:**
1. Fix SFTP connectivity
2. Re-generate batch file (idempotent — same records, same mandate IDs)
3. Re-submit to NPCI
4. If re-submission is on 2nd of month: NPCI may reject (1-day late)
5. Manual resolution with NACH processor to accept late submission or reschedule

**Prevention:**
- Dry-run SFTP connection test 30 minutes before batch generation
- Batch file generation on 2 nodes (active + standby) — standby ready to submit if active fails

---

## Failure 5: Maker-Checker SLA Breach

**Scenario:** Application referred to maker-checker at 9 AM. 24-hour SLA for decision. Maker is out sick. Nobody picks up the task. SLA breaches at 9 AM next day.

**Automated response:**
1. SLA monitoring job runs every 15 minutes
2. Tasks within 2 hours of SLA: escalation notification to team lead
3. Tasks at SLA breach: auto-escalation to senior underwriting team
4. Auto-rejection NOT triggered (auto-rejecting valid applications = revenue loss)

**System design:**
- `maker_checker_tasks.sla_deadline` indexed
- K8s CronJob runs every 15 minutes: `SELECT * FROM maker_checker_tasks WHERE sla_deadline < now() + interval '2 hours' AND status = 'PENDING_MAKER'`
- Publishes `TaskSlaWarning` event → Notification Service → email/SMS to team lead

---

## Failure 6: Loan Account Service Crash During Activation

**Scenario:** Disbursement saga's final step: "activate loan account." Service crashes mid-activation.

**State:** PostgreSQL row partially created? Or not at all (depends on where crash occurred relative to transaction).

**PostgreSQL transactions protect correctness:**
```
BEGIN;
  INSERT INTO loan_accounts (...);
  INSERT INTO amortization_schedule (...);
  UPDATE disbursement_sagas SET loan_activated_at = now();
COMMIT;
```

If crash before COMMIT: PostgreSQL rolls back automatically. Saga step `loan_activated_at` is NULL.

On service restart: saga recovery job detects `bank_confirmed_at` set but `loan_activated_at` NULL → re-trigger loan activation step.

**Why this works:** The entire loan activation is one transaction. Either all happens or none happens. Saga state in PostgreSQL tells us exactly where to resume.

---

## Failure 7: Collections Case Not Created for Overdue Loan

**Scenario:** EMI bounces (final retry). `EMIBounced` event published to Kafka. Collections Service crashes. Collections case never created. Borrower enters DPD without being contacted.

**Detection:** Daily DPD check job independently identifies all loans with DPD > 0 and no active collections case → creates case directly from DB scan (not relying on event delivery).

**This is the belt-and-suspenders principle:** The primary trigger is event-driven. The secondary trigger is a nightly DB scan. Both create cases idempotently (check for existing case before creating).

**DPD calculator job (K8s CronJob, 7 AM daily):**
```sql
SELECT la.loan_account_id, la.dpd
FROM loan_accounts la
LEFT JOIN collections_cases cc ON la.loan_account_id = cc.loan_account_id
  AND cc.status NOT IN ('CLOSED', 'RESOLVED')
WHERE la.dpd > 0 AND la.status = 'ACTIVE'
  AND cc.collections_case_id IS NULL;
```

Result: loans that should have a collections case but don't → create case.

---

## Failure 8: Redis Unavailable (Idempotency Cache)

**Scenario:** Redis is down. Idempotency cache for EMI recording is unavailable. Borrower submits prepayment. Service cannot check idempotency key.

**Behavior options:**
1. **Fail-closed:** Reject request until Redis recovers. Borrower cannot submit prepayment. Safe but poor UX.
2. **Fail-open with DB fallback:** Check PostgreSQL's `repayment_records.idempotency_key` column directly.

**Decision: Fail-open with DB fallback for critical financial operations.**

```
Redis available → fast idempotency check (0.1ms)
Redis unavailable → fall through to PostgreSQL check (< 5ms)
```

PostgreSQL is always available (primary + replica). DB-based idempotency is slower but correct. The DB has the idempotency column as a unique constraint — it is the ultimate protection.

---

## Failure 9: Reconciliation Failure (Ledger vs Repayments)

**Scenario:** Nightly reconciliation discovers sum of all `repayment_records.principal_paid` for a loan doesn't match expected outstanding principal reduction.

**Cause:** Could be rounding error accumulation, partial payment handling bug, or data inconsistency.

**Response:**
1. Alert: reconciliation failure is P1 (financial data integrity)
2. Identify affected loan accounts
3. Manual audit of loan's full event history
4. Correction entry: adjusting ledger entry (similar to bank reconciliation adjustment)
5. Root cause analysis and code fix

**Prevention:** Run reconciliation daily (not monthly). Catch discrepancies when small. Run on each loan at closure (full reconciliation at loan end ensures books balance before archive).

---

## Disaster Recovery

| Scenario | RTO | RPO | Strategy |
|----------|-----|-----|---------|
| Application pod failure | < 2 min | 0 (stateless) | K8s restart |
| PostgreSQL primary failure | < 5 min | < 30 sec | Automatic replica promotion |
| Redis failure | < 1 min | 0 (Redis is cache) | Sentinel failover, DB fallback |
| Kafka broker failure | < 2 min | 0 (replicated) | Automatic leader election |
| AZ failure | < 10 min | < 1 min | Multi-AZ PostgreSQL + Kafka |
| Region failure | < 2 hours | < 5 min | DR region with PostgreSQL replica + Kafka MirrorMaker |
| Data corruption | < 4 hours | Last backup | PITR restore from PostgreSQL continuous backup |

**PostgreSQL PITR (Point-In-Time Recovery):**
- WAL archived to S3 continuously
- Can restore to any point in time with 5-minute granularity
- RPO: 5 minutes for data corruption scenario
- Critical for: accidental mass data deletion (human error), software bug causing incorrect writes
