# 07 — Scaling Strategy: Loan Origination & Servicing System

## Objective

Define scaling strategy for each component of a lending platform. Unlike a trading system, the bottleneck is not latency but throughput (EMI batch), storage (multi-year data), and workflow parallelism.

---

## Scaling Profile

Loan systems have three distinct load patterns:

| Pattern | When | Bottleneck |
|---------|------|-----------|
| **Application surge** | Festive campaigns, partnership launches | Application Service, Bureau API rate limits |
| **EMI batch burst** | 1st of every month, 6-8 AM window | EMI processing, NACH submission, DB writes |
| **Daily servicing** | Throughout day | Read replicas, cache, status APIs |

**Key difference from real-time systems:** most operations are asynchronous and batch — optimize for throughput, not latency.

---

## Application Service: Horizontal Scaling

Stateless Spring Boot service. Scale horizontally via Kubernetes HPA.

```
Normal load:    5 pods × 500 RPS = 2,500 RPS total
Campaign surge: 20 pods × 500 RPS = 10,000 RPS total
Scale trigger:  CPU > 70% or active threads > 80% of pool
```

**Bureau API throttling:** CIBIL / Experian impose rate limits (e.g., 10 RPS per API key). Multiple API keys (one per lending product or partner) allow higher aggregate throughput. Bureau calls are async — gateway returns 202 immediately, bureau check happens in background.

**Application submission queue:** Under extreme surge, place application submission behind Kafka queue. Gateway returns 202 immediately. Application Service consumes at its own pace. This decouples submission acceptance from processing.

---

## EMI Batch: The Critical Scaling Problem

Monthly EMI batch processes ~66,667 loans in a 4-hour window (6 AM to 10 AM on 1st of month).

### Batch Architecture

```
K8s CronJob triggers at 6:00 AM, 1st of month
    │
    ▼
EMI Scheduler reads amortization_schedule WHERE due_date = today
    │ (query returns ~66,667 rows from partitioned table)
    ▼
Partition into chunks of 500 loans per Kafka message
    │
    ▼
Kafka topic: emi-due-batch (50 partitions)
    │
    ▼
50 EMI Worker pods (each consumes 1 partition)
    │
    ▼
Each worker: submit NACH debit instruction per loan → NPCI
    │
    ▼
NPCI processes overnight, returns results T+1 morning
    │
    ▼
Result processor: update amortization_schedule, repayment_records, loan_accounts
```

**Throughput:** 50 workers × 10 NACH submissions/sec = 500 NACH submissions/sec. 66,667 loans / 500/sec = 133 seconds to submit all instructions. Well within the 4-hour window.

### NACH Submission Batching

NPCI does not accept individual NACH requests at scale — banks submit batch files (CSV/XML format). System generates NACH batch files, uploads to NPCI SFTP, receives result files next morning.

```
File generation: 66,667 records × ~200 bytes = ~13 MB per batch file
SFTP upload: < 1 minute
Result file processing: 66,667 × result records = batch processing job, 30 minutes
```

### Spring Batch for Result Processing

Result files (T+1) processed via Spring Batch:
- Reader: flat file reader (CSV/XML from NPCI)
- Processor: map NACH return codes to `BounceReason`, compute penalty
- Writer: batch update `repayment_records`, `amortization_schedule`, `loan_accounts`

Chunk size: 500 records per transaction. If chunk fails: retry, then move to error file for manual reconciliation.

---

## Read Scaling: Loan Status and Schedule

Borrower loan status is the most frequent read operation.

### Read Replica Strategy

```
Primary:          All writes, EMI batch (must see latest state)
Replica 1:        Borrower portal (status, schedule, payment history)
Replica 2:        Back-office dashboard, reporting
Replica 3:        Analytics, compliance queries
```

Replica lag budget: 5 seconds for Replica 1 (borrower portal). Acceptable — loan status changes are not real-time (EMI processing happens once/month, not second-by-second).

### Redis Caching

| Cached Data | Key Pattern | TTL | Invalidation |
|-------------|------------|-----|-------------|
| Loan account summary | `loan_summary:{loanAccountId}` | 5 minutes | On any account update event |
| Amortization schedule | `loan_schedule:{loanAccountId}` | 1 hour | On repayment or restructuring |
| Interest rate cards | `rate_card:{productType}:{creditGrade}` | 24 hours | On rate change (explicit eviction) |
| Idempotency keys | `idem:{key}` | 24 hours | TTL-based expiry |
| Back-office task counts | `pending_tasks:{teamId}` | 30 seconds | On task state change |

**Cache hit rate target:** 80% for loan summary (status page) — reduces primary DB load significantly during campaign surges when many borrowers check status.

---

## PostgreSQL: Write Throughput

### EMI Batch Writes

Monthly EMI batch generates:
- 66,667 `repayment_records` rows
- 66,667 `amortization_schedule` status updates
- 66,667 `loan_accounts` outstanding_principal updates
- 66,667 ledger entries

Total: ~266,668 writes in 4-hour window = 18.5 writes/sec average (trivially manageable).

Peak if all writes concentrated in 30 minutes: 148 writes/sec. Still within PostgreSQL's capabilities with connection pooling.

**Optimization:** Use PostgreSQL `COPY` for batch inserts of `repayment_records`. Use `UPDATE ... FROM VALUES` for bulk status updates.

### Connection Pooling

PgBouncer in transaction pooling mode:
- Application pods: 200 connections to PgBouncer
- PgBouncer: 30 connections to PostgreSQL primary
- EMI batch workers: 50 connections to PgBouncer (separate pool)

---

## Document Storage Scaling

Documents (PDFs, images) stored in S3, never in PostgreSQL.

```
Daily document volume: 50,000 applications × 10 MB = 500 GB/day
Monthly: 15 TB
Annual: ~180 TB
```

S3 handles this without any scaling concern. Cost optimization:
- Active applications (< 30 days): S3 Standard
- Decided applications (1-5 years): S3 Standard-IA
- Archived applications (5+ years): S3 Glacier
- Regulatory minimum retention: 10 years

Pre-signed URLs for document download (never proxied through application server — reduces bandwidth load by 95%).

---

## Bureau API Rate Limit Management

CIBIL/Experian rate limits are a real constraint for high-volume lenders.

### Strategy

1. **Multiple API keys:** Each lending product line gets its own API key (separate rate limit)
2. **Queue-based rate limiting:** Bureau requests queued in Redis sorted set, consumed at configured rate (e.g., 10 RPS per key)
3. **Cache bureau reports:** Cache CIBIL pull result per (PAN, date) for 24 hours — same borrower applying multiple times that day doesn't re-trigger a pull
4. **Soft credit pull first:** Some bureaus offer a soft pull (no impact to score) for pre-qualification — use soft pull for initial screening, hard pull only at formal application

---

## Underwriting Parallelism

Underwriting is CPU-bound (rules evaluation) and I/O-bound (bureau call).

```
Bureau call: 2-5 seconds (external API)
Rules evaluation: < 100ms
Total per application: ~5 seconds
```

With 50 applications/second at campaign peak:
- 50 applications/sec × 5 seconds = 250 concurrent underwriting requests in flight
- 25 Underwriting Service pods × 10 concurrent bureau calls = 250 concurrent

Kafka decouples submission rate from underwriting throughput — application submission can spike, underwriting processes at its own pace.

---

## Collections Scaling

Collections is relatively low volume but data-intensive:

```
Overdue loans: 3-5% of portfolio (industry average)
Active portfolio: 2,000,000 loans
Overdue at any time: 60,000-100,000 loans
Collections cases: 60,000 cases
Collections agents: 500 agents (100 cases per agent)
```

Collections Service reads from read replica. Assignment algorithm (round-robin + geographic matching) runs as a scheduled job, not real-time.

---

## Overengineering Risks

| Temptation | Why to Resist |
|------------|--------------|
| Event sourcing for loan account | Loan state changes are infrequent — audit log + current state table is simpler and sufficient |
| CQRS for EMI schedule | Read and write patterns are simple — PostgreSQL read replica handles it without separate read model |
| Saga for every operation | Saga for disbursement (crosses system boundaries) — yes. Saga for EMI recording (in one DB) — no, use a transaction |
| Microservices from day 1 | Team of 5-8 cannot operate 8 services with shared databases and Kafka between all of them |
| Real-time DPD calculation | DPD updates nightly in a batch job — real-time DPD has no business value |
