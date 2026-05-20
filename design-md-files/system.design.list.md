# Beginner → Advanced System Design Questions

This list is structured intentionally:

* Start with CRUD + architecture fundamentals
* Move into scaling + async systems
* Then distributed systems
* Finally staff/principal-level architecture problems

These are excellent capstone-style projects for your Spring Boot + Java preparation.

---

# 1. URL Shortener (TinyURL / Bitly)

## Brief

Design a service that converts long URLs into short links and redirects users efficiently.

## Core Concepts

* Hashing
* Database indexing
* Cache
* Read-heavy scaling
* Rate limiting

## Advanced Topics

* Custom aliases
* Analytics
* Expiration
* Geo-routing
* Distributed ID generation

## Learn

* Redis
* CDN
* DB partitioning
* Consistency tradeoffs

## Preferred Stack

* **Language:** Java
* **Framework:** Spring Boot
* **Database:** PostgreSQL
* **Cache:** Redis (short URL → long URL mapping)
* **CDN:** CloudFront / Nginx
* **ID Generation:** Snowflake or Base62 counter
* **Deployment:** Docker + Kubernetes

---

# 2. Pastebin / Code Sharing Platform

## Brief

Users can create and share text/code snippets via unique links.

## Core Concepts

* Blob/text storage
* Expiration handling
* Compression
* Access control

## Advanced Topics

* Syntax highlighting
* Versioning
* Abuse prevention

## Learn

* Object storage
* Async cleanup jobs
* CDN usage

## Preferred Stack

* **Language:** Java
* **Framework:** Spring Boot
* **Database:** PostgreSQL (metadata), S3 / MinIO (blob content)
* **Cache:** Redis (expiry TTL tracking)
* **CDN:** CloudFront for public pastes
* **Deployment:** Docker + Kubernetes

---

# 3. Notification System

## Brief

Build a centralized notification service supporting email, SMS, push, and in-app notifications.

## Core Concepts

* Async processing
* Queue systems
* Retry mechanisms
* Template engine

## Advanced Topics

* Notification preferences
* Batch processing
* Fanout
* Multi-channel fallback

## Learn

* Kafka
* DLQ
* Outbox pattern
* Idempotency

## Preferred Stack

* **Language:** Java
* **Framework:** Spring Boot
* **Database:** PostgreSQL (preferences, templates)
* **Queue:** Kafka (fanout, per-channel topics)
* **Cache:** Redis (deduplication keys, rate limiting)
* **Push:** FCM (Android), APNs (iOS), AWS SNS
* **Email/SMS:** SendGrid, Twilio
* **Deployment:** Docker + Kubernetes

---

# 4. Chat Application (WhatsApp/Slack)

## Brief

Design a real-time messaging platform supporting private and group chats.

## Core Concepts

* WebSockets
* Presence tracking
* Message delivery
* Ordering guarantees

## Advanced Topics

* Typing indicators
* Read receipts
* Offline sync
* Multi-device sync

## Learn

* Event-driven architecture
* Pub/Sub
* Cassandra-style thinking
* Real-time scaling

## Preferred Stack

* **Language:** Java
* **Framework:** Spring Boot + WebSocket (STOMP)
* **Database:** PostgreSQL (messages, threads)
* **Cache:** Redis Pub/Sub (presence, message fan-out)
* **Queue:** Kafka (async delivery guarantees)
* **Deployment:** Docker + Kubernetes

---

# 5. File Storage System (Google Drive/Dropbox)

## Brief

Users upload, download, and share files securely.

## Core Concepts

* Large file uploads
* Metadata management
* Chunking
* Object storage

## Advanced Topics

* Versioning
* Deduplication
* Sharing permissions
* Sync engine

## Learn

* S3 concepts
* CDN
* Eventual consistency

## Preferred Stack

* **Language:** Java
* **Framework:** Spring Boot
* **Database:** PostgreSQL (metadata, permissions)
* **Storage:** S3 / MinIO (chunks, files)
* **Cache:** Redis (session tokens, share links)
* **CDN:** CloudFront (download acceleration)
* **Deployment:** Docker + Kubernetes

---

# 6. Video Streaming Platform (YouTube/Netflix)

## Brief

Store, process, and stream videos globally.

## Core Concepts

* Video transcoding
* Streaming protocols
* CDN
* Recommendation system basics

## Advanced Topics

* Live streaming
* Adaptive bitrate streaming
* Analytics pipeline

## Learn

* Distributed storage
* Processing pipelines
* Kafka streams

## Preferred Stack

* **Language:** Java
* **Framework:** Spring Boot
* **Database:** PostgreSQL (video metadata), S3 (raw + transcoded video)
* **Transcoding:** FFmpeg (worker jobs), AWS Elemental MediaConvert
* **CDN:** CloudFront + HLS adaptive bitrate streaming
* **Queue:** Kafka (transcoding pipeline events)
* **Deployment:** Docker + Kubernetes

---

# 7. Ride-Sharing System (Uber/Ola)

## Brief

Match riders with nearby drivers in real time.

## Core Concepts

* Geospatial queries
* Real-time tracking
* Matching algorithms

## Advanced Topics

* Surge pricing
* Driver allocation
* Route optimization

## Learn

* Redis GEO
* Event streaming
* High availability

## Preferred Stack

* **Language:** Java
* **Framework:** Spring Boot + WebSocket
* **Database:** PostgreSQL (trips, users)
* **Cache:** Redis GEO (driver location), Redis Pub/Sub (real-time tracking)
* **Queue:** Kafka (match events, trip lifecycle)
* **Maps:** Google Maps API / OpenStreetMap
* **Deployment:** Docker + Kubernetes

---

# 8. Food Delivery Platform (Swiggy/Zomato)

## Brief

Users order food from restaurants with real-time tracking.

## Core Concepts

* Order lifecycle
* Delivery tracking
* Payment integration

## Advanced Topics

* Restaurant onboarding
* Dynamic ETA
* Search ranking

## Learn

* Saga pattern
* Distributed workflows

## Preferred Stack

* **Language:** Java
* **Framework:** Spring Boot + WebSocket (order tracking)
* **Database:** PostgreSQL (orders, restaurants)
* **Cache:** Redis (menu cache, session)
* **Queue:** Kafka (order lifecycle, delivery assignment)
* **Search:** Elasticsearch (restaurant/menu search)
* **Maps:** Google Maps API (ETA, routing)
* **Deployment:** Docker + Kubernetes

---

# 9. E-Commerce Platform (Amazon)

## Brief

Design a scalable e-commerce backend.

## Core Concepts

* Product catalog
* Cart
* Orders
* Inventory

## Advanced Topics

* Inventory reservation
* Recommendations
* Flash sales
* Fraud detection

## Learn

* CQRS
* Event sourcing
* Distributed locking

---

# 10. Payment Gateway / Wallet System

## Brief

Handle secure financial transactions between users and merchants.

## Core Concepts

* Double-entry ledger
* Idempotency
* Transaction consistency

## Advanced Topics

* Refunds
* Settlement
* Reconciliation
* Fraud detection

## Learn

* ACID vs BASE
* Distributed transactions
* Audit systems

---

# 11. Banking Core System

## Brief

Build a banking backend with maker-checker, audit, and transaction systems.

## Core Concepts

* Ledger systems
* Audit trails
* Role-based approvals

## Advanced Topics

* Compliance
* Transaction reconciliation
* Event-driven processing

## Learn

* DDD
* Modular monolith
* Security-heavy design

This is especially aligned with your current maker-checker + tracking module experience.

---

# 12. API Gateway

## Brief

Central entry point for all microservices.

## Core Concepts

* Routing
* Authentication
* Rate limiting

## Advanced Topics

* Circuit breakers
* Canary releases
* API aggregation

## Learn

* Spring Cloud Gateway
* Observability
* Security

---

# 13. Distributed Job Scheduler

## Brief

Execute scheduled background jobs reliably across clusters.

## Core Concepts

* Scheduling
* Distributed locking
* Retry handling

## Advanced Topics

* Cron execution guarantees
* Priority queues
* Failover

## Learn

* Quartz
* Redis locks
* Kafka retry patterns

---

# 14. Search Engine (Mini Elasticsearch)

## Brief

Build a search platform supporting indexing and querying.

## Core Concepts

* Inverted indexes
* Ranking
* Tokenization

## Advanced Topics

* Autocomplete
* Fuzzy search
* Relevance scoring

## Learn

* Elasticsearch
* Event-driven indexing

---

# 15. Social Media Feed System (Twitter/X)

## Brief

Generate personalized feeds for millions of users.

## Core Concepts

* Fanout
* Timeline generation
* Caching

## Advanced Topics

* Celebrity problem
* Ranking
* Infinite scrolling

## Learn

* Push vs pull models
* Feed precomputation

---

# 16. Collaborative Document Editor (Google Docs)

## Brief

Multiple users edit documents simultaneously.

## Core Concepts

* Conflict resolution
* Real-time sync
* Operational transforms

## Advanced Topics

* CRDT
* Offline editing
* Version history

## Learn

* Advanced distributed systems

---

# 17. Metrics & Monitoring Platform

## Brief

Collect logs, metrics, and traces from distributed systems.

## Core Concepts

* Time-series data
* Aggregation
* Alerting

## Advanced Topics

* Distributed tracing
* Cardinality issues

## Learn

* Prometheus
* Grafana
* OpenTelemetry

---

# 18. Kafka-like Event Streaming System

## Brief

Build a distributed messaging platform.

## Core Concepts

* Partitioning
* Replication
* Consumer groups

## Advanced Topics

* Ordering guarantees
* Rebalancing
* Backpressure

## Learn

* Distributed systems internals

---

# 19. CI/CD Platform (Mini GitHub Actions/Jenkins)

## Brief

Run distributed build pipelines and deployments.

## Core Concepts

* Job orchestration
* Queue management
* Artifact storage

## Advanced Topics

* Parallel execution
* Sandboxing
* Autoscaling runners

## Learn

* Kubernetes
* Distributed scheduling

---

# 20. Multi-Tenant SaaS CRM

## Brief

Design a CRM platform supporting many organizations securely.

## Core Concepts

* Tenant isolation
* RBAC
* Configurable workflows

## Advanced Topics

* Dynamic schemas
* Plugin systems
* Audit history

## Learn

* DDD
* Modular monolith
* Enterprise architecture

This also maps closely to your Spring Boot CRM/tracking module work.

---

# Financial Domain — Specialized Questions

These go deep on fintech/banking engineering. High signal for BFSI, fintech, and payment company interviews.

---

# 21. Double-Entry Ledger Service

## Brief

Build the financial core: every transaction debits one account and credits another. Immutable, consistent, auditable at scale.

## Core Concepts

* Double-entry bookkeeping (debit = credit invariant)
* Immutable append-only ledger
* Account balance computation
* Idempotency keys
* Atomic multi-leg posting

## Advanced Topics

* Balance snapshot strategy vs recomputing from journal
* Multi-currency support and FX conversion entries
* Reconciliation against external systems
* Point-in-time balance queries
* Ledger sharding by account range

## Learn

* Event sourcing as natural fit
* Optimistic locking for concurrent postings
* ACID guarantees vs eventual consistency tradeoffs
* PostgreSQL advisory locks

## Open Source Reference

* [beancount](https://github.com/beancount/beancount) — plain-text double-entry accounting engine (Python)
* [firefly-iii](https://github.com/firefly-iii/firefly-iii) — self-hosted personal finance with double-entry (PHP, but architecture is instructive)
* [GnuCash](https://github.com/Gnucash/gnucash) — mature double-entry desktop accounting engine

---

# 22. KYC / Identity Verification Pipeline

## Brief

Onboard users by verifying identity documents, liveness, and regulatory watchlists before allowing financial transactions.

## Core Concepts

* Document ingestion and OCR
* Async multi-step verification workflow
* State machine per application
* Watchlist / sanctions screening
* Manual review queue

## Advanced Topics

* Vendor abstraction layer (Onfido, Jumio, DigiLocker)
* Risk-based KYC tiers (light vs full)
* Re-verification triggers on regulatory change
* Data residency and PII handling
* Audit trail for regulators

## Learn

* Outbox pattern for state transitions
* Maker-checker for manual review steps
* Saga for multi-vendor verification chain
* GDPR / data minimization in audit logs

---

# 23. Credit Scoring Engine

## Brief

Compute real-time and batch credit scores using bureau data, behavioral signals, and ML models to drive lending decisions.

## Core Concepts

* Feature store (pre-computed vs real-time features)
* Scoring model versioning
* Explainability (regulatory requirement — ECOA, FCRA)
* Synchronous scoring API under 200ms
* Batch nightly refresh

## Advanced Topics

* Champion-challenger model deployment
* Score drift monitoring
* Cold-start problem for thin-file applicants
* Adverse action reason codes
* Model fairness / bias auditing

## Learn

* CQRS — command triggers score refresh, query reads cached score
* Feature store design (Feast, Tecton concepts)
* A/B testing infrastructure for model comparison
* Kafka Streams for real-time feature computation

---

# 24. Stock Trading Order Book

## Brief

Build a matching engine that accepts buy/sell orders and matches them by price-time priority with microsecond-level latency targets.

## Core Concepts

* Limit order book (bids and asks)
* Price-time priority matching
* Order types: limit, market, stop, IOC, FOK
* Trade execution and fill notification
* Order state machine

## Advanced Topics

* Lock-free data structures for hot path
* Market data fan-out (WebSocket, FIX protocol)
* Circuit breakers and trading halts
* Pre-trade risk checks (position limits, buying power)
* Co-location and kernel bypass (DPDK, RDMA) at HFT scale

## Learn

* FIX protocol basics
* LMAX Disruptor pattern (ring buffer, single writer)
* Event sourcing for order audit trail
* Redis sorted sets as simple order book prototype

## Open Source Reference

* [quickfixj](https://github.com/quickfixj/quickfixj) — FIX protocol engine for Java (industry standard messaging)
* [bitfinex-v2-wss-api-java](https://github.com/jnidzwetzki/bitfinex-v2-wss-api-java) — WebSocket market data reference

---

# 25. Loan Origination & Servicing System

## Brief

End-to-end lending platform: application intake → underwriting → approval → disbursement → EMI schedule → repayment tracking → closure.

## Core Concepts

* Loan application state machine
* Underwriting workflow with credit policy rules
* Amortization schedule generation
* Disbursement to bank account
* EMI collection and prepayment

## Advanced Topics

* Maker-checker for credit decisions above threshold
* Loan restructuring and moratorium handling
* NPA (non-performing asset) classification
* Collections workflow and escalation
* Multi-product support (personal, home, BNPL)

## Learn

* Saga pattern for disbursement (ledger debit + bank transfer + loan activation)
* Temporal workflows for long-running loan lifecycle
* Event sourcing for loan state history
* DDD aggregates: LoanApplication, LoanAccount, RepaymentSchedule

## Open Source Reference

* [apache/fineract](https://github.com/apache/fineract) — Apache Fineract: production-grade open banking / loan management platform (Java, Spring Boot)

---

# 26. Card Transaction Processing (Issuer Side)

## Brief

Process card authorizations, clearing, and settlement for a card-issuing bank. Handle sub-second auth decisions and T+1 settlement.

## Core Concepts

* Authorization flow (online vs offline)
* Clearing and settlement cycle
* Partial authorization
* Reversal and void handling
* Chargeback lifecycle

## Advanced Topics

* Dual-message vs single-message processing
* Stand-in processing when core system is unavailable
* Velocity checks and fraud scoring at auth time
* Interchange fee computation
* Network integration (Visa/Mastercard API concepts)

## Learn

* ISO 8583 message format basics
* Idempotency at every network hop
* Distributed locking for balance check + hold
* Outbox pattern for downstream notification

---

# 27. Reconciliation Engine

## Brief

Match internal ledger records against external sources (bank statements, payment processor reports, card network files) and flag discrepancies.

## Core Concepts

* File ingestion (SFTP, S3, API feeds)
* Record normalization across formats
* Matching algorithm (exact, fuzzy, multi-key)
* Discrepancy classification and aging
* Exception workflow for manual resolution

## Advanced Topics

* Near-real-time reconciliation vs batch
* Multi-leg reconciliation (3-way: ledger vs bank vs processor)
* Break aging and auto-escalation
* Regulatory reporting from reconciliation state
* Recon for high-volume systems (100M+ records/day)

## Learn

* Batch processing patterns (Spring Batch)
* Idempotent file processing (file fingerprinting)
* State machine for discrepancy lifecycle
* Partitioned parallel matching for throughput

---

# 28. Real-Time Fraud Detection System

## Brief

Score every transaction in under 100ms using rules engine + ML model, block suspicious activity, and feed a case management workflow.

## Core Concepts

* Stream processing pipeline
* Feature computation (velocity, geo-anomaly, device fingerprint)
* Rules engine (threshold, pattern-based)
* ML model scoring
* Case management for analyst review

## Advanced Topics

* Feature store for sub-millisecond lookups
* Model feedback loop from confirmed fraud labels
* Graph-based fraud ring detection
* Step-up authentication trigger
* Adaptive thresholds per merchant / user segment

## Learn

* Kafka Streams / Flink for real-time feature aggregation
* Redis for velocity counters (sliding window)
* CQRS — fraud event enriches read model for case analysts
* Dark launch / shadow mode for new models

---

# 29. Cross-Border Payment Network (SWIFT-like)

## Brief

Route international payments across correspondent banks with FX conversion, compliance screening, and guaranteed delivery.

## Core Concepts

* Message routing via correspondent network
* FX rate lookup and conversion
* IBAN / BIC resolution
* Sanctions and AML screening before send
* Settlement finality and confirmation

## Advanced Topics

* SWIFT GPI tracker equivalent (end-to-end payment tracking)
* Nostro/Vostro account management
* T+1 vs same-day settlement tradeoffs
* FX hedging for float exposure
* Regulatory reporting per jurisdiction

## Learn

* ISO 20022 message standard
* Distributed saga for multi-hop payment
* Idempotency across network hops
* Reconciliation of Nostro positions

## Open Source Reference

* [moov-io/ach](https://github.com/moov-io/ach) — Moov ACH: open-source ACH file processing (Go), good reference for payment file formats

---

# 30. Crypto Exchange

## Brief

Exchange where users trade cryptocurrency pairs with order book matching, wallet management, and on-chain settlement.

## Core Concepts

* Order book matching engine
* Hot/cold wallet architecture
* On-chain transaction broadcast and confirmation tracking
* Internal ledger for off-chain balances
* Withdrawal flow with multi-sig signing

## Advanced Topics

* Proof of reserves
* Blockchain reorganization handling
* Gas fee estimation and dynamic pricing
* Liquidity pool integration (DeFi concepts)
* Regulatory compliance (travel rule, KYC gating)

## Learn

* UTXO vs account-based blockchain models
* Threshold signature schemes (TSS) for key management
* Event-driven architecture for on-chain event ingestion
* CAP theorem: exchange internal ledger (CP) vs blockchain (eventual)

---

# 31. Financial Data Aggregation Platform (Plaid-like)

## Brief

Aggregate user financial data across multiple banks via Open Banking APIs or screen scraping, normalize it, and serve to third-party apps.

## Core Concepts

* OAuth2 token management per user-institution pair
* Data normalization across institution formats
* Webhook fan-out to downstream apps
* Transaction categorization
* Balance and account sync

## Advanced Topics

* Token refresh and re-authentication flows
* Rate limit management per institution
* Incremental sync vs full refresh strategy
* PSD2 / Open Banking regulatory compliance
* Data freshness SLA per institution tier

## Learn

* OAuth2 authorization code flow at scale
* Outbox pattern for webhook delivery with retry
* CDC (Change Data Capture) for pushing deltas
* Idempotent transaction deduplication

---

# 32. AML Transaction Monitoring

## Brief

Detect money laundering patterns across accounts using rule-based detection, graph analysis, and generate SAR (Suspicious Activity Reports).

## Core Concepts

* Scenario-based rule engine (structuring, layering, round-trip)
* Graph traversal for entity relationship analysis
* Alert generation and deduplication
* Case management and investigation workflow
* SAR filing with regulatory body

## Advanced Topics

* Network analysis for fraud ring detection (graph DB)
* Typology library management (versioned rule sets)
* Risk scoring aggregation across alert signals
* Regulatory SLA: SAR must be filed within N days of detection
* False positive rate tuning (compliance cost vs risk)

## Learn

* Graph databases (Neo4j) for entity network analysis
* Kafka Streams for transaction pattern windowing
* Event sourcing for immutable investigation audit trail
* Maker-checker for SAR approval before submission

---

# 33. Regulatory Reporting Platform

## Brief

Generate periodic compliance reports (SEBI, RBI, FINRA, Basel III) from financial data with guaranteed accuracy, lineage, and on-time submission.

## Core Concepts

* Data lineage tracking (source → transformation → report)
* Point-in-time snapshots for historical reporting
* Scheduled report generation with SLA monitoring
* Multi-format output (XML, CSV, XBRL)
* Submission tracking and acknowledgement

## Advanced Topics

* Regulatory change management (report format versioning)
* Reconciliation between source data and submitted report
* Correction and resubmission workflow
* Cross-border regulatory differences per entity
* Data quality gates before submission

## Learn

* Immutable event log as source of truth
* CQRS — reporting read model rebuilt from events
* Batch processing with Spring Batch for large extracts
* Schema versioning for report format evolution

---

# 34. PCI-DSS Compliant Card Vault (Tokenization Service)

## Brief

Store raw card data (PAN) in an isolated vault and issue tokens to upstream systems, keeping PCI scope minimal across the organization.

## Core Concepts

* PAN tokenization and detokenization
* Format-preserving encryption (FPE)
* Key management and rotation
* HSM (Hardware Security Module) integration
* PCI-DSS scope boundary enforcement

## Advanced Topics

* Network segmentation: vault in separate isolated zone
* Key ceremony and dual-control procedures
* Token lifecycle (provisioning, suspension, deletion)
* Vault high availability without exposing raw data in transit
* Tokenization at rest vs in-flight

## Learn

* AES-256-GCM, HMAC for token derivation
* HSM APIs (PKCS#11)
* Zero-trust network design around vault
* Audit logging — every detokenization is a compliance event

---

# Recommended Learning Order

## Phase 1 — Foundations

1. URL Shortener
2. Pastebin
3. Notification System
4. API Gateway

---

## Phase 2 — Intermediate

5. Chat Application
6. Food Delivery
7. E-Commerce
8. Distributed Job Scheduler

---

## Phase 3 — Advanced

9. Ride Sharing
10. Payment Gateway
11. Banking System
12. Social Media Feed

---

## Phase 4 — Staff/Principal Level

13. Google Docs
14. Kafka-like System
15. Video Streaming
16. Monitoring Platform
17. CI/CD Platform

---

# Best Questions For Taking Backend Interviews

If your target is:

* Senior Backend Engineer
* Staff Engineer
* Distributed Systems Engineer

Prioritize these:

1. E-Commerce
2. Payment Gateway
3. Notification System
4. Chat System
5. Feed System
6. Ride Sharing
7. Banking System
8. Kafka-like Queue
9. API Gateway
10. Google Docs

---

# Best Match For Your Current Experience

Given your:

* Spring Boot work
* Tracking module work
* Maker-checker flows
* CRM systems
* AOP/security experience

You should strongly focus on:

1. Banking Core System
2. Multi-Tenant CRM
3. Notification Platform
4. Audit/Tracking Platform
5. API Gateway
6. Payment System
7. Distributed Approval Workflow Engine

Those will build very strong “real engineering” discussion depth in interviews.
