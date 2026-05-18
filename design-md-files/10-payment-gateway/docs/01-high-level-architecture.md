# 01 — High-Level Architecture: Payment Gateway / Wallet System

## Objective

Define the overall architectural style, service topology, communication patterns, and request flows for the Payment Gateway / Wallet system. Justify the architectural choice against alternatives and document the migration path as the system scales.

---

## 1. Architecture Decision: Modular Monolith → Selective Microservices

### Chosen Approach: Phased Decomposition

Start as a **Modular Monolith with DDD boundaries** enforced at the package/module level. Selectively extract high-stakes or independently-scaling services early (Vault, Fraud Engine). Migrate to full microservices only when operational burden of the monolith demonstrably exceeds decomposition cost.

### Justification

| Factor | Why Modular Monolith Wins at Day 1 |
|---|---|
| Correctness | ACID transactions across payment, ledger, and wallet are trivially maintained within one process; distributed transactions (Saga) add failure modes before you have the team to manage them |
| Operational simplicity | One deployment artifact, one database connection pool, one log stream |
| PCI DSS compliance surface | Smaller network perimeter to certify |
| Team size | A 5–15 engineer team cannot operationally manage 15 microservices |
| Time to market | First version of a payment system needs to prove trust, not architectural purity |

### When to Extract to Microservices

- **Vault Service**: Must be extracted immediately — PCI DSS requires the cardholder data environment (CDE) to be network-isolated and separately auditable.
- **Fraud Engine**: Extract when ML model serving needs independent scaling and data science team ownership.
- **Settlement Service**: Extract when settlement batch processing causes resource contention with the online payment path.
- **Notification Service**: Stateless, IO-bound, independently scalable — extract early.
- **Merchant Portal API**: Extract when merchant-facing API has different SLOs and release cadence.

### What NOT to Extract Prematurely

- Do not separate Wallet from Payment core in V1 — they share ledger consistency requirements.
- Do not create a separate "Transaction Service" and "Order Service" — you will end up with distributed transactions on day one.

---

## 2. Service Topology

```mermaid
graph TB
    subgraph External
        MerchantClient[Merchant Client / Mobile App]
        CardNetworks[Card Networks<br/>Visa / Mastercard / RuPay]
        AcquirerBank[Acquirer Bank API]
        NPCI[NPCI / UPI Switch]
        BankAPI[Bank API<br/>NEFT/IMPS/UPI Payout]
        KYCProvider[KYC / eKYC Provider]
    end

    subgraph DMZ / Edge
        APIGW[API Gateway<br/>Kong / AWS API GW]
        WAF[WAF + DDoS Protection]
        CDN[CDN<br/>Static Assets / Merchant Dashboard]
    end

    subgraph Core Services
        PaymentCore[Payment Core Module<br/>- Payment lifecycle<br/>- Auth/Capture/Refund<br/>- 3DS orchestration]
        WalletCore[Wallet Core Module<br/>- Balance management<br/>- Top-up/Transfer/Withdrawal]
        LedgerModule[Ledger Module<br/>- Double-entry accounting<br/>- Immutable audit trail]
        MerchantModule[Merchant Module<br/>- API keys, webhooks<br/>- Dashboard data]
        SettlementService[Settlement Service<br/>- Batch payout calculation<br/>- Reconciliation]
        NotificationService[Notification Service<br/>- Webhook dispatch<br/>- Email/SMS alerts]
    end

    subgraph Isolated Services
        VaultService[Vault Service<br/>PCI CDE Isolated<br/>Card Tokenization]
        FraudEngine[Fraud Engine<br/>- Rule engine<br/>- ML scorer<br/>- Review queue]
    end

    subgraph Data Layer
        PostgresPrimary[(PostgreSQL Primary<br/>Payment / Wallet / Ledger)]
        PostgresReplica[(PostgreSQL Read Replicas)]
        RedisCluster[(Redis Cluster<br/>Idempotency / Cache / Velocity)]
        KafkaCluster[Kafka Cluster<br/>Event Bus]
        VaultDB[(Vault DB<br/>HSM-backed<br/>PAN / Token store)]
        ElasticSearch[(Elasticsearch<br/>Transaction Search / Analytics)]
    end

    WAF --> APIGW
    CDN --> APIGW
    MerchantClient --> WAF
    APIGW --> PaymentCore
    APIGW --> WalletCore
    APIGW --> MerchantModule

    PaymentCore --> VaultService
    PaymentCore --> FraudEngine
    PaymentCore --> LedgerModule
    PaymentCore --> AcquirerBank
    PaymentCore --> CardNetworks
    PaymentCore --> NPCI
    PaymentCore --> KafkaCluster

    WalletCore --> LedgerModule
    WalletCore --> BankAPI
    WalletCore --> KYCProvider
    WalletCore --> KafkaCluster

    KafkaCluster --> SettlementService
    KafkaCluster --> NotificationService
    KafkaCluster --> FraudEngine
    KafkaCluster --> ElasticSearch

    PaymentCore --> PostgresPrimary
    WalletCore --> PostgresPrimary
    LedgerModule --> PostgresPrimary
    MerchantModule --> PostgresReplica
    SettlementService --> PostgresReplica

    PaymentCore --> RedisCluster
    WalletCore --> RedisCluster
    FraudEngine --> RedisCluster
    VaultService --> VaultDB
```

---

## 3. Request Flow: Card Payment

```mermaid
sequenceDiagram
    participant Merchant
    participant APIGW as API Gateway
    participant PayCore as Payment Core
    participant Redis
    participant Vault as Vault Service
    participant Fraud as Fraud Engine
    participant Ledger as Ledger Module
    participant Acquirer as Acquirer Bank
    participant Kafka

    Merchant->>APIGW: POST /v1/payments {idempotency_key, amount, card_token}
    APIGW->>APIGW: Auth JWT / API Key
    APIGW->>PayCore: Forward request

    PayCore->>Redis: Check idempotency_key (SETNX, TTL=24h)
    alt Duplicate request
        Redis-->>PayCore: Key exists → return cached response
        PayCore-->>Merchant: 200 OK (idempotent replay)
    end

    PayCore->>Fraud: Synchronous fraud score check
    Fraud->>Redis: Velocity counters lookup
    Fraud-->>PayCore: Score + decision (approve/review/reject)
    alt Fraud reject
        PayCore-->>Merchant: 402 Payment Required (fraud decline)
    end

    PayCore->>Vault: Detokenize card_token → PAN + expiry
    Vault-->>PayCore: Network token (Visa Token) for acquirer

    PayCore->>PayCore: Create payment record (status=PENDING)
    PayCore->>Acquirer: Authorization request (ISO 8583 / REST)
    Acquirer-->>PayCore: Auth response (approval code / decline)

    alt Auth approved
        PayCore->>Ledger: Insert double-entry rows (PENDING → AUTHORIZED)
        PayCore->>Redis: Cache payment status (TTL=1h)
        PayCore->>Kafka: Publish payment.authorized event
        PayCore-->>Merchant: 200 OK {payment_id, status=AUTHORIZED}
    else Auth declined
        PayCore->>Kafka: Publish payment.declined event
        PayCore-->>Merchant: 402 {payment_id, status=DECLINED, decline_code}
    end

    Kafka->>NotificationService: Trigger webhook to merchant
```

---

## 4. Request Flow: Wallet Transfer

```mermaid
sequenceDiagram
    participant User
    participant APIGW as API Gateway
    participant WalletCore as Wallet Core
    participant Ledger as Ledger Module
    participant Postgres as PostgreSQL (Transaction)
    participant Redis
    participant Kafka

    User->>APIGW: POST /v1/wallet/transfer {idempotency_key, from, to, amount}
    APIGW->>WalletCore: Forward

    WalletCore->>Redis: Idempotency check
    WalletCore->>WalletCore: Validate sender KYC limit, balance
    WalletCore->>Postgres: BEGIN TRANSACTION
    WalletCore->>Postgres: SELECT balance WHERE user_id=sender FOR UPDATE
    WalletCore->>WalletCore: Check balance >= amount
    WalletCore->>Ledger: INSERT debit entry (sender)
    WalletCore->>Ledger: INSERT credit entry (receiver)
    WalletCore->>Postgres: UPDATE wallet balances (sender-=amount, receiver+=amount)
    WalletCore->>Postgres: COMMIT

    WalletCore->>Redis: Cache updated balances (TTL=30s)
    WalletCore->>Kafka: Publish wallet.transfer.completed
    WalletCore-->>User: 200 OK {transfer_id, status=COMPLETED}
```

---

## 5. Async Flow: UPI Collect Payment

```mermaid
sequenceDiagram
    participant Merchant
    participant PayCore as Payment Core
    participant NPCI as NPCI / UPI Switch
    participant Kafka
    participant NotifSvc as Notification Service
    participant Webhook

    Merchant->>PayCore: POST /v1/payments {method=UPI, vpa, amount}
    PayCore->>NPCI: Initiate UPI collect request
    NPCI-->>PayCore: txn_ref_id (async ACK)
    PayCore-->>Merchant: 202 Accepted {payment_id, status=PENDING}

    Note over NPCI: User approves on their UPI app (30s – 10min)

    NPCI->>PayCore: Callback: payment_status=SUCCESS/FAILURE
    PayCore->>Ledger: Insert entries
    PayCore->>Kafka: Publish payment.completed/payment.failed
    Kafka->>NotifSvc: Consume event
    NotifSvc->>Webhook: POST merchant_webhook_url {payment_id, status}
```

---

## 6. 3DS2 Authentication Flow

```mermaid
sequenceDiagram
    participant Browser
    participant Merchant
    participant PayCore as Payment Core
    participant DS as Directory Server (Visa/MC)
    participant ACS as ACS (Bank)
    participant Acquirer

    Merchant->>PayCore: POST /v1/payments (card, 3ds=required)
    PayCore->>DS: Authentication request (card BIN lookup)
    DS->>ACS: Forward auth request
    ACS-->>DS: Challenge required / frictionless
    DS-->>PayCore: Auth response

    alt Frictionless (risk-based auth passes)
        PayCore->>Acquirer: Auth with 3DS liability shift
    else Challenge required
        PayCore-->>Merchant: {payment_id, status=CHALLENGE_REQUIRED, acs_url}
        Merchant->>Browser: Redirect to ACS challenge
        Browser->>ACS: User completes OTP/biometric
        ACS->>PayCore: Callback: authentication_value (CAVV)
        PayCore->>Acquirer: Auth with CAVV
    end

    Acquirer-->>PayCore: Approval
    PayCore-->>Merchant: {status=AUTHORIZED}
```

---

## 7. Sync vs Async Communication Decisions

| Interaction | Protocol | Reason |
|---|---|---|
| Merchant → Payment Core | REST/HTTPS | Synchronous response required; merchant needs payment_id immediately |
| Payment Core → Vault | REST/gRPC (internal mTLS) | Synchronous; token needed before acquirer call |
| Payment Core → Fraud Engine | gRPC (sync) | Inline fraud check must complete before auth |
| Payment Core → Acquirer | REST/ISO 8583 | Dictated by acquirer protocol |
| Payment Core → Kafka | Async publish | Post-auth events; failure doesn't block payment |
| Kafka → Notification Service | Async consume | Webhook delivery is at-least-once; retries acceptable |
| Settlement → Ledger | Batch read | Nightly; no real-time requirement |
| Fraud Engine → Redis | Sync read | Velocity counters must be millisecond-latency |

---

## 8. Tradeoffs

### Modular Monolith Tradeoffs

| Pro | Con |
|---|---|
| Simple distributed transaction story | Single point of deployment failure if not run with multiple instances |
| Easier debugging (one log stream, one trace) | Module boundary violations possible if team discipline is low |
| Shared database = no eventual consistency headaches on ledger | Cannot scale individual modules independently (mitigated by async read replicas) |
| Lower operational overhead | Large codebase — must enforce module boundaries via Architecture tests (ArchUnit) |

### Why Not Microservices on Day 1

Microservices would require: distributed transactions (Saga) for payment + ledger + wallet, inter-service mTLS setup, independent CI/CD for each service, distributed tracing from day one, service mesh (Istio/Linkerd), and a team large enough to own each service. A 10-engineer team cannot safely operate 12 microservices in a regulated financial environment.

---

## 9. Alternatives Considered

| Approach | Why Rejected |
|---|---|
| Full microservices from day 1 | Premature operational complexity; distributed transaction risk on day 1 |
| Event-sourcing for all state | Extreme complexity for query paths; CQRS required; team must be event-sourcing-experienced |
| CQRS for all domains | Overkill for V1; introduce CQRS for settlement/analytics read models in V2 |
| GraphQL primary API | Payment APIs are RPC-style, not graph-style; REST + webhooks is industry standard |

---

## 10. Interview-Level Discussion Points

- **"Why not use Saga from day one?"** Saga manages distributed transactions — you only need it when the transaction spans services. In a modular monolith, a single DB transaction handles the payment + ledger entries atomically.
- **"How do you ensure the Vault is truly isolated?"** Network segmentation (separate VPC/subnet), separate database, separate deployment pipeline, separate PCI audit scope. The Vault exposes only two endpoints: tokenize and detokenize. Nothing else.
- **"What happens if Kafka is down during payment?"** The payment still succeeds — Kafka publish is post-commit. The Outbox pattern ensures events are eventually published even if Kafka was down at commit time.
- **"When would you move the Ledger module to its own service?"** When ledger write throughput exceeds what the monolith's DB connection pool can handle, or when the accounting team needs to own and deploy it independently.
- **"How do you handle the 5,000 TPS spike?"** Horizontal pod autoscaling on the Payment Core, Redis for idempotency and status caching (avoids DB reads on polling), read replicas for merchant dashboard queries. The acquirer is usually the bottleneck — implement a queue/throttle before the acquirer calls.
