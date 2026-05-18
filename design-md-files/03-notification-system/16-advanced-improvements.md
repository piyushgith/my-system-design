# 16 — Advanced Improvements: Notification System

---

## Objective

Document advanced, production-differentiating improvements to the Notification System that go beyond the standard design. These are topics that separate a good system from a great one, and that candidate's engineers discuss when designing for 100M+ user platforms.

---

## Improvement 1: ML-Based Optimal Send Time

### Problem
Sending a notification at the wrong time (3 AM, during a commute blackout) drastically reduces engagement. A marketing email sent when the user is inactive gets buried.

### Solution: Per-User Engagement Window Prediction

**Data collection:**
- Track `notification_opened_at` and `notification_clicked_at` per user (from email pixel tracking, push open events, in-app read events)
- Store time-of-day + day-of-week of each engagement event per user
- Build a feature vector: `user_id → [hour_0_engagement_rate, hour_1_engagement_rate, ..., hour_23_engagement_rate]`

**Model:**
- Simple baseline: compute per-user average engagement rate per hour-of-day bucket
- Advanced: LSTM model trained on engagement time series
- Output: `optimal_send_window` per user → e.g., "Tuesday 7–9 PM"

**Integration:**
- Scheduler Service consumes optimal send windows from ML Service
- For MARKETING category: Fanout checks send window → schedules notification at optimal time
- For TRANSACTIONAL: bypass (immediate delivery regardless of model)

**Risk:**
- Cold start: new users have no history → default to platform-wide average by user segment
- If ML service is down: fall back to scheduled delivery time or platform prime time window

---

## Improvement 2: Notification Fatigue Suppression

### Problem
A user who triggers multiple events (e.g., 5 order updates in rapid succession) receives 5 notifications in 10 minutes. They become annoyed and start ignoring all notifications (or worse, unsubscribe).

### Solution: Per-User Notification Budget

**Approach:**
- Maintain a per-user, per-category, per-channel notification count in Redis (sliding window)
- Key: `notif_budget:{user_id}:{channel}:{category}:{hour_window}`
- Limit: configurable per category (e.g., Marketing: max 2/day; Product Updates: max 5/day; Transactional: unlimited)
- Fanout Service checks budget before routing to a channel
- On budget exceeded: notification is dropped (for NORMAL/LOW) or deferred to next available window (for HIGH)

**Deduplication:**
- If same category notification arrives within N minutes, coalesce them (send one "You have 5 order updates" digest instead of 5 individual notifications)
- Coalescing requires a short-lived aggregation buffer (Redis sorted set with 5-minute TTL)

**Risk:**
- TRANSACTIONAL (OTP) must always bypass the budget check
- Budget configuration becomes a product policy decision — engineers should not hard-code limits

---

## Improvement 3: Smart Channel Selection

### Problem
Sending to all channels simultaneously is wasteful (cost of SMS is 100x in-app). Sending only to the cheapest channel may miss users who rarely check that channel.

### Solution: Engagement-Weighted Channel Selection

**Algorithm:**
1. For each user, track engagement rate per channel: `email_open_rate`, `push_open_rate`, `sms_response_rate`
2. Rank channels by engagement rate for this user
3. For MARKETING category: send only to top-1 channel (cheapest if tie)
4. For HIGH priority: send to top-2 channels
5. For CRITICAL: send to all channels simultaneously

**Cost savings:**
- If 70% of users engage with push but not email, sending only push for marketing saves 70% of email costs
- At 27.5M marketing emails/day × $0.0001/email = $2,750/day in SendGrid costs → significant savings at scale

**Cold start for new users:**
- Default channel priority: Push > In-App > Email > SMS (cheapest first with highest engagement typically)
- Override: if user explicitly configures a preferred channel, respect it

---

## Improvement 4: Real-Time Delivery Status Streaming

### Problem
Producers currently must poll `GET /notifications/{id}` to check delivery status. For high-volume producers (order service checking OTP delivery), this is inefficient.

### Solution: Server-Sent Events (SSE) or WebSocket for Status Updates

**For internal producers (service-to-service):**
- Webhook callbacks (already in design) are sufficient
- Producer registers a callback URL when submitting the notification
- Delivery Log Service calls the webhook on final status

**For user-facing status (e.g., "Check your email" spinner on login page):**
- Auth Service exposes an SSE endpoint: `GET /auth/otp/status/{session_id}`
- Notification System publishes to a `delivery.realtime.{user_id}` Redis Pub/Sub channel on delivery
- Auth Service subscribes to the Redis channel and pushes SSE events to the waiting client
- Frontend receives delivery confirmation in < 1 second and dismisses the spinner

**Risk:**
- Redis Pub/Sub is fire-and-forget (not durable) → only works for real-time; if client disconnects, event is lost
- For high-value use cases (OTP confirmation), combine SSE with polling fallback

---

## Improvement 5: Template A/B Testing

### Problem
Marketing teams want to test which notification copy drives higher engagement but don't have a scientific framework for it.

### Solution: Built-In Variant Routing

**Design:**
- Template has A/B variants: `template_id = "summer-sale", variant = "control" | "variant_a"`
- A/B experiment defined in Campaign Service: `{template_id: "summer-sale", split: {control: 50%, variant_a: 50%}}`
- Fanout Service: hash `user_id` modulo 100 → assign to bucket → select variant
- Delivery analytics tracks open/click rate per variant
- Statistical significance computed in Analytics Service (Chi-square test)

**Guardrails:**
- A/B tests only apply to MARKETING category (never test OTP templates)
- Variant assignment is sticky: same user sees same variant if they receive the same notification type multiple times
- Experiments auto-stop on statistical significance or after 7 days

---

## Improvement 6: Notification Inbox Real-Time Push (WebSocket)

### Problem
The in-app inbox currently requires the frontend to poll (`GET /inbox`) to discover new notifications. This adds latency (polling interval) and unnecessary load.

### Solution: WebSocket / Server-Sent Events for In-App

**Design:**
- User's browser/mobile app opens a persistent WebSocket connection to a Notification Gateway service
- In-App Dispatcher writes to inbox DB AND publishes to Redis Pub/Sub channel `inapp:{user_id}`
- Notification Gateway subscribes to `inapp:{user_id}` and pushes new inbox items to the connected WebSocket
- Frontend immediately shows notification badge without polling

**Scaling the WebSocket Gateway:**
- Stateful: each WebSocket connection is pinned to a specific gateway pod
- Redis Pub/Sub: all gateway pods subscribe to a shared channel — any pod can push to any user
- At 20M DAU with concurrent connections: 20M × 1 WebSocket = ~20M connections → ~200 gateway pods at 100K connections/pod (manageable with virtual threads)

**Risk:**
- WebSocket connections add memory overhead per connection (~40KB)
- At 20M concurrent: 800 GB RAM across gateway pods → autoscaling + idle connection timeout (15 min)

---

## Improvement 7: Global Rate Limiting with Distributed Token Bucket

### Problem
The current per-service rate limiting uses Redis in a single region. For a multi-region deployment, a service in US-East and one in AP-South-1 don't share rate limit state.

### Solution: Redis Cluster with Cross-Region Replication for Rate Limits

For most use cases, per-region rate limits are sufficient. But for global campaigns where a burst in one region could overwhelm a global provider:

**Design:**
- Use Redis Cluster with reads from local replica, writes to primary (accept slight staleness)
- For provider-level rate limits (SendGrid 600/sec global): dedicate a single Redis region (US-East) as the global token bucket authority
- Dispatchers in all regions decrement from this global Redis before calling SendGrid
- Accept ~10ms cross-region latency for rate limit check — worth it to prevent provider SLA violations

---

## Improvement 8: Self-Healing Templates

### Problem
Template variables are validated against the schema at submission time. But what if a variable changes type in the producing service (e.g., `order_total` changes from `String` to `Number`)? All notifications from that producer fail silently.

### Solution: Variable Coercion + Type-Flexible Templates

**Design:**
- Template variables_schema defines: `{name: "order_total", type: "String", coerce: true}`
- Template renderer attempts type coercion if `coerce = true`: `Number(1499)` → `"1499"` → render as `"₹1499"`
- If coercion fails (null value, unexpected type with coerce=false): fail fast with clear error, never partially render
- Template testing suite: run all registered templates against a golden dataset of variable values on every template update

**Risk:**
- Auto-coercion can produce semantically wrong output (e.g., `order_total = 1499.5` coerced to "1499.5" but template expected "₹1,499.50" formatting)
- Discipline: coerce = true should only be used for simple type conversions, not complex formatting

---

## Improvement 9: Event Sourcing for Notification State

### Problem
The current design uses a status column on `notification_requests` that is mutated as the notification progresses. This makes it difficult to reconstruct "what happened to notification X at every step."

### Solution: Event Sourcing (Optional, for Compliance-Heavy Use Cases)

**Design:**
- Instead of `UPDATE notification_requests SET status='DELIVERED'`, append events to `notification_events` table
- Events: `ACCEPTED`, `VALIDATION_PASSED`, `FANOUT_STARTED`, `EMAIL_DISPATCHED`, `EMAIL_DELIVERED`, `SMS_DISPATCHED`, `SMS_FAILED`, `RETRY_SCHEDULED`, `COMPLETED`
- Current state is derived by replaying events

**When to use:**
- Financial notifications (payment confirmations, invoice notifications) where full audit trail is legally required
- Heavily regulated industries (banking, healthcare)

**When NOT to use:**
- The additional event storage cost (5–10x current storage) is not justified for marketing notifications
- Event replay complexity adds significant engineering overhead

---

## Architecture Self-Critique

### What This Design Does Well
- Producer isolation: an email provider outage does not affect SMS/push
- Durability: Outbox pattern + Kafka means no notification is lost after acceptance
- Scalability: each component scales independently
- Compliance-friendly: preferences, audit logs, GDPR erasure built in

### What Could Be Better
- **Operational complexity**: 13+ services, 25+ Kafka topics, ClickHouse, KEDA — this requires a mature DevOps culture
- **End-to-end testing is hard**: with 6 service hops and external providers, integration testing requires significant test infrastructure
- **Template management UI**: not designed here — needs a separate frontend build
- **Cost at scale**: 50M emails/day at $0.0001 = $5,000/day. ML-based smart channel selection from Improvement 3 is not optional at this scale.

### What a Taking Interviewer Will Challenge

1. "Your SLO says p99 transactional delivery < 5 seconds. How do you actually measure this end-to-end?"
   - Answer: OpenTelemetry trace from `notification_accepted_at` to `delivery_confirmed_at` in `delivery_attempts`, aggregated in Prometheus histogram.

2. "How do you prevent your retry mechanism from DDoSing a recovering provider?"
   - Answer: Exponential backoff with jitter + graduated circuit breaker half-open recovery (1% → 10% → 100% traffic).

3. "Your Fanout service reads 50M user preferences during a campaign. Isn't Redis a bottleneck?"
   - Answer: Two-tier cache (L1 in-process Caffeine + L2 Redis) absorbs most load. At 50K RPS with 30 Fanout pods, each pod only hits Redis for its subset of users, and L1 handles repeated users within the 10-second window.

4. "What happens if a template is updated while a campaign is mid-delivery?"
   - Answer: Template version is pinned at campaign launch time in `batch_campaigns.template_version`. Mid-campaign updates have no effect. Operators must cancel + relaunch the campaign to use a new version.
