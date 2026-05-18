# 11 — Failure Scenarios

## Objective

Enumerate the most likely and most impactful failure scenarios for the Multi-Tenant SaaS CRM, define the failure mode, user-visible impact, detection mechanism, mitigation, and recovery procedure. This document is the foundation of the runbook library.

---

## Failure Taxonomy

| Category | Examples |
|---|---|
| Infrastructure failure | Database crash, Redis OOM, Kafka broker loss |
| Application failure | Bug causing 500s, memory leak, deadlock |
| Data failure | Corrupt data write, migration gone wrong, accidental delete |
| Security failure | Cross-tenant data leak, account takeover |
| External dependency failure | Email provider down, payment processor timeout |
| Tenant-induced failure | Bulk import overload, misconfigured webhook loop |
| Operational failure | Botched deployment, config error |

---

## Scenario 1: PostgreSQL Primary Failure

**What happens**: The primary PostgreSQL instance becomes unreachable (hardware failure, network partition, OOM killer).

**User-visible impact**: All write operations fail (HTTP 500 or 503). Reads may continue if read replicas are healthy.

**Detection**:
- PgBouncer health check: unable to connect to primary
- Spring Boot health actuator: `DataSource` health DOWN
- PagerDuty alert within 30 seconds

**Mitigation — Automatic Failover (Phase 2+)**:
- Use AWS RDS Multi-AZ: standby replica in a different Availability Zone with synchronous replication
- AWS RDS Auto-Failover: promotes standby to primary within 60-120 seconds automatically
- PgBouncer reconnects to new primary endpoint (via AWS RDS DNS CNAME update)
- Application retries with exponential backoff (Spring `@Retryable`)

**Recovery**:
- Old primary comes back as standby replica
- After validation, optionally promote back (not strictly necessary if new primary is healthy)

**RPO**: Near-zero (synchronous Multi-AZ replication)
**RTO**: 1-2 minutes (automatic failover)

**Gaps without Multi-AZ**: RTO is hours (manual restore from snapshot). For Starter tier tenants, this may be acceptable per SLA. For Enterprise: Multi-AZ mandatory.

---

## Scenario 2: Redis Cluster Node Failure

**What happens**: One Redis node in the cluster becomes unreachable.

**User-visible impact**:
- Cache misses increase → higher database load → slightly elevated API latency (P99 may increase from 100ms to 300ms)
- If the failed node held session data: affected users receive 401 Unauthorized and must re-login

**Detection**: Redis Cluster node health check via Spring Boot Actuator. Alert on elevated cache miss rate.

**Mitigation**:
- Redis Cluster automatically promotes a replica for the failed shard (within seconds)
- Application handles Redis `CLUSTERDOWN` exceptions with fallback to database (cache-bypass mode)
- Sessions: short JWT access tokens (15min expiry) limit the re-login window for affected users

**Recovery**:
- Redis Cluster self-heals via replica promotion
- If all replicas for a shard fail: that shard's keyspace is unavailable → application falls back to DB for that shard's key range
- Reprovision failed node, Redis Cluster rebalances

---

## Scenario 3: Kafka Broker Failure

**What happens**: 1-2 Kafka brokers in a 6-broker cluster become unreachable.

**User-visible impact**:
- CRM write operations: **unaffected** (writes go to PostgreSQL, outbox relay queues)
- Async side effects lag: audit events, search index updates, workflow triggers, webhooks all stop processing
- User perception: "Why isn't my workflow firing?" — delays increase over time

**Detection**: Kafka broker health check. Consumer lag metrics spike. Alert on `consumer_lag > threshold`.

**Mitigation**:
- With RF=3 and `min.insync.replicas=2`: Kafka tolerates 1 broker failure without any disruption (producer ACKs satisfied with 2 surviving replicas)
- With 2 broker failures: Kafka pauses production for topics with `min.insync.replicas=2`. Outbox relay queues events in PostgreSQL outbox. No data loss.
- Application can disable Kafka publishing temporarily and allow backlog to build in outbox

**Recovery**:
- Replace failed brokers
- Kafka rebalances partitions to new brokers
- Outbox relay resumes, processes backlog in order
- Consumer groups catch up — monitor lag decrease

---

## Scenario 4: Elasticsearch Cluster Unavailability

**What happens**: All Elasticsearch nodes become unreachable or cluster goes red state.

**User-visible impact**:
- Search endpoint `GET /v1/search?q=...` returns 503
- CRM list views using DB queries: **unaffected**
- Full-text contact search: unavailable until ES recovers

**Detection**: ES health check returns `red` status. Kibana dashboard shows cluster state.

**Mitigation**:
- Implement **degraded mode**: when ES is unavailable, search falls back to PostgreSQL `ILIKE` queries (slower but functional for simple text matches)
- Degrade gracefully: search results include a `"search_quality": "degraded"` flag in response
- ES Indexer consumer lag builds up — events accumulate in Kafka topic

**Recovery**:
- ES cluster recovers via shard reallocation
- ES Indexer consumer replays Kafka events from lag offset → ES index catches up
- If ES data is corrupted: rebuild index from Kafka topic (30-day retention) or from PostgreSQL snapshot

---

## Scenario 5: Application Instance Crash (Multiple)

**What happens**: 2 out of 4 application instances crash simultaneously (memory leak, OOM).

**User-visible impact**:
- Traffic redirected to surviving 2 instances → higher CPU load → elevated latency
- If both remaining instances also crash: complete service outage

**Detection**: Load balancer health checks fail for crashed instances → remove from pool. Kubernetes detects pod failures → restarts pods.

**Mitigation**:
- Kubernetes: minimum 3 replicas in production, max 10 via HPA
- Pod Disruption Budget: ensures at least 2 instances always available during rolling deployments
- JVM memory limits set in Kubernetes pod spec to prevent OOM from impacting host
- Circuit breaker (Resilience4j) on database calls to prevent cascading failure under load

**Recovery**:
- Kubernetes restarts crashed pods automatically (within 30 seconds)
- HPA scales up additional instances if surviving instances are under high CPU load
- Root cause: analyze heap dumps from crashed instances (JVM `-XX:+HeapDumpOnOutOfMemoryError`)

---

## Scenario 6: Cross-Tenant Data Leakage Bug

**What happens**: A developer introduces a bug that omits `tenant_id` filter in a query, allowing Tenant A to see Tenant B's contacts.

**User-visible impact**:
- Tenant A can read Tenant B's confidential CRM data
- Catastrophic trust failure — potential SLA breach, regulatory violation

**Detection**:
- PostgreSQL RLS policy rejects the query at DB level (first line of defense)
- Integration test detects cross-tenant query returns data for wrong tenant → CI fails → bug never reaches production (if test coverage is complete)
- If it reaches production: anomaly detection on audit logs — a user reading records with entity_ids that don't match their tenant → alert

**Mitigation (defense layers)**:
1. PostgreSQL RLS — always active backstop
2. Application-layer mandatory `tenant_id` filter
3. Integration tests: cross-tenant query suite
4. Static analysis CI gate: query without `tenant_id` = build failure

**Recovery if it reaches production**:
1. Immediately: deploy hotfix, revoke active sessions for affected tenants
2. Investigation: audit log analysis to determine exact scope of leaked data
3. Notification: inform affected tenants within 72 hours (GDPR Article 33)
4. Forensics: enumerate all records accessed by the compromised query

---

## Scenario 7: Kafka Consumer Stuck in Processing Loop

**What happens**: A bug in the workflow engine consumer causes it to fail processing a specific event, then retry indefinitely, blocking all events in that partition.

**User-visible impact**:
- Workflows stop triggering for all tenants whose events land in the affected partition
- Consumer lag grows unboundedly for that partition

**Detection**: Consumer lag alert for `workflow-engine` group. Metrics show high retry count for specific partition.

**Mitigation**:
- Max retry count (3 retries) with exponential backoff
- After max retries: move message to `workflow.triggers.dlq`
- Advance offset past the failed message → consumer unblocks
- Alert on DLT message: investigate and fix root cause

**Recovery**:
- Fix the bug in workflow engine
- Replay DLT messages for the affected event type from the fixed version

---

## Scenario 8: Botched Database Migration

**What happens**: A developer deploys a migration that adds a NOT NULL column without a default value. PostgreSQL table rewrite blocks all reads and writes for minutes.

**User-visible impact**: Complete service outage for the duration of the table lock (potentially 10-60 minutes for a large table).

**Detection**: Monitoring shows DB query timeout spike. Error logs show `lock timeout` exceptions.

**Mitigation — Prevention**:
- Migration review checklist: no ADD COLUMN NOT NULL without default in one step
- Zero-downtime migration convention (see 05-database-design.md Phase approach)
- Staging environment migration dry-run on a production-sized dataset
- Flyway migration validation in CI before deployment

**Recovery**:
- If migration is running: assess whether to cancel or wait
- If cancelled mid-way: PostgreSQL automatically rolls back the transaction
- If completed but service broken: rollback migration via Flyway `flyway:repair` + reverse migration script
- Post-incident: add migration checker to CI that rejects dangerous DDL patterns

---

## Scenario 9: Tenant Bulk Import Overloads the System

**What happens**: An Enterprise tenant imports 1 million contacts via the bulk import API simultaneously.

**User-visible impact**:
- Other tenants experience slower API responses (shared database connection pool saturation)
- Kafka topic fills with import events, causing consumer lag for all other tenants

**Detection**: Database connection pool utilization > 80%. Per-tenant query time anomaly detection.

**Mitigation**:
- Bulk import is always async (returns job_id, processed in background)
- Per-tenant rate limiting on bulk operations (max 2 concurrent bulk jobs per tenant)
- Bulk import worker pool is separate from the real-time request thread pool — cannot exhaust the API connection pool
- Bulk import events batched: one `contacts.bulk_import_completed` event, not 1M individual events

**Recovery**:
- Rate limit the tenant's bulk import job
- If other tenants are impacted: priority queuing for non-bulk traffic

---

## Scenario 10: Webhook Delivery Loop

**What happens**: A tenant's workflow creates a webhook action that calls an external service, which in turn calls the CRM API and updates a contact, which triggers the workflow again — infinite loop.

**User-visible impact**:
- Tens of thousands of workflow executions per minute for a single tenant
- Kafka topic overwhelmed, consumer lag spikes
- Database write load spikes

**Detection**: Per-tenant workflow execution rate anomaly. Kafka throughput spike. DB write rate spike.

**Mitigation**:
- **Loop detection**: Track `correlation_id` chain depth. If an event's correlation chain exceeds depth 10, reject the workflow trigger
- **Per-tenant workflow execution rate limit**: Max 100 workflow executions per minute per tenant. Exceeding this: pause the tenant's workflow execution, alert the tenant admin
- **Dead letter on loop detection**: Move the event to DLQ with `LOOP_DETECTED` reason

---

## CAP Theorem Implications for CRM

The CRM is designed for **CP (Consistency + Partition Tolerance)** for the primary write path:
- Contact and deal writes require ACID consistency (can't save a half-written contact)
- During network partition: writes are rejected rather than serving stale data

For read paths (search, list views): **AP (Availability + Partition Tolerance)**:
- Read replicas serve potentially slightly stale data
- Elasticsearch serves potentially seconds-old data
- During partition: search continues to serve last-known index state

This tradeoff is correct for a CRM: users expect their saves to be durable and accurate. They accept slightly stale list views.

---

## Interview Discussion Points

- **What is the most dangerous failure for a multi-tenant SaaS?** → Cross-tenant data leakage. It's catastrophic for trust, regulatory standing, and the entire business. Defense in depth (RLS + app layer + tests) is non-negotiable.
- **How do you design for disaster recovery without downtime?** → Multi-AZ primary + standby, automated failover, health-check-based routing. Test the failover quarterly with a game day exercise.
- **What happens to in-flight requests during a failover?** → In-flight DB transactions on the primary fail when the primary goes down. Clients receive 500 errors. They should retry with exponential backoff. The application returns 503 + `Retry-After` header during failover window.
- **How would you conduct a chaos engineering exercise?** → Use Chaos Monkey (or AWS Fault Injection Service) in a staging environment. Kill random pods, terminate DB read replica, increase Kafka consumer lag artificially. Observe if monitoring detects it, if automatic recovery works, and if alert thresholds are calibrated correctly.
