# 11 — Failure Scenarios: Notification System

---

## Objective

Analyze all meaningful failure scenarios in the Notification System, their blast radius, detection mechanisms, mitigation strategies, and the behavior of the system under each failure condition. This section is critical for production readiness and candidate's interview discussions.

---

## Failure Categories

| Category | Examples |
|----------|---------|
| Provider failures | SendGrid down, Twilio rate-limited, FCM batch rejected |
| Infrastructure failures | Kafka broker failure, PostgreSQL primary down, Redis cluster failure |
| Application failures | Fanout pod crash, dispatcher OOM, buggy template render |
| Data failures | Duplicate events, DLQ overflow, invalid user preference state |
| Cascading failures | Campaign burst starving transactional path, provider DDoS from retry storm |

---

## Scenario 1: Email Provider (SendGrid) Outage

### Description
SendGrid returns 503 or connection timeout for all requests. Affects 50% of all notifications.

### Blast Radius
- All email notifications fail to deliver
- 27.5M emails/day undelivered during outage
- SMS, Push, In-App are unaffected (isolated dispatchers)
- OTP users with email as primary channel cannot receive codes → account login failures

### Detection
- Email Dispatcher error rate > 20% in 60-second window
- Prometheus alert: `email_dispatch_failure_rate > 0.20`
- PagerDuty P1 within 2 minutes of outage start

### Mitigation
1. **Circuit Breaker opens** (Resilience4j in Email Dispatcher)
   - State: CLOSED → OPEN after 10 failures in 5 seconds
   - Stops calling SendGrid
   - All `email.dispatch.requested` messages accumulate in Kafka topic (3-day retention)
2. **Fallback to AWS SES** (secondary email provider)
   - Circuit breaker routes to SES when SendGrid circuit is OPEN
   - SES configured with same API abstraction — just a different HTTP endpoint
3. **User-level channel fallback**
   - For users with SMS as fallback channel: Fanout re-routes based on `fallback_channel` preference
   - For transactional (OTP): immediately fall back to SMS if push/email both fail

### Recovery
- SendGrid recovers → Circuit Breaker transitions OPEN → HALF_OPEN → CLOSED on success
- Kafka consumer resumes from last committed offset
- KEDA scales Email Dispatcher to 60 pods for rapid drain
- At 60 pods × 600 emails/sec = 36,000/sec → 10-hour backlog clears in ~13 minutes

### Data Consistency
- No emails are lost — Kafka retention covers 3-day recovery window
- Delivery attempt log correctly reflects all failed attempts
- Idempotency keys prevent duplicate delivery on recovery

---

## Scenario 2: Kafka Broker Failure (Single Node)

### Description
One of the 9 Kafka broker nodes fails unexpectedly.

### Blast Radius
- Partitions whose leader was on the failed broker: leader election in progress (~10–30 seconds)
- During election: produce + consume on affected partitions pauses
- With `min.insync.replicas = 2` and `acks = all`: producers wait, do not fail
- Other partitions (2/3 of all) continue unaffected

### Detection
- Kafka JMX metric: `UnderReplicatedPartitions > 0`
- Kafka exporter Prometheus alert
- P2 alert within 1 minute

### Mitigation
1. **KRaft controller** detects failed broker, triggers leader election for all affected partitions
2. **Producers** block briefly (up to `max.block.ms = 60000` default), then succeed when new leader elected
3. **Outbox relay** accumulates briefly in PostgreSQL outbox table — no data loss
4. **Consumers** pause during partition reassignment, resume automatically after rebalance

### Recovery
- Broker restarts and rejoins cluster
- Replicas catch up (follower replication)
- No manual intervention needed for single broker failure

---

## Scenario 3: PostgreSQL Primary Failure

### Description
The PostgreSQL primary node becomes unavailable. Write operations to `notification_requests` and `delivery_attempts` fail.

### Blast Radius
- Notification API: cannot insert new notifications → returns 503
- Outbox relay: cannot write outbox events → blocks
- Delivery Log Service: cannot write delivery attempt updates (these can queue in Kafka)
- **Reads are unaffected**: all reads route to Redis cache or read replica

### Detection
- PostgreSQL health check probe fails
- HAProxy / Patroni detects primary failure
- P1 alert in < 30 seconds

### Mitigation
1. **Patroni** (PostgreSQL HA) triggers automatic failover
   - Read replica promoted to primary in ~15–30 seconds
   - Connection pool (PgBouncer) re-routes to new primary
2. **Notification API** returns 503 during failover window (30 seconds)
   - Producers should retry with exponential backoff (backed by Retry-After header)
3. **Outbox relay** cannot publish during DB downtime → messages accumulate in outbox table (already on new primary after promotion)
4. **Delivery attempts** that completed during the window: results are in Kafka `delivery.attempt.completed` topic — Delivery Log Service will catch up after DB recovery

### Data Consistency Risk
The 15–30 second window between primary failure and failover completion:
- New notifications submitted during this window: API returns 503 → producer retries → idempotency key ensures no duplicate once DB recovers
- Notifications already in Kafka pipeline: unaffected (delivery continues from dispatcher)
- Delivery attempt updates in flight: buffer in Kafka, applied to new primary after promotion

### RPO/RTO
- RPO: 0 (synchronous WAL replication to replica before failover)
- RTO: ~30 seconds for automatic promotion

---

## Scenario 4: Redis Cluster Failure

### Description
Redis Cluster loses quorum or all nodes become unavailable.

### Blast Radius
- **Preference cache**: all Fanout Service preference lookups go to PostgreSQL
  - 50,000 RPS × preference lookup = 50,000 DB reads/sec → primary DB overwhelmed
- **Template cache**: all dispatchers go to PostgreSQL for templates
  - Smaller impact (~50,000 reads/sec on small template table, cacheable at L1)
- **Idempotency checks**: fall back to PostgreSQL idempotency check (slower but correct)
- **Rate limit counters**: all rate limits disabled → producers can exceed their limits temporarily

### Detection
- Redis health check fails
- P1 alert within 30 seconds

### Mitigation
1. **Preference Service**: activates fallback mode
   - Reads from PostgreSQL read replica
   - Adds 5–10ms per preference lookup (vs 0.5ms from Redis)
   - Read replica can handle ~50K preference reads/sec with proper indexing
2. **Circuit breaker** on Redis client: opens immediately on connection failure, routes to DB fallback
3. **Rate limiting**: temporarily disabled in fallback mode — log anomalous rates, alert ops to monitor for abuse
4. **Idempotency**: falls back to PostgreSQL `SELECT` for duplicate check (slower: 5ms vs 0.5ms)

### Recovery
- Redis Cluster recovered → services detect via health check
- Warm-up: preference and template data is lazily re-populated into Redis on first cache miss after recovery
- Pre-warm option: trigger a batch job to push top 10M user preferences to Redis on recovery

---

## Scenario 5: Campaign Burst Starves Transactional Path

### Description
A 50M-user campaign fires at the same time as a high-traffic period. The `notification.request.submitted` Kafka topic is flooded with 50M campaign messages, causing consumer lag to grow for Fanout Service. OTP notifications for active users are delayed.

### Blast Radius
- OTP delivery latency increases from 1s to potentially minutes
- User login/auth flows break
- Revenue impact proportional to OTP failure rate

### Detection
- Consumer lag on `notification.request.submitted` > 5,000 messages
- p99 OTP delivery latency > 5 seconds
- P1 alert

### Mitigation (Priority Lanes)
1. **Separate Kafka topics for priority**
   - `notification.request.critical` — CRITICAL + HIGH priority notifications
   - `notification.request.batch` — campaign and NORMAL/LOW priority
   - Fanout Service deploys two consumer groups: high-priority group (30 fast instances), batch group (10 instances)
2. **Campaign throttle enforced at source**
   - Campaign Fanout limits publish rate to `throttle_rps` (configurable, default 5,000/sec)
   - A 50M campaign at 5,000/sec takes 2.78 hours — gradual fanout
3. **OTP routing bypass**
   - CRITICAL + TRANSACTIONAL notifications with OTP category bypass the Fanout queue entirely
   - Direct path: API → Fanout (synchronous, in-process) → SMS Dispatcher queue
   - Only feasible for very low volume (OTP = few thousand/sec)

---

## Scenario 6: Retry Storm (Thundering Herd)

### Description
A 2-hour SendGrid outage accumulates 200M failed email delivery attempts. When SendGrid recovers, all retry consumers simultaneously attempt to re-deliver 200M emails at 36,000/sec. SendGrid immediately re-throttles due to the burst.

### Blast Radius
- New SendGrid throttling triggers another outage perception
- Retry storm causes legitimate emails to be delayed again
- Provider relationship risk (SendGrid may flag the account)

### Mitigation
1. **Exponential backoff with jitter** (exponential + random delay offset)
   - Retry 1: 1m ± 30s
   - Retry 2: 5m ± 60s
   - The jitter desynchronizes concurrent retries
2. **Graduated recovery**: When circuit breaker transitions OPEN → HALF_OPEN, only 1% of traffic is routed to SendGrid initially
3. **Campaign email deprioritization**: During recovery, transactional emails drain first before campaign emails
4. **Provider rate limit respect on recovery**: Email Dispatcher checks `provider_tokens` in Redis before re-attempting — limits inbound to exactly SendGrid's stated capacity

---

## Scenario 7: Bad Template Deployment

### Description
A template with a syntax error is deployed (e.g., `{{#if}}` without closing). All notifications using this template fail at render time.

### Blast Radius
- All notifications using the template fail (FAILED status)
- No provider calls are made (fail fast before dispatch)
- Producers get webhook callbacks with FAILED status
- If a high-volume template (e.g., order confirmation), thousands of failures per minute

### Detection
- `template_render_failure_rate` metric spike
- DLQ accumulation for affected notifications
- P2 alert within 5 minutes

### Mitigation
1. **Template validation at publish time** (before template version becomes active)
   - Template Service renders a test payload against the new template
   - Any render error blocks the version from becoming active
2. **Canary deployment for templates**
   - New template version activated for 1% of traffic first
   - Full activation after 15 minutes with no errors
3. **Quick rollback**: Template version can be deprecated (is_active = false) without deployment
   - System falls back to previous active version automatically

---

## Scenario 8: Notification with Expired OTP Content

### Description
An OTP notification is submitted but not dispatched for 10 minutes due to system load (e.g., Kafka consumer lag). The user receives an OTP that was generated 10 minutes ago and may have expired at the application layer.

### Blast Radius
- User enters expired OTP → authentication fails
- User experience degraded; may need to request new OTP

### Mitigation
1. **`expires_at` field on notification**
   - OTP notifications should have `expires_at = now + 5 minutes`
   - Fanout Service checks `expires_at` before dispatching
   - If `expires_at < now`: drop notification, publish `notification.expired` event
2. **Producer responsibility**: Auth Service should set appropriate `expires_at` for OTP notifications
3. **Monitoring**: alert on `notification.expired` events for OTP category (indicates Kafka consumer lag problem)

---

## Scenario 9: Data Corruption in User Preferences

### Description
A bug in the Preference Service sets all users' `opted_in = false` for TRANSACTIONAL SMS due to a migration error.

### Blast Radius
- All transactional SMS (OTP) stopped silently
- No error alerts (notifications are dropped per preference logic, not failed)
- Users cannot log in, verify phones, etc.

### Detection
- `sms.dispatch.requested` topic volume drops to near zero
- Preference Service anomaly: 100M users suddenly have `opted_in = false` for TRANSACTIONAL
- Delivery rate dashboard shows >99% drop in SMS delivery

### Mitigation
1. **Preference audit table** (`preferences_audit`) captures every change with old + new values
2. **Preference change rate alert**: if > 10,000 preferences change per minute outside business hours → immediate alert
3. **Recovery**: roll back preference table to last good snapshot (audit table + point-in-time DB restore)
4. **Defense**: TRANSACTIONAL category preferences should require an explicit `allow_disable_transactional = true` flag to disable — preventing accidental mass opt-out

---

## Failure Resilience Summary

| Component | Failure Mode | Detection | Mitigation |
|-----------|-------------|-----------|-----------|
| Email provider | 503/timeout | Error rate metric | Circuit breaker + SES fallback |
| Kafka broker | Node failure | UnderReplicatedPartitions | KRaft auto-election |
| PostgreSQL primary | Node failure | Health check | Patroni auto-failover |
| Redis cluster | Unavailable | Connection error | DB fallback mode |
| Campaign burst | Consumer lag spike | Lag metric | Priority lanes + throttle |
| Retry storm | Provider re-throttle | Error rate metric | Jitter + graduated recovery |
| Template bug | Render failures | Failure rate metric | Canary + rollback |
| Expired content | Silently dropped | Drop rate metric | expires_at enforcement |

---

## Chaos Engineering Plan

| Experiment | Frequency | What It Tests |
|-----------|-----------|--------------|
| Kill one Kafka broker | Monthly | Leader election speed, producer retry behavior |
| Kill all Email Dispatcher pods | Monthly | Circuit breaker opens, Kafka accumulates gracefully |
| Inject 200ms Redis latency | Monthly | L1 cache absorbs load, Redis L2 is cold path |
| Kill PostgreSQL primary | Quarterly | Patroni failover, API 503 window, no data loss |
| 50M campaign during peak traffic | Quarterly | Priority lane isolation |

---

## Interview Discussion Points

- Why is Redis failure less catastrophic than PostgreSQL failure for the notification system?
- How does exponential backoff with jitter solve the thundering herd problem mathematically?
- What happens to a notification that's in the Fanout Service memory when the Fanout pod crashes?
- Why is "silent drop" (expired notification) preferable to "failed delivery" for OTP use cases?
- How do you design a preference change that must take effect within 1 second for a legally sensitive opt-out?
- What is the difference between a circuit breaker and a rate limiter, and when does each apply to provider calls?
