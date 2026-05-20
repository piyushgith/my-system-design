# 11 — Failure Scenarios & Recovery: Double-Entry Ledger Service

---

## Objective

Document concrete failure modes for the double-entry ledger, their impact, detection mechanisms, recovery procedures, and RTO/RPO targets. A financial ledger has zero tolerance for data inconsistency.

---

## Failure Scenarios

### Failure 1: PostgreSQL Primary Goes Down Mid-Posting

**Scenario:** The application sends a multi-leg posting transaction to PostgreSQL. The primary crashes after the journal_entries INSERT but before the account_snapshots UPDATE and COMMIT.

**Impact:**
- The transaction is rolled back automatically by PostgreSQL on restart (WAL recovery)
- No partial posting committed — the ACID guarantee holds
- The caller receives a connection error or timeout → retries with the same idempotency key
- Retry hits the new primary (after failover) → idempotency key not found (original transaction was rolled back) → posting proceeds normally

**Detection:**
- PgBouncer health check detects primary unavailability within 5 seconds
- Kubernetes readiness probe fails on the ledger pod (DB connection pool exhausted)

**Recovery:**
- PostgreSQL automatic failover via Patroni or AWS RDS Multi-AZ: < 30 seconds
- During failover window: 503 Service Unavailable to callers
- Callers retry with exponential backoff

**RTO:** 30 seconds | **RPO:** Zero (WAL replication — no committed data lost)

---

### Failure 2: Snapshot Inconsistency (Snapshot ≠ Journal Sum)

**Scenario:** A bug in the snapshot update CAS logic applies the wrong delta — the snapshot shows a balance that is higher than the sum of journal entries.

**Impact:**
- Accounts appear to have more money than they do — payment authorization could approve transactions against phantom balance
- Financial loss until detected

**Detection:**
- Daily reconciliation job: for each account, `SUM(journal_entries) vs account_snapshots.balance` → alert on mismatch
- Automated integrity check on a random sample of 1,000 accounts per hour

**Recovery:**
1. Stop serving balance reads from snapshot for affected accounts → fall back to `SUM(journal_entries)` (correct but slow)
2. Deploy corrected snapshot computation
3. Rebuild snapshot for all affected accounts from journal (idempotent replay — journal is the source of truth)
4. Resume snapshot reads

**RTO:** 1 hour for snapshot rebuild | **RPO:** Zero (journal is authoritative, snapshot is derived)

**Prevention:**
- Snapshot rebuild is tested in staging on every release
- Snapshot = journal invariant verified in CI test suite
- Database trigger alerts on snapshot balance changes > $100,000 without a corresponding journal entry

---

### Failure 3: Redis Cache Corruption / Eviction

**Scenario:** Redis memory pressure causes aggressive LRU eviction. A high-traffic account's balance is evicted and the system serves the next read from the snapshot DB. Meanwhile, a concurrent posting hasn't been flushed to snapshot yet.

**Impact:**
- Cache miss → DB snapshot read → returns snapshot value that's up to one posting behind
- Caller sees balance slightly different from real-time
- For payment pre-authorization: risk of approving a borderline transaction

**Detection:**
- Redis `evicted_keys` metric spiking → memory pressure alert
- Balance discrepancy detected in reconciliation

**Recovery:**
- Increase Redis cluster memory
- Reduce TTL to 1 second during high-pressure periods
- For critical balance reads (payment authorization): flag `must_be_fresh=true` → force read from PostgreSQL primary, bypass cache

**RTO:** Immediate (cache miss serves correct DB value) | **RPO:** Zero (DB is authoritative)

---

### Failure 4: Outbox Relay Stops Publishing

**Scenario:** The outbox relay job crashes or gets stuck. `outbox_events` table grows — events are committed to DB but never published to Kafka. Downstream consumers (fraud, analytics) stop receiving events.

**Impact:**
- Fraud detection: no new events for analysis → fraud goes undetected for the lag duration
- Analytics: dashboards show stale data
- No impact on ledger write correctness — postings still commit to journal

**Detection:**
- Outbox unpublished event count metric: `SELECT COUNT(*) FROM outbox_events WHERE published=false`
- Alert when count > 1,000 (approximately 30 seconds of lag at 5,000 posting RPS)
- Kafka consumer group lag monitor: `fraud-detection` lag > 0 growing

**Recovery:**
1. Alert fires → SRE investigates relay pod
2. If relay pod is dead: Kubernetes restarts it automatically (restartPolicy: Always)
3. On restart: relay polls from earliest unpublished event → catches up
4. Kafka consumers receive delayed events in order — process as normal

**Maximum lag:** Kubernetes pod restart time: 30 seconds. Kafka consumer catches up at ~10,000 events/sec. 30 seconds of events = 150,000 events → catch-up time: ~15 seconds.

**RTO:** < 1 minute total | **RPO:** Zero

---

### Failure 5: Duplicate Posting (Idempotency Failure)

**Scenario:** Redis is unavailable. The DB idempotency_cache table lookup also fails (DB overloaded). The application proceeds with a new posting — creating a duplicate journal entry for the same financial event.

**Impact:**
- Account credited or debited twice
- Financial loss (for the platform) or gain (for the customer)

**Detection:**
- Debit=credit invariant still holds per posting — the duplicate is a valid balanced posting, not an invariant violation
- Reconciliation: external bank statement shows one transaction; internal ledger shows two postings for the same reference_id → `DUPLICATE_REFERENCE_ID` discrepancy flag

**Recovery:**
1. Identify duplicate posting via `reference_id` search
2. Post a reversal for the duplicate posting
3. Alert payment operations team
4. Root cause analysis: Redis cluster health, DB connection pool exhaustion

**Prevention:**
- UNIQUE constraint on `postings.idempotency_key` as final backstop — even if application logic fails, the DB constraint prevents the INSERT
- DB idempotency_cache is written in the same transaction as the posting — always available if DB is up

**RTO:** Corrected within hours (manual reversal) | **RPO:** Financial reconciliation may take hours

---

### Failure 6: Hot Account Serialization (Performance Failure)

**Scenario:** The platform float account receives 50,000 concurrent postings per minute. Each posting tries to CAS-update `account_snapshots` for this account. The retry loop causes cascading timeouts.

**Impact:**
- All postings to the float account timeout after 3 CAS retries
- 503 errors cascade to the payment service → user-visible payment failures

**Detection:**
- CAS retry rate > 20% on `account_snapshots`
- Posting P99 latency exceeds 500ms
- PostgreSQL `wait_event=Lock` rate spike

**Recovery (immediate):**
1. Enable emergency mode: disable snapshot update in-transaction for the hot account
2. Serve balance as `SUM(journal_entries since last manual snapshot)` — slower but functional
3. Schedule async snapshot rebuild every 60 seconds for the hot account

**Recovery (permanent):**
1. Implement virtual account sharding for float account
2. Batch snapshot updates (collect deltas in Redis, apply every 100ms)

**RTO:** 15 minutes (enable emergency mode) | **RPO:** Zero

---

### Failure 7: Kafka Broker Cluster Failure

**Scenario:** Two Kafka brokers fail simultaneously. `min.insync.replicas=2` is violated — producers cannot get `acks=all`.

**Impact:**
- Outbox relay cannot publish events → events accumulate in `outbox_events` table
- Downstream consumers stop receiving events
- Posting commits to DB continue (ledger correctness unaffected)

**Detection:**
- Kafka producer `record-send-rate` drops to zero
- Kafka broker health check fails in monitoring
- Outbox unpublished count grows

**Recovery:**
1. Kafka broker replacement: new broker joins cluster, leader election, partition reassignment
2. Time to recover: 10–30 minutes depending on partition rebalancing
3. During recovery: outbox_events accumulate (table grows)
4. After recovery: relay catches up; downstream consumers process delayed events in order

**RTO (event delivery):** 30 minutes | **RPO:** Zero (events persisted in outbox_events table)

---

### Failure 8: Regulatory Period Close Violation (Backdated Entry)

**Scenario:** A system bug or misconfigured `effective_at` allows a journal entry to be posted with a date in a closed accounting period.

**Impact:**
- Regulatory report for the closed period is now incorrect
- Correction requires resubmission to regulator (RBI/SEBI)
- Audit finding

**Detection:**
- Pre-posting validation: if `effective_at < current_period_close_date` → reject with 422
- Period close date stored in `accounting_periods` table; checked on every posting

**Recovery:**
1. Identify the backdated posting
2. Post a reversal with the same `effective_at` date (also backdated — requires admin override)
3. Report to compliance team
4. Resubmit affected regulatory report

**Prevention:**
- Period close lock enforced at application layer
- Database-level check constraint (`effective_at > :open_period_start`) after period is closed

---

## RTO / RPO Summary

| Failure | RTO | RPO | Data Loss? |
|---|---|---|---|
| PostgreSQL primary crash | 30 seconds | 0 | None — WAL recovery |
| Snapshot inconsistency | 1 hour | 0 | None — journal authoritative |
| Redis cache loss | Immediate | 0 | None — cache is derived |
| Outbox relay crash | < 1 minute | 0 | None — events in DB |
| Duplicate posting | Hours (manual) | Requires reconciliation | Potential financial |
| Hot account serialization | 15 minutes | 0 | None |
| Kafka cluster failure | 30 minutes | 0 | Events buffered in DB |
| Backdated entry | Hours (correction + resubmit) | Audit finding | Regulatory |

---

## Interview Discussion Points

- **What is your disaster recovery story for the full region going down?** Active-passive: a standby region receives PostgreSQL WAL streaming replication (< 100ms lag). On region failure: DNS failover to standby region, promote standby to primary, resume operations. RPO: up to 100ms of transactions (the replication lag at time of failure). RTO: 5 minutes
- **How do you verify the journal is correct (no missing entries)?** The debit=credit invariant is checked at every posting. A daily audit job: `SELECT SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END) FROM journal_entries WHERE effective_at BETWEEN date_start AND date_end GROUP BY currency` should equal zero across all accounts. Any non-zero sum indicates a missing or corrupted entry
- **What happens if a saga compensation (reversal) also fails?** This is the dual-failure problem. In practice: the original posting is already in the journal. The compensation entry is retried with exponential backoff until it succeeds. If permanently unavailable (e.g., account closed), a manual compensation is raised as a compliance ticket — the journal has a `PENDING_MANUAL_CORRECTION` status
- **How do you test disaster recovery scenarios?** Chaos engineering (Netflix Chaos Monkey / Chaos Mesh): quarterly automated exercises that kill the PostgreSQL primary, saturate the hot account, kill the Redis cluster, and verify that RTO targets are met without data loss. Results are reviewed in a post-exercise report
