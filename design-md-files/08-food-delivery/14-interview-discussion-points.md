# 14 — Interview Discussion Points: Food Delivery Platform

## Objective
Compile the hardest interviewer challenges, common candidate mistakes, senior/staff-level discussion depth, and "what breaks first" analysis for a food delivery system design interview. Food delivery is uniquely complex because it coordinates three parties (user, restaurant, delivery partner) in a single distributed transaction.

---

## Expected Interviewer Questions by Category

### 1. Saga Pattern & Distributed Transactions

| Question | Strong Answer Direction |
|----------|------------------------|
| How do you handle ordering food across multiple services without a distributed transaction? | Saga pattern — orchestration-based. Order Service is the orchestrator. Each step (payment, restaurant acceptance, delivery assignment) is a separate service call. Each has a compensating transaction if it fails. No 2PC — use eventual consistency with compensation. |
| What is the difference between Saga orchestration and choreography? | **Orchestration**: central coordinator (Order Service) directs each step via command messages. Clear flow, easy to debug. Central point of failure. **Choreography**: each service listens for events and reacts. No central coordinator. Decoupled but hard to trace flow, complex failure handling. Choose orchestration for food delivery — the multi-step, multi-party flow needs a clear state machine owner. |
| What happens if payment succeeds but the restaurant rejects the order? | Compensating transaction: Payment Service receives `REFUND_REQUIRED` command from Order Service Saga → initiates refund. Rider gets notification "Restaurant unavailable, refund initiated." Refund timeline: 3–5 business days for card, instant for wallet. Order status: CANCELLED_RESTAURANT_REJECTION. |
| What if the Saga Orchestrator crashes mid-saga? | Saga state persisted to PostgreSQL on every step. On restart, Order Service reads all orders in non-terminal states → replays from last confirmed Kafka offset. Each step is idempotent — replaying a completed step has no effect. |
| Why did you choose orchestration over choreography here? | The saga spans 6 steps across 4 services. Choreography would mean each service reacts to events from the previous one, creating an implicit flow that's impossible to visualize or debug. When the order gets stuck, you can't tell where. Orchestration gives a single source of truth (Order Service state machine) that tells you exactly which step is in-progress. |

### 2. Search & Discovery

| Question | Strong Answer Direction |
|----------|------------------------|
| How does restaurant search work? | Elasticsearch index with: restaurant name, cuisine type, menu item names, tags (veg/non-veg, dietary). Search query → ES with multi-match (name boosts highest, menu items lower). Result re-ranked by: proximity (within delivery zone), rating, open status (real-time), delivery time estimate. |
| How do you rank restaurants? | Multi-factor score: (0.3 × normalized_rating) + (0.25 × order_volume_score) + (0.2 × proximity_score) + (0.15 × avg_delivery_time_score) + (0.1 × promotional_boost). Weights are tunable. A/B testing framework for weight optimization. |
| How do you handle the "restaurant is 1 km away but delivery takes 45 min" problem? | Delivery time estimate = food prep time + delivery partner travel time. Food prep time: per-restaurant ML model trained on historical order data (varies by item complexity, time of day, order volume). Delivery travel time: distance ÷ average speed for current traffic conditions. Show estimated delivery time, not just distance. |
| How do you keep the search index updated when a restaurant goes offline? | Restaurant Service publishes `RestaurantStatusChanged` event to Kafka → Search Consumer updates Elasticsearch document. In ES, `is_open=false` → filtered from search results. Latency: < 5 seconds from status change to disappearing from search. |

### 3. Delivery Partner Matching

| Question | Strong Answer Direction |
|----------|------------------------|
| How do you assign a delivery partner to an order? | When `RESTAURANT_ACCEPTED` event fires → Delivery Service queries Redis GEO for partners within 5 km of restaurant. Filter: available (not on another delivery), vehicle type match. Score: proximity + rating + acceptance rate. Send push notification. 30s acceptance window. If rejected → next candidate. |
| What if no delivery partner is available? | Expand radius to 10 km. If still none → mark order as `WAITING_FOR_PARTNER` (up to 5 min). Notify rider of delay. If 5 min passes → cancel order with full refund. Alert city ops team: supply shortage detected. |
| How do you handle a delivery partner going offline mid-delivery? | GPS watchdog: no location update for > 2 min during active delivery → mark partner as UNREACHABLE. Attempt push notification (3× retries). If no response in 5 min → reassign delivery to nearest available partner. Show rider "Finding new delivery partner" in app. |
| How do you handle the food getting cold during reassignment? | If reassignment > 10 min after food was ready → issue automatic food credit to rider. Restaurant is not penalized — this is a platform-side delivery failure. Track frequency of reassignment events per zone → operations signal for driver supply issues. |

### 4. Payment & Refunds

| Question | Strong Answer Direction |
|----------|------------------------|
| How do you ensure exactly-once payment? | Idempotency key = orderId + attemptNumber. Payment gateway accepts idempotency key. Even if payment request is retried 3× (network timeout), only one charge is created. Store payment status in DB with idempotency key for local deduplication before calling gateway. |
| What if the customer's card fails? | Payment Service retries 3× with exponential backoff (2s, 4s, 8s). If still failing → order stays in PAYMENT_FAILED state. Rider notified to update payment method. Order held for 10 minutes. If not resolved → auto-cancel. |
| How do you handle a refund for a cancelled order? | Refund command sent from Order Service Saga to Payment Service via Kafka. Payment Service calls gateway refund API. If gateway refund fails → DLQ, retry every 15 min for 24h. If 24h failure → manual escalation + credit to wallet as fallback. |
| How do you split payment if rider uses wallet + card? | Payment splits computed at order time. Wallet deduction first (instant, no gateway). Card charged for remainder. If card fails after wallet deduction → refund wallet, cancel order. Track both transactions under same orderId. |

### 5. Peak Load Handling

| Question | Strong Answer Direction |
|----------|------------------------|
| How do you handle 10× peak traffic during lunch? | Pre-scaled Kubernetes pods (CronJob at 11:30 AM). Kafka absorbs burst (orders queue if Order Service falls slightly behind). Database connection pooler (PgBouncer) prevents connection exhaustion. Redis handles delivery location at high throughput. CDN serves menu images (zero origin load). |
| What's your rate limiting strategy during peak? | Per-user: max 3 concurrent orders. Per-restaurant: order queue limit (restaurant capacity × peak factor). Global: API gateway rate limit per city (prevent runaway clients). Soft limit: show "Restaurant busy" if order queue > threshold — don't reject hard. |
| How does Elasticsearch handle peak search traffic? | Read replicas (ES replica shards) handle search queries. Search is stateless — horizontal scaling. Pre-warm search cache (Redis) for top 100 searches per city. Search queries during peak are mostly "nearby restaurants" — highly cacheable (TTL 30s, per geohash cell). |
| What if Kafka falls behind during peak? | Consumer groups scale (KEDA). Kafka provides backpressure — producers slow if brokers are overwhelmed. If Order Service cannot consume fast enough → orders experience higher latency but are not lost. Monitor consumer lag P99. SLO alert if lag > 1000 messages. |

---

## Common Candidate Mistakes

| Mistake | Why It's Wrong | Correct Approach |
|---------|----------------|-----------------|
| 2PC for order → payment → restaurant | Distributed 2PC locks resources across services, terrible availability | Saga pattern with compensating transactions |
| Storing all state in one database | Restaurant catalog, order data, delivery locations have different access patterns | Separate stores: PG for orders, Redis for locations, ES for search |
| Polling for order status on client | 5M active orders × poll every 5s = 1M RPS on Order Service | WebSocket/SSE push on state change |
| Synchronous call chain across 4 services | One slow service blocks all others, cascading failures | Kafka-driven async saga with timeouts per step |
| Not handling idempotency on payment retry | Network timeout → retry → double charge | Idempotency key per attempt, stored in DB |
| No delivery partner offline handling | Partner disconnects mid-delivery → order stuck forever | GPS watchdog with auto-reassignment |
| Menu data in same DB as orders | Menu is read-heavy catalog; orders are write-heavy transactions | Separate schemas, menu cached in Redis |

---

## Senior Engineer Discussion Points

- **Saga timeout handling**: Every saga step has a timeout (configurable per step). Payment: 30s. Restaurant acceptance: 10 min. Delivery assignment: 5 min. On timeout → compensating action (cancel/refund). Timeouts prevent stuck sagas indefinitely.
- **Outbox pattern for reliable event publishing**: Order Service writes order record + events to PostgreSQL in one ACID transaction. Outbox reader (CDC or polling) publishes events to Kafka. Guarantees: if DB write succeeds, event will be published eventually. Prevents "wrote to DB but Kafka publish failed" split-brain.
- **Elasticsearch index design**: Index once per city (restaurant_bangalore, restaurant_mumbai) vs single global index with `city` field. Per-city indexes: smaller, faster queries, independent scaling. Global index: simpler management. Choose per-city for > 10 cities.
- **Read-after-write consistency**: Rider places order → immediately checks order status → must see PLACED, not old state. Solution: route order status reads for the placing user to primary DB for 5 seconds after write, then switch to replica.

---

## Staff Engineer Discussion Points

- **Choreo vs Orchestration at enterprise scale**: Swiggy started with Kafka choreography. As number of services grew, debugging a failed order required reading 6 Kafka topics to reconstruct the flow. Migrated to orchestration. Cost: central orchestrator is a bottleneck. Mitigation: multiple Saga Orchestrator pods with idempotent processing.
- **Dynamic delivery zone optimization**: Delivery zones (which restaurants serve which areas) are static polygons. ML can optimize zone boundaries based on real delivery times — zones that consistently result in > 45-min delivery should be shrunk. This is a continuous optimization problem, not a one-time config.
- **Multi-restaurant orders**: User orders from Restaurant A and Restaurant B in one cart. Requires: two separate saga sub-flows, two delivery partners or one partner picking up from both, fare calculation with shared delivery fee. Significant product and engineering complexity — only add when single-restaurant orders are stable.
- **Dark kitchen integration**: Multiple virtual restaurant brands operating from one kitchen. Restaurant Service needs brand-level abstraction above kitchen-level. Order routing: brand → kitchen mapping. Menu: per-brand, kitchen handles all. Surge capacity: brand allocation per kitchen. Swiggy's "The Bowl Company" is multiple brands from one dark kitchen.

---

## "What Would Break First?" Analysis

| Scale Factor | First Failure | Second Failure | Third Failure |
|-------------|--------------|----------------|---------------|
| 3× order volume | Order Service pods CPU | Kafka consumer lag | PostgreSQL connection pool |
| 10× search traffic | Elasticsearch JVM heap | Redis search cache miss rate | API gateway rate limiter |
| Delivery partner surge (festival) | Redis GEO write throughput | Notification Service (FCM rate limit) | Delivery Service pod count |
| Payment gateway slowdown | Order saga timeout spike | DLQ overflow | Rider cancellation rate spike |
| Restaurant system offline | Restaurant Service errors | Saga timeout → cancellation wave | Refund pipeline backlog |
| New city launch (all at once) | ES index creation | City config missing for pricing/surge | Delivery zone geo data |

---

## Tradeoff Discussions Interviewers Expect

| Topic | Option A | Option B | Production Choice |
|-------|----------|----------|------------------|
| Saga: orchestration vs choreography | Centralized, debuggable | Decoupled, scalable | Orchestration (debuggability wins for payments) |
| Restaurant search: PostgreSQL vs ES | Simple LIKE queries | Full-text + geo + scoring | Elasticsearch (multi-factor ranking needed) |
| Delivery matching: sync vs async | Rider waits for match | Kafka async, poll for result | Async (partner acceptance time is variable) |
| ETA calculation | Static formula | ML model per restaurant | ML model after enough data (> 1000 orders/restaurant) |
| Payment timing | Pre-auth before order | Charge on delivery | Charge on confirmation (post-restaurant-acceptance) |
| Refund channel | Original payment method | Platform wallet | Original method preferred, wallet as fallback |
