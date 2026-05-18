# 10 — Message Queue Design: Banking Core System

---

## Objective

Define Kafka topology for a banking core system — covering event-driven processing for transactions, notifications, AML monitoring, maker-checker workflows, and regulatory reporting with full audit trail.

---

## Why Kafka for Banking

| Requirement | Kafka Capability |
|---|---|
| Transaction event fan-out (AML, notification, reporting) | Multiple consumer groups per topic |
| Immutable audit trail | Kafka log is append-only and replayable |
| Regulatory reporting (nightly batch from events) | Long retention + log replay |
| Maker-checker async workflow | Events drive state machine |
| AML real-time monitoring | Low-latency streaming |
| EOD batch triggers | Scheduled events to Kafka |

**Kafka's log semantics (immutable, ordered, replayable) align with banking's audit and compliance requirements better than traditional message queues.**

---

## Kafka vs Traditional Queue for Banking

| Aspect | Traditional Queue (RabbitMQ) | Kafka |
|---|---|---|
| Message replay | Not supported | Supported (by offset) |
| Audit trail | Messages deleted after consume | Retained for configured period |
| Multiple consumers | Fan-out exchange (complex) | Consumer groups (simple) |
| Compliance | Not naturally compliant | Append-only log = audit-friendly |
| Ordering | Per-queue ordering | Per-partition ordering |

**Recommendation**: Kafka for event streaming + audit; traditional queue acceptable for simple task queues (e.g., OTP delivery jobs).

---

## Topic Design

### Naming Convention

```
banking.{domain}.{event-type}
```

Core Topics:

```
banking.transaction.initiated
banking.transaction.authorized
banking.transaction.completed
banking.transaction.rejected
banking.transaction.reversed

banking.account.opened
banking.account.closed
banking.account.status-changed
banking.account.kyc-completed

banking.maker-checker.submitted
banking.maker-checker.approved
banking.maker-checker.rejected
banking.maker-checker.executed

banking.notification.requested
banking.aml.alert-generated
banking.aml.transaction-flagged

banking.batch.eod-triggered
banking.batch.interest-calculated
banking.batch.emi-due

banking.regulatory.report-triggered
banking.audit.event-written

banking.dead-letter.all
```

### Topic Configuration

| Topic | Partitions | Retention | Replication |
|---|---|---|---|
| banking.transaction.* | 16 | 365 days | 3 |
| banking.account.* | 8 | 365 days | 3 |
| banking.maker-checker.* | 8 | 90 days | 3 |
| banking.aml.* | 8 | 90 days | 3 |
| banking.notification.requested | 8 | 7 days | 3 |
| banking.batch.* | 4 | 30 days | 3 |
| banking.regulatory.* | 4 | 365 days | 3 |
| banking.dead-letter.all | 4 | 90 days | 3 |

**365-day retention for transactions**: regulatory reconciliation and audit can go back a full year on Kafka. Beyond that: Postgres + S3 archival.

### Partition Key Strategy

| Topic | Partition Key | Reason |
|---|---|---|
| Transaction events | `accountId` | All events for one account ordered |
| Account events | `accountId` | Account state transitions ordered |
| Maker-checker | `requestId` | Workflow steps ordered per request |
| AML alerts | `customerId` | Customer-level AML events ordered |
| Notification | `userId` | Per-user notification ordering |

---

## Consumer Groups

```
banking.transaction.completed
    ├── notification-consumer       (send SMS/email receipt)
    ├── aml-screening-consumer      (real-time AML check)
    ├── audit-log-consumer          (write to immutable audit log)
    ├── statement-projector         (update account statement read model)
    ├── analytics-consumer          (update analytics aggregates)
    └── regulatory-feed-consumer    (feed to regulatory reporting system)
```

Each consumer group is independent. AML screening slowdown doesn't affect notification delivery.

---

## Key Event Flows

### Flow 1: Fund Transfer (NEFT)

```
Customer initiates NEFT transfer
    → Core Banking API
    → Validate: KYC status, account active, sufficient balance
    → DB: debit account, create NEFT transaction record
    → Emit: banking.transaction.initiated (with NEFT details)

Consumer: neft-processor
    → Submit to RBI NPCI NEFT API
    → On success: Emit banking.transaction.completed
    → On failure: Emit banking.transaction.rejected (with reason)

Consumer (banking.transaction.completed):
    notification-consumer → Send SMS: "NEFT of ₹X sent to {beneficiary}"
    aml-consumer → Screen transaction for AML flags
    statement-projector → Update statement read model
    audit-consumer → Write immutable audit log entry
```

### Flow 2: Maker-Checker Workflow

```
Maker submits high-value transfer (₹50L)
    → Validate maker role + transaction limits
    → Save to maker_checker_requests (PENDING)
    → Emit: banking.maker-checker.submitted

Consumer: checker-notification-consumer
    → Notify assigned checker: "New request pending approval: ₹50L transfer"
    → Add to checker's queue UI

Checker reviews + approves:
    → Core Banking API: PATCH /maker-checker/{requestId}/approve
    → Validate: checker != maker, checker has authority for this amount
    → Emit: banking.maker-checker.approved

Consumer: execution-consumer
    → Execute the actual transfer
    → DB: debit account, create transaction
    → Emit: banking.transaction.completed (with makerCheckerId reference)
    → Emit: banking.maker-checker.executed
```

### Flow 3: AML Screening

```
banking.transaction.completed → aml-screening-consumer:
    → Apply rule engine checks:
        - Transaction > ₹10L: mandatory CTR (Cash Transaction Report)
        - Same account sending > ₹50L in 24h: STR (Suspicious Transaction Report)
        - Round amounts (₹1,00,000 exactly): flag for review
        - New beneficiary + large amount: enhanced due diligence
    
    → If flagged: Emit banking.aml.transaction-flagged
    
Consumer (banking.aml.transaction-flagged):
    aml-case-manager → Create AML case for compliance officer review
    regulatory-feed → Flag for FIU-IND report (if STR required)
    notification → Alert compliance officer (not the customer)
```

### Flow 4: EOD Batch Processing

```
Scheduler (every day 11 PM):
    → Emit: banking.batch.eod-triggered { date: "2026-01-15" }

Consumer: interest-calculation-consumer
    → For each savings account:
        Calculate daily interest (balance × rate / 365)
        Emit: banking.batch.interest-calculated { accountId, date, amount }

Consumer: interest-credit-consumer
    → Monthly (last day of month):
        Aggregate daily interest for account
        Credit to account
        Emit: banking.transaction.completed (interest credit)

Consumer: emi-due-consumer  
    → For each loan account with due date today:
        Emit: banking.batch.emi-due { loanAccountId, emiAmount, dueDate }

Consumer: emi-deduction-consumer
    → Debit savings account for EMI
    → Credit loan account
    → Update loan amortization schedule
```

---

## Regulatory Reporting via Kafka

Banking regulatory reports sourced from Kafka events:

```
Daily reports (to RBI, FIU-IND):
  banking.regulatory.report-triggered { reportType: "CTR", date: "2026-01-15" }
    → Consumer: report-generator
    → Aggregate: all transactions > ₹10L for the day from Kafka
    → Format: RBI-specified XML format
    → Submit: to RBI portal via API
    → Archive: S3 with 10-year retention

Monthly reports:
  banking.regulatory.report-triggered { reportType: "SLR", date: "2026-01-31" }
    → Statutory Liquidity Ratio compliance report
```

**Key advantage**: reports generated by replaying Kafka events — no impact on live Postgres, no report queries on transactional DB.

---

## Idempotency and Exactly-Once

Banking requires exactly-once processing for financial events:

### Outbox Pattern

```sql
CREATE TABLE transaction_outbox (
    id              UUID PRIMARY KEY,
    topic           TEXT NOT NULL,
    partition_key   TEXT NOT NULL,
    payload         JSONB NOT NULL,
    created_at      TIMESTAMPTZ DEFAULT NOW(),
    published_at    TIMESTAMPTZ,
    attempts        INT DEFAULT 0
);
```

Transaction + outbox insert: single atomic DB transaction.
Outbox publisher: background job publishes to Kafka, marks published.

### Consumer Idempotency

Every consumer checks before processing:

```sql
-- AML consumer
INSERT INTO aml_screening_results (transaction_id, screened_at, result)
VALUES (?, NOW(), ?)
ON CONFLICT (transaction_id) DO NOTHING;
-- If conflict: already screened, skip
```

---

## Dead Letter Queue in Banking

Banking DLQ requires immediate attention:

```
banking.dead-letter.all
  → Alert: P1 immediately (any banking DLQ message = financial event not processed)
  → Human review: compliance + engineering
  → Root cause analysis required
  → Manual replay after fix with confirmed idempotency
```

Unprocessed banking DLQ messages can mean:
- AML alert not created (regulatory violation)
- Notification not sent (customer dispute)
- Audit log not written (compliance failure)

All DLQ scenarios are P1 in banking.

---

## Kafka Configuration (Banking Grade)

```
Cluster:
  3 brokers + 1 ZooKeeper quorum (or KRaft)
  Replication factor: 3 (all topics)
  min.insync.replicas: 2
  unclean.leader.election.enable: false  ← Critical: prefer unavailability over data loss

Producer:
  acks=all
  enable.idempotence=true
  max.in.flight.requests.per.connection=5
  retries=Integer.MAX_VALUE

Consumer:
  enable.auto.commit=false
  isolation.level=read_committed
  max.poll.records=50  ← Small batch for banking events (slower but more careful)
```

---

## Schema Design

### Avro Schema for Transaction Event

```json
{
  "type": "record",
  "name": "TransactionCompleted",
  "fields": [
    {"name": "transactionId", "type": "string"},
    {"name": "fromAccountId", "type": "string"},
    {"name": "toAccountId", "type": "string"},
    {"name": "amount", "type": "long"},       // Always long (paise), never float
    {"name": "currency", "type": "string"},
    {"name": "transactionType", "type": "string"},
    {"name": "channel", "type": "string"},     // NET_BANKING, MOBILE, ATM, BRANCH
    {"name": "initiatorId", "type": "string"}, // userId or staffId
    {"name": "makerId", "type": ["null", "string"], "default": null},
    {"name": "checkerId", "type": ["null", "string"], "default": null},
    {"name": "completedAt", "type": "string"}, // ISO-8601
    {"name": "referenceNumber", "type": "string"}
  ]
}
```

---

## Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| 365-day retention | Regulatory replay, audit | Storage cost (~5GB/day × 365 = ~1.8TB) |
| Unclean leader election disabled | No data loss | Brief unavailability if all ISR down |
| Small poll batch (50 records) | Careful processing, lower memory | Lower throughput per consumer |
| AML as Kafka consumer | Decoupled from transaction commit | AML screening has latency (minutes, not milliseconds) |
| Outbox pattern | Guaranteed publish | Polling overhead |

---

## Interview Discussion Points

- **"Why use Kafka for banking batch processing?"** → Batch triggered by Kafka events; batch reads from Kafka (replay) not Postgres; no OLAP queries on OLTP DB; regulatory reports generated from event replay
- **"How do you ensure AML screening happens for every transaction?"** → Kafka consumer group with exactly-once processing; outbox guarantees event published; idempotent consumer prevents double-screening; DLQ alert on failure
- **"What's your Kafka retention policy?"** → 365 days for financial events (regulatory audit + dispute resolution); beyond that, Postgres + S3 archival is authoritative
- **"How does maker-checker work with Kafka?"** → Maker submission emits event; checker notification is async consumer; approval emits event; execution is async consumer; entire workflow is event-driven and auditable via Kafka log
- **"Why `unclean.leader.election=false` in banking?"** → Same reason as payments: prefer partition unavailability over electing lagging replica. A missed banking transaction is a regulatory incident; temporary unavailability is recoverable.
