# Notification Service — Code Review

Senior engineer review. Architecture analysis, problem inventory, and refactoring strategy with before/after code.

---

## 1. Architecture Summary

**Style:** Layered monolith with event-driven delivery  
**Pattern:** Transactional Outbox → Kafka → Async Consumer  
**Stack:** Spring Boot 3.3.5, PostgreSQL, Redis, Kafka, SendGrid

```
HTTP Request
    │
    ▼
api/controller/          ← HTTP boundary, DTO mapping
    │
    ▼
service/                 ← Business logic, idempotency, orchestration
    │
    ├── domain/repository/ ← JPA, Spring Data
    ├── domain/model/      ← JPA entities (mutable, anemic)
    └── OutboxEvent (DB)
         │
         ▼
OutboxRelayService       ← Scheduled poller (every 200ms)
    │
    ▼
Kafka: notification.requested
    │
    ▼
EmailDispatchConsumer    ← Kafka listener, delivery execution
    │
    └── EmailProvider (Mock/SendGrid)
```

**What works well:**
- Outbox pattern correctly implemented for at-least-once delivery
- Dual-layer idempotency (Redis fast path + DB slow path)
- Template versioning with composite key
- Pluggable `EmailProvider` interface with `@ConditionalOnProperty`
- Centralized exception handling with structured error responses

---

## 2. Key Problem Areas

### A. Structural / SOLID Violations

#### A1. `EmailDispatchConsumer` is a God Component

**File:** `dispatcher/EmailDispatchConsumer.java`  
**Problem:** One class handles 7 distinct responsibilities: expiry gating, preference enforcement, template loading, template rendering, user email resolution, delivery attempt persistence, and metrics emission. Any change to any one of these requires touching this class.

**Why it matters:** Adding SMS dispatch requires duplicating all the shared logic (expiry, preference, template rendering). Testing requires mocking 6 dependencies. Every business rule change has blast radius across all concerns.

#### A2. Controller does DTO-to-entity mapping

**File:** `api/controller/NotificationController.java:35–48`  
**Problem:** The controller constructs a `NotificationRequest` entity from `SubmitNotificationRequest`. Controllers should only handle HTTP concerns (parsing, validation, response serialization). Domain assembly belongs in the service or a dedicated mapper.

```java
// BEFORE — controller building entity (wrong layer)
NotificationRequest notification = NotificationRequest.builder()
    .idempotencyKey(idempotencyKey)
    .category(req.getCategory())
    // ...8 more lines...
    .build();
```

#### A3. `Priority.ordinal()` used for business logic

**File:** `api/controller/NotificationController.java:57`  
**Problem:** `saved.getPriority().ordinal() == 0 ? 5 : 30` — enum ordinal is a JVM implementation detail, not a business concept. If `CRITICAL` is ever inserted before `HIGH`, this silently returns wrong values. Ordinal values shift when enum constants are reordered.

```java
// BEFORE — fragile, order-dependent
.estimatedDeliverySeconds(saved.getPriority().ordinal() == 0 ? 5 : 30)
```

#### A4. `cancel()` conflates "not found" with "wrong status"

**File:** `service/NotificationSubmissionService.java:66–77`  
**Problem:** Returns `false` for both "notification doesn't exist" and "notification is not in a cancellable state". The controller throws the same `NotificationNotCancellableException` for both cases. Callers can't distinguish 404 from 409.

#### A5. Cancellable-state logic is inline, not on the enum

**File:** `service/NotificationSubmissionService.java:68–70`  
**Problem:** `if (PENDING || DISPATCHED)` is a hardcoded business rule buried in the service. Every caller must duplicate this check. If a new status (e.g., `QUEUED`) is added, this must be found and updated.

---

### B. Code Quality Issues

#### B1. Duplicate template rendering logic

**File:** `service/TemplateService.java:72–88`  
**Problem:** `render()` and `renderSubject()` are identical `{{var}}` replacement loops — one applies to `bodyText`, the other to `subject`. DRY violation: any change to rendering logic (e.g., missing variable handling, escaping) must be applied twice.

#### B2. `buildHtmlTemplate()` is a semantics hack

**File:** `dispatcher/EmailDispatchConsumer.java:137–146`  
**Problem:** Creates a fake `Template` with only `bodyText` set to the original template's `bodyHtml`, then passes it to `render()`. This abuses the domain model to work around the fact that `render()` only knows about `bodyText`. Confusing to readers; silently discards all other template fields.

#### B3. `isOptedIn()` loads all preferences to check one

**File:** `service/PreferenceService.java:52–58`  
**Problem:** Fetches all preferences for a user (potentially 10–20 rows) from Redis or DB, then does an in-memory `stream().filter()` to find the one matching `(channel, category)`. The repository has no targeted lookup method.

```java
// BEFORE — load all, filter in memory
return getPreferences(userId).stream()
    .filter(p -> p.getChannel() == channel && p.getCategory() == category)
    .findFirst()
    .map(p -> p.isOptedIn() && !p.isHardUnsubscribed())
    .orElse(true);
```

#### B4. Quiet hours exist in the model but are never enforced

**File:** `domain/model/UserNotificationPreference.java:36–44`  
**Problem:** `quietHoursStart`, `quietHoursEnd`, and `timezone` are persisted but `isOptedIn()` never checks them. The feature is half-implemented: users can set quiet hours, but notifications are still delivered during those hours.

#### B5. `hardUnsubscribe()` silently does nothing if no record exists

**File:** `service/PreferenceService.java:86–96`  
**Problem:** Uses `ifPresent()` — if the user has no existing preference record for that `(channel, category)`, the unsubscribe silently fails. The caller receives no signal. A user who was never opted-in remains unsubscribable.

#### B6. `NotificationRequest.createdAt` set in two places

**File:** `domain/model/NotificationRequest.java:77–79` and `service/NotificationSubmissionService.java:44`  
**Problem:** `@Builder.Default private Instant createdAt = Instant.now()` and then `request.setCreatedAt(Instant.now())` in the service. Two competing sources of truth. The entity default is irrelevant and misleading; the service always overwrites it.

---

### C. Performance Bottlenecks

#### C1. N individual `save()` calls in outbox relay loop

**File:** `service/OutboxRelayService.java:43–57`  
**Problem:** `outboxRepository.save(event)` inside the loop = N separate `UPDATE` round-trips to the database per batch. With the default batch size of 100 and 200ms poll interval, this is up to 500 DB round-trips per second at scale.

```java
// BEFORE — N writes inside loop
for (OutboxEvent event : pending) {
    // process...
    outboxRepository.save(event); // ← N separate round-trips
}
```

#### C2. Wrong index on `outbox_events` table

**File:** `domain/model/OutboxEvent.java:13–15`  
**Problem:** `@Index(columnList = "created_at")` — the `findPendingEvents` query filters by `status = :status`. Without `status` in the index, every 200ms poll does a full table scan with a post-filter. As the outbox table grows (PUBLISHED rows accumulate), this scan gets progressively more expensive.

**Fix:** Index should be `(status, created_at)` to enable an index range scan on `status = 'PENDING'`.

#### C3. `@Transactional` wraps external HTTP call

**File:** `dispatcher/EmailDispatchConsumer.java:47–48`  
**Problem:** `@Transactional` holds a DB connection open for the entire duration of `consume()`, including the external `emailProvider.send()` call. If SendGrid responds in 2 seconds, a connection is held in the pool for that 2 seconds. Under load, this exhausts the connection pool.

The transaction should only cover: (1) saving the `DeliveryAttempt` and (2) updating the `NotificationRequest` status — after the external call completes.

#### C4. `RestClient` rebuilt on every email send

**File:** `dispatcher/SendGridEmailProvider.java:38`  
**Problem:** `restClientBuilder.build()` is called inside `send()` per invocation. Building a `RestClient` allocates an HTTP client with its connection pool. This should be a singleton built once at startup.

#### C5. Two separate DB round-trips to update notification status

**File:** `dispatcher/EmailDispatchConsumer.java:120–129`  
**Problem:** `updateNotificationStatus()` does `findById()` + `save()` — a SELECT then UPDATE — when a single `@Modifying @Query("UPDATE ... SET status = ... WHERE ...")` would suffice.

---

### D. Reliability / Correctness Issues

#### D1. Kafka publish is fire-and-forget but outbox marks PUBLISHED synchronously

**File:** `service/OutboxRelayService.java:48–51` and `kafka/producer/NotificationEventProducer.java:22–33`  
**Problem:** This is the most critical correctness bug.

```java
// OutboxRelayService — marks PUBLISHED *before* Kafka confirms
eventProducer.publishNotificationRequested(kafkaEvent);  // async future
event.setStatus(OutboxStatus.PUBLISHED);                 // always runs
event.setPublishedAt(Instant.now());
outboxRepository.save(event);
```

`kafkaTemplate.send()` returns a `CompletableFuture`. The error handler in `whenComplete` only logs. The outbox event is marked `PUBLISHED` regardless of whether Kafka actually accepted the message. If Kafka is briefly unavailable, messages are silently lost.

**Fix:** Either (a) make the send synchronous (`kafkaTemplate.send().get()`), or (b) mark PUBLISHED only inside the `whenComplete` success branch, with a separate retry loop for PENDING events that failed to publish.

#### D2. DLT hardcoded to partition 0

**File:** `kafka/config/KafkaConsumerConfig.java:51–53`  
**Problem:** All failed messages route to partition 0 of the DLT topic. With high error rates, partition 0 becomes a hot partition. `DeadLetterPublishingRecoverer`'s default strategy (same partition as the original record) is better — it preserves ordering semantics and distributes load.

---

## 3. Refactoring Plan (Prioritized)

| Priority | Issue | Effort | Risk |
|----------|-------|--------|------|
| 🔴 Critical | D1: Kafka publish fire-and-forget vs outbox PUBLISHED | Medium | Data loss |
| 🔴 Critical | C2: Wrong outbox index | Low | Performance |
| 🟠 High | A1: EmailDispatchConsumer decomposition | High | Architecture |
| 🟠 High | C1: N saves in outbox relay loop | Low | Performance |
| 🟠 High | C3: Transaction wraps external HTTP | Medium | Reliability |
| 🟡 Medium | A2: Controller DTO mapping | Low | Maintainability |
| 🟡 Medium | A3: Priority.ordinal() | Low | Correctness |
| 🟡 Medium | B1: Duplicate render logic | Low | Maintainability |
| 🟡 Medium | B3: isOptedIn loads all prefs | Low | Performance |
| 🟡 Medium | B4: Quiet hours not enforced | Medium | Feature gap |
| 🟡 Medium | A4/A5: cancel() error semantics | Low | Correctness |
| 🟢 Low | C4: RestClient rebuilt per send | Low | Performance |
| 🟢 Low | B2: buildHtmlTemplate hack | Low | Readability |
| 🟢 Low | B5: hardUnsubscribe silent fail | Low | Correctness |
| 🟢 Low | D2: DLT partition 0 | Low | Scalability |

---

## 4. Code Improvements

### Fix 1: Wrong outbox index (C2)

```java
// BEFORE
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_pending", columnList = "created_at")
    }
)

// AFTER — status first (equality filter), created_at second (sort)
@Table(
    name = "outbox_events",
    indexes = {
        @Index(name = "idx_outbox_pending", columnList = "status, created_at")
    }
)
```

Also the JPQL query parameter name is redundant:
```java
// BEFORE
@Query("SELECT e FROM OutboxEvent e WHERE e.status = :status ORDER BY e.createdAt ASC")
List<OutboxEvent> findPendingEvents(OutboxStatus status, Pageable pageable);

// AFTER — remove parameter bloat, query only ever called with PENDING
@Query("SELECT e FROM OutboxEvent e WHERE e.status = 'PENDING' ORDER BY e.createdAt ASC")
List<OutboxEvent> findPendingEvents(Pageable pageable);
```

---

### Fix 2: N+1 saves in OutboxRelayService (C1)

```java
// BEFORE — N round-trips
for (OutboxEvent event : pending) {
    try {
        NotificationRequestedEvent kafkaEvent = objectMapper.readValue(...);
        eventProducer.publishNotificationRequested(kafkaEvent);
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setPublishedAt(Instant.now());
    } catch (JsonProcessingException e) {
        log.error(...);
        event.setStatus(OutboxStatus.FAILED);
    }
    outboxRepository.save(event); // ← inside loop
}

// AFTER — single batch write
for (OutboxEvent event : pending) {
    try {
        NotificationRequestedEvent kafkaEvent = objectMapper.readValue(
                event.getPayload(), NotificationRequestedEvent.class);
        eventProducer.publishNotificationRequested(kafkaEvent);
        event.setStatus(OutboxStatus.PUBLISHED);
        event.setPublishedAt(Instant.now());
    } catch (JsonProcessingException e) {
        log.error("Failed to deserialize outbox event id={} error={}", event.getEventId(), e.getMessage());
        event.setStatus(OutboxStatus.FAILED);
    }
}
outboxRepository.saveAll(pending); // ← one round-trip
```

---

### Fix 3: Kafka fire-and-forget breaks outbox guarantee (D1)

The root problem: `eventProducer.publishNotificationRequested()` returns `void` and the async callback only logs. The relay marks PUBLISHED unconditionally.

**Option A — synchronous send (simplest, correctness-first):**

```java
// NotificationEventProducer — make it synchronous
public void publishNotificationRequested(NotificationRequestedEvent event) {
    String topic = props.getKafka().getTopics().getNotificationRequested();
    String key = event.getNotificationId().toString();
    try {
        var result = kafkaTemplate.send(topic, key, event).get(5, TimeUnit.SECONDS);
        log.debug("Published notification.requested id={} partition={} offset={}",
                event.getNotificationId(),
                result.getRecordMetadata().partition(),
                result.getRecordMetadata().offset());
    } catch (Exception e) {
        throw new KafkaPublishException("Failed to publish notificationId=" + event.getNotificationId(), e);
    }
}
```

Then in `OutboxRelayService`, catch `KafkaPublishException` and mark the event `FAILED` instead of `PUBLISHED`. The next poll will retry it.

**Option B — async with callback-driven status update (more complex, higher throughput):**  
Return the `CompletableFuture<SendResult>` from the producer, collect all futures, wait for completion, then batch-update statuses based on results. Correct but significantly more complex.

**Recommendation:** Use Option A for correctness. The 200ms poll interval already means throughput is bounded by poll frequency, not Kafka send latency.

---

### Fix 4: Priority enum owns delivery estimate (A3)

```java
// BEFORE — in controller, fragile ordinal check
.estimatedDeliverySeconds(saved.getPriority().ordinal() == 0 ? 5 : 30)

// AFTER — behavior belongs on the enum
public enum Priority {
    CRITICAL(5),
    HIGH(10),
    NORMAL(30),
    LOW(60);

    private final int estimatedDeliverySeconds;

    Priority(int estimatedDeliverySeconds) {
        this.estimatedDeliverySeconds = estimatedDeliverySeconds;
    }

    public int getEstimatedDeliverySeconds() {
        return estimatedDeliverySeconds;
    }
}

// Controller becomes:
.estimatedDeliverySeconds(saved.getPriority().getEstimatedDeliverySeconds())
```

---

### Fix 5: NotificationStatus owns cancellable logic (A5)

```java
// BEFORE — inline check in service
if (n.getStatus() == NotificationStatus.PENDING
        || n.getStatus() == NotificationStatus.DISPATCHED) {

// AFTER — enum owns the rule
public enum NotificationStatus {
    PENDING, DISPATCHED, PARTIALLY_DELIVERED, DELIVERED, FAILED, CANCELLED, EXPIRED;

    public boolean isCancellable() {
        return this == PENDING || this == DISPATCHED;
    }
}

// Service:
if (n.getStatus().isCancellable()) {
    n.setStatus(NotificationStatus.CANCELLED);
    ...
}
```

---

### Fix 6: Cancel — distinguish 404 from 409 (A4)

```java
// BEFORE — returns false for both not-found and not-cancellable
public boolean cancel(UUID notificationId) {
    return notificationRepository.findById(notificationId).map(n -> {
        if (n.getStatus().isCancellable()) { ... return true; }
        return false;
    }).orElse(false);
}

// AFTER — explicit throw for not-found, false only for wrong status
@Transactional
public boolean cancel(UUID notificationId) {
    NotificationRequest n = notificationRepository.findById(notificationId)
            .orElseThrow(() -> new NotificationNotFoundException(notificationId));
    if (!n.getStatus().isCancellable()) {
        return false;
    }
    n.setStatus(NotificationStatus.CANCELLED);
    n.setCompletedAt(Instant.now());
    notificationRepository.save(n);
    return true;
}
```

The controller then only throws `NotificationNotCancellableException` when `cancel()` returns `false` (409), while `NotificationNotFoundException` propagates directly to the 404 handler.

---

### Fix 7: Duplicate render logic in TemplateService (B1)

```java
// BEFORE — two methods, same loop
public String render(Template template, Map<String, String> variables) {
    String body = template.getBodyText();
    if (variables == null) return body;
    for (Map.Entry<String, String> entry : variables.entrySet()) {
        body = body.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return body;
}

public String renderSubject(Template template, Map<String, String> variables) {
    if (template.getSubject() == null) return "";
    String subject = template.getSubject();
    if (variables == null) return subject;
    for (Map.Entry<String, String> entry : variables.entrySet()) {
        subject = subject.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return subject;
}

// AFTER — extract core logic, delegate from both
private String interpolate(String template, Map<String, String> variables) {
    if (template == null) return "";
    if (variables == null || variables.isEmpty()) return template;
    String result = template;
    for (Map.Entry<String, String> entry : variables.entrySet()) {
        result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
    }
    return result;
}

public String render(Template template, Map<String, String> variables) {
    return interpolate(template.getBodyText(), variables);
}

public String renderSubject(Template template, Map<String, String> variables) {
    return interpolate(template.getSubject(), variables);
}

public String renderHtml(Template template, Map<String, String> variables) {
    return interpolate(template.getBodyHtml(), variables);
}
```

This also eliminates the `buildHtmlTemplate()` hack in `EmailDispatchConsumer` — replace:
```java
// BEFORE — fake Template object
? templateService.render(buildHtmlTemplate(template), event.getVariables())

// AFTER — direct
? templateService.renderHtml(template, event.getVariables())
```

---

### Fix 8: EmailDispatchConsumer decomposition (A1)

Extract each concern into a collaborating class. The consumer becomes an orchestrator:

```java
// Extract: NotificationGate — expiry + preference checks
@Component
public class NotificationGate {
    private final PreferenceService preferenceService;

    public GateResult check(NotificationRequestedEvent event) {
        if (isExpired(event)) return GateResult.expired();
        if (!isEmailAllowed(event)) return GateResult.optedOut();
        if (isInQuietHours(event)) return GateResult.quietHours();
        return GateResult.allowed();
    }

    private boolean isExpired(NotificationRequestedEvent event) {
        return event.getExpiresAt() != null && Instant.now().isAfter(event.getExpiresAt());
    }

    private boolean isEmailAllowed(NotificationRequestedEvent event) {
        List<Channel> overrides = event.getChannelsOverride();
        if (overrides != null && overrides.contains(Channel.EMAIL)) return true;
        return preferenceService.isOptedIn(event.getRecipientUserId(), Channel.EMAIL, event.getCategory());
    }
}

// Extract: UserEmailResolver — decouple stub/real implementation
public interface UserEmailResolver {
    String resolveEmail(UUID userId);
}

@Component
@ConditionalOnProperty(name = "app.user-resolver", havingValue = "stub", matchIfMissing = true)
public class StubUserEmailResolver implements UserEmailResolver {
    @Override
    public String resolveEmail(UUID userId) {
        return "user-" + userId + "@example.com";
    }
}

// Extract: DeliveryRecorder — persistence of attempt + status update
@Component
public class DeliveryRecorder {
    private final DeliveryAttemptRepository deliveryAttemptRepository;
    private final NotificationRequestRepository notificationRequestRepository;

    @Transactional
    public void record(UUID notificationId, Channel channel, String provider,
                       EmailProvider.EmailSendResult result, int attemptNumber) {
        DeliveryAttempt attempt = DeliveryAttempt.builder()
                .notificationId(notificationId)
                .channel(channel)
                .provider(result.success() ? provider : "unknown")
                .providerMessageId(result.providerMessageId())
                .status(result.success() ? DeliveryStatus.DELIVERED : DeliveryStatus.FAILED)
                .attemptNumber(attemptNumber)
                .attemptedAt(Instant.now())
                .deliveredAt(result.success() ? Instant.now() : null)
                .failureReason(result.failureReason())
                .failureCode(result.failureCode())
                .build();
        deliveryAttemptRepository.save(attempt);

        notificationRequestRepository.updateStatus(
                notificationId,
                result.success() ? NotificationStatus.DELIVERED : NotificationStatus.FAILED,
                Instant.now()
        );
    }
}
```

The consumer becomes lean:
```java
@KafkaListener(topics = "${app.kafka.topics.notification-requested}", ...)
public void consume(NotificationRequestedEvent event,
                    @Header(KafkaHeaders.DELIVERY_ATTEMPT) int deliveryAttempt) {

    UUID notificationId = event.getNotificationId();
    log.info("EmailDispatcher consuming notificationId={} attempt={}", notificationId, deliveryAttempt);

    GateResult gate = notificationGate.check(event);
    if (!gate.isAllowed()) {
        handleGateResult(notificationId, gate);
        return;
    }

    Template template = templateService.getTemplate(
            event.getTemplateId(), event.getTemplateVersion(), Channel.EMAIL, "en-US");

    String toEmail = userEmailResolver.resolveEmail(event.getRecipientUserId());

    EmailProvider.EmailMessage message = buildMessage(event, template, toEmail);
    EmailProvider.EmailSendResult result = emailProvider.send(message);

    deliveryRecorder.record(notificationId, Channel.EMAIL,
            emailProvider.getClass().getSimpleName(), result, deliveryAttempt);

    meterRegistry.counter("notification." + (result.success() ? "delivered" : "failed"),
            "channel", "EMAIL").increment();

    if (!result.success()) {
        throw new EmailDeliveryException(result.failureReason());
    }
}
```

---

### Fix 9: Fix transaction scope — don't hold connection during HTTP (C3)

```java
// BEFORE — @Transactional on the whole consume method
@KafkaListener(...)
@Transactional  // ← holds DB connection during emailProvider.send()
public void consume(...) { ... }

// AFTER — transaction only wraps DB persistence, not the HTTP call
@KafkaListener(...)
public void consume(NotificationRequestedEvent event, ...) {
    // no transaction here — Redis reads are fine without it
    GateResult gate = notificationGate.check(event);
    ...
    Template template = templateService.getTemplate(...);  // Redis read, no TX needed
    String toEmail = userEmailResolver.resolveEmail(...);

    EmailProvider.EmailSendResult result = emailProvider.send(message);  // external HTTP

    // transaction only here — short, bounded
    deliveryRecorder.record(...);  // @Transactional on the recorder method
}
```

---

### Fix 10: RestClient built once, not per send (C4)

```java
// BEFORE — rebuilt on every call
public EmailSendResult send(EmailMessage message) {
    restClientBuilder.build()  // ← allocates new HTTP client each time
        .post()
        ...
}

// AFTER — build once at construction
@Component
@ConditionalOnProperty(name = "app.email.provider", havingValue = "sendgrid")
public class SendGridEmailProvider implements EmailProvider {

    private final RestClient restClient;
    private final AppProperties props;

    public SendGridEmailProvider(AppProperties props, RestClient.Builder restClientBuilder) {
        this.props = props;
        this.restClient = restClientBuilder
                .baseUrl(SENDGRID_API_URL)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Override
    public EmailSendResult send(EmailMessage message) {
        try {
            restClient.post()
                    .uri("")
                    .header("Authorization", "Bearer " + props.getEmail().getSendgrid().getApiKey())
                    .body(buildBody(message))
                    .retrieve()
                    .toBodilessEntity();
            return new EmailSendResult(true, "sg-" + System.currentTimeMillis(), null, null);
        } catch (Exception e) {
            log.error("SendGrid send failed notificationId={} error={}", message.notificationId(), e.getMessage());
            return new EmailSendResult(false, null, e.getMessage(), "PROVIDER_ERROR");
        }
    }
}
```

---

### Fix 11: Remove redundant `createdAt` initialization (B6)

```java
// BEFORE — entity has default AND service sets it explicitly
// NotificationRequest.java
@Builder.Default
private Instant createdAt = Instant.now();  // redundant

// NotificationSubmissionService.java
request.setCreatedAt(Instant.now());  // overwrites builder default

// AFTER — pick one. Use @CreationTimestamp for JPA lifecycle control:
@CreationTimestamp
@Column(name = "created_at", nullable = false, updatable = false)
private Instant createdAt;
// Remove the setCreatedAt() call in the service entirely.
```

---

## 5. Architecture Critique

**What this design gets right:**  
The outbox pattern correctly prevents dual-write (notification saved + event published atomically). The `@ConditionalOnProperty` email provider is clean and testable. The structured error response format is consistent. Template versioning with composite keys is production-ready.

**Where it will break at scale:**

1. **Outbox polling at 200ms** — At high throughput, a single-threaded outbox poller on a 200ms loop is a bottleneck. Solution: increase `batchSize`, use optimistic locking (`@Version`) to allow multiple relay instances, or adopt CDC (Debezium) to replace polling entirely.

2. **Single Kafka consumer group for email** — `email-dispatcher` is a single consumer group. Scaling requires increasing partition count and consumer instances together. If topic has 4 partitions, max parallelism is 4 consumers.

3. **Preference cache is entire list per user** — Caching the full preference list per user works at small scale but grows unbounded as channel×category combinations expand. Consider caching individual `(userId, channel, category)` lookups as atomic keys with shorter TTLs.

4. **No CANCELLED/EXPIRED outbox cleanup** — FAILED outbox events accumulate indefinitely. Need a cleanup job to archive/delete old PUBLISHED and FAILED events, or the `findPendingEvents` scan cost grows even with the correct index.

5. **Template rendering is naive string replacement** — `{{var}}` replacement with `String.replace()` has O(n×m) complexity (n=template length, m=variable count). For large HTML templates with many variables, consider a proper template engine (Mustache, Freemarker). Also: no escaping = XSS if HTML template variables come from user input.

6. **User email resolution is hardcoded** — `resolveUserEmail()` returns `user-{uuid}@example.com`. This is a production blocker. Before any real traffic, this must be replaced with a real `UserService` gRPC or REST call, with caching.

**What a senior interviewer will challenge:**

- "If Kafka is down during outbox relay, how long can you sustain and what happens to `expiresAt` notifications that expire while queued?"
- "Your outbox relay runs every 200ms — how do you prevent multiple app instances from relaying the same event?"
- "If SendGrid returns a transient 429, your DLT strategy routes to partition 0. How does your ops team drain the DLT?"
- "The `@Transactional` on the Kafka consumer — what happens if the DB is down when a message arrives? Does Kafka commit the offset?"
