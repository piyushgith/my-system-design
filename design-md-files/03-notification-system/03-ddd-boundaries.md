# 03 — DDD Bounded Contexts: Notification System

---

## Objective

Define the bounded contexts of the Notification System using Domain-Driven Design principles. Establish clear ownership, language, and integration contracts between contexts. Identify context mapping strategies for cross-boundary communication.

---

## Bounded Context Overview

```mermaid
graph TB
    subgraph Notification Core Context
        NC_API[Notification Intake]
        NC_Fanout[Fanout & Routing]
        NC_Sched[Scheduler]
    end

    subgraph Channel Delivery Context
        CD_Email[Email Delivery]
        CD_SMS[SMS Delivery]
        CD_Push[Push Delivery]
        CD_InApp[In-App Delivery]
    end

    subgraph Template Context
        TM_Store[Template Store]
        TM_Render[Template Renderer]
    end

    subgraph Preference Context
        PR_Store[User Preferences]
        PR_Eval[Preference Evaluator]
        PR_Unsub[Unsubscribe Handler]
    end

    subgraph Delivery Intelligence Context
        DI_Log[Delivery Log]
        DI_Analytics[Analytics Aggregator]
        DI_Retry[Retry Orchestrator]
    end

    subgraph Campaign Context
        CA_Build[Campaign Builder]
        CA_Segment[Segmentation]
        CA_Launch[Campaign Launcher]
    end

    NC_API -->|notification.requested| NC_Fanout
    NC_Fanout -->|email/sms/push/inapp.dispatch| Channel Delivery Context
    NC_Fanout -->|consults| Preference Context
    Channel Delivery Context -->|delivery.result| Delivery Intelligence Context
    DI_Retry -->|requeue| Channel Delivery Context
    Channel Delivery Context -->|consults| Template Context
    CA_Launch -->|batch.requested| NC_API
    CA_Segment --> CA_Launch
```

---

## Bounded Context 1: Notification Core

### Ubiquitous Language

| Term | Meaning in this context |
|------|------------------------|
| **Notification Request** | An intent to communicate with a user, received from a producer |
| **Fanout** | The act of routing a single notification intent to one or more channel-specific delivery jobs |
| **Priority** | Business urgency level that governs queue placement and quiet-hours bypass |
| **Category** | Semantic classification (Transactional, Marketing) that governs preference filtering |
| **Idempotency Key** | Producer-assigned deduplication token |

### Responsibilities
- Accept incoming notification requests from all producers
- Validate schema, category, and priority
- Assign notification_id and persist to Outbox
- Route to Fanout for channel-specific dispatch determination
- Manage scheduled notifications (future delivery)
- Publish `notification.requested` events

### What This Context Does NOT Own
- Which channels a user prefers (Preference Context)
- How a message is rendered (Template Context)
- Whether a delivery succeeded (Delivery Intelligence Context)
- How a user segment is built (Campaign Context)

### Integration Points
- **Upstream**: All internal producer services (Order, Auth, Marketing)
- **Downstream**: Kafka `notification.requested` topic consumed by Fanout

---

## Bounded Context 2: Channel Delivery

### Ubiquitous Language

| Term | Meaning in this context |
|------|------------------------|
| **Delivery Job** | A unit of work to deliver a specific notification via a specific channel to a specific address |
| **Provider** | An external service used for delivery (SendGrid, Twilio, FCM) |
| **Dispatch** | The act of calling a provider API with rendered message content |
| **Provider Reference** | The external ID returned by provider on success (e.g., Twilio SID) |
| **Hard Bounce** | A permanent delivery failure (invalid address, account closed) |
| **Soft Bounce** | A temporary failure (provider throttle, timeout) |

### Responsibilities
- Consume channel-specific dispatch topics (`email.dispatch`, `sms.dispatch`, etc.)
- Perform template rendering (via Template Context)
- Call external provider APIs
- Publish delivery results
- Per-channel rate limiting against provider quotas
- Provider health tracking (circuit breaker per provider)

### What This Context Does NOT Own
- Retry scheduling (Delivery Intelligence Context)
- Preference filtering (Preference Context)
- User segmentation (Campaign Context)

### Channel Isolation Principle
Each channel (Email, SMS, Push, In-App) is independently deployable. They share the Channel Delivery bounded context conceptually but are isolated at the service level. Email dispatch load does not block SMS dispatch.

### Integration Points
- **Upstream**: Kafka `*.dispatch` topics
- **Downstream**: External providers; Kafka `delivery.result` topic

---

## Bounded Context 3: Template Management

### Ubiquitous Language

| Term | Meaning in this context |
|------|------------------------|
| **Template** | A reusable message structure with variable placeholders |
| **Version** | An immutable snapshot of a template at a point in time |
| **Variable Schema** | The contract defining required/optional variables for a template |
| **Locale** | Language/region variant of a template |
| **Rendering** | The act of substituting variables into a template to produce final message content |

### Responsibilities
- CRUD for templates (admin interface)
- Version management (create new version, deprecate old)
- Template validation (variable schema enforcement)
- Render API: given template_id + version + variables → rendered content
- Cache management for high-frequency reads

### What This Context Does NOT Own
- Who receives a notification (Notification Core)
- Delivery mechanics (Channel Delivery)
- User preferences (Preference Context)

### Integration Points
- **Upstream**: Admin operators via management API
- **Downstream (synchronous)**: Channel Dispatchers call Render API

---

## Bounded Context 4: Preference Management

### Ubiquitous Language

| Term | Meaning in this context |
|------|------------------------|
| **Preference** | A user's stated desire to receive or not receive notifications on a channel/category combination |
| **Opted In** | User allows delivery on this channel/category |
| **Opted Out** | User blocks delivery on this channel/category |
| **Quiet Hours** | Time window during which non-critical notifications are suppressed |
| **Hard Unsubscribe** | Provider-level unsubscribe that cannot be reversed by user alone |
| **Soft Unsubscribe** | User-initiated preference change |

### Responsibilities
- Store and serve user notification preferences
- Evaluate routing decisions (should this notification go to this channel for this user?)
- Handle unsubscribe requests (one-click unsubscribe links)
- Process hard bounce events from Channel Delivery to update preferences
- Enforce GDPR/CAN-SPAM unsubscribe handling

### What This Context Does NOT Own
- Sending the notification (Channel Delivery)
- Template content (Template Context)

### Critical Invariant
A Transactional + Critical notification MUST be delivered regardless of opted_out status, except in legally mandated cases (e.g., user requests complete deletion under GDPR).

### Integration Points
- **Upstream**: Users via preference API; Channel Delivery context via `user.hard_bounced` events
- **Downstream (synchronous gRPC)**: Fanout Service queries preferences on every dispatch decision

---

## Bounded Context 5: Delivery Intelligence

### Ubiquitous Language

| Term | Meaning in this context |
|------|------------------------|
| **Delivery Event** | An immutable record of one delivery attempt outcome |
| **DLQ** | Dead Letter Queue — messages that failed all retry attempts |
| **Retry Policy** | Rules governing when and how many times a failed delivery is retried |
| **Delivery Rate** | Percentage of dispatched notifications that resulted in confirmed delivery |
| **Open Rate** | Percentage of delivered emails/pushes that the user opened (where trackable) |

### Responsibilities
- Consume all `delivery.result` events
- Write delivery audit log (append-only, ClickHouse)
- Orchestrate retries: compute next_retry_at, publish to retry topic
- Move exhausted-retry messages to DLQ
- Aggregate analytics: delivery rates, channel performance, bounce rates
- Expose reporting APIs for operations dashboards

### What This Context Does NOT Own
- The delivery mechanics themselves (Channel Delivery)
- The original notification content (Notification Core)

### Integration Points
- **Upstream**: Kafka `delivery.result` topic
- **Downstream**: Kafka retry topics; DLQ storage; analytics dashboard

---

## Bounded Context 6: Campaign Management

### Ubiquitous Language

| Term | Meaning in this context |
|------|------------------------|
| **Campaign** | A targeted notification send to a user segment |
| **Segment** | A cohort of users defined by behavioral/demographic criteria |
| **Audience** | The resolved list of users for a campaign run |
| **Throttle Rate** | Maximum notifications per second for a campaign |
| **A/B Test** | Running multiple template variants to measure engagement |

### Responsibilities
- Campaign creation and scheduling
- Segment definition and user resolution
- Campaign launch: stream user IDs to Notification Core for individual notification creation
- Throttle control: prevent campaigns from overwhelming Kafka or provider rate limits
- A/B test variant assignment
- Campaign performance reporting

### What This Context Does NOT Own
- Individual notification delivery (Channel Delivery)
- User preferences (Preference Context)
- Template content (Template Context)

### Integration Points
- **Upstream**: Marketing operators via campaign management UI
- **Downstream**: Calls Notification Core API to enqueue individual notifications

---

## Context Mapping

### Relationship Types

| Context A | Relationship | Context B | Notes |
|-----------|-------------|-----------|-------|
| Notification Core | **Customer / Supplier** | Channel Delivery | Core defines dispatch contract; Channel Delivery implements it |
| Channel Delivery | **Customer / Supplier** | Template Management | Dispatchers consume Render API |
| Notification Core | **Customer / Supplier** | Preference Management | Fanout queries Preference before dispatch |
| Channel Delivery | **Published Language** | Delivery Intelligence | `delivery.result` is a stable event contract |
| Campaign | **Partnership** | Notification Core | Campaign enqueues via Core's intake API |
| Delivery Intelligence | **Conformist** | Channel Delivery | Consumes Channel Delivery events as-is |

### Anti-Corruption Layer

The Preference Management context must apply an Anti-Corruption Layer when receiving hard bounce events from external providers. Provider-specific codes (Twilio: "21211", SendGrid: "550 5.1.1") must be translated into the domain's `HardBounce` concept before updating user preferences. External provider language must not leak into the domain model.

---

## Shared Kernel

The following types are shared across contexts but owned by a shared schema/library:

| Shared Type | Used By |
|-------------|---------|
| `notification_id` (UUID format) | All contexts |
| `Channel` enum (EMAIL, SMS, PUSH, IN_APP) | Notification Core, Channel Delivery, Preference |
| `Category` enum | Notification Core, Preference |
| `Priority` enum | Notification Core, Channel Delivery |
| `DeliveryStatus` enum | Channel Delivery, Delivery Intelligence |

These are published in a shared library (`notification-contracts`). Changes require coordination across teams.

---

## Team Ownership Mapping (at scale)

| Bounded Context | Team | Deployment Unit |
|----------------|------|-----------------|
| Notification Core | Platform Core | notification-api, fanout-service |
| Email Delivery | Messaging Team | email-dispatcher |
| SMS Delivery | Messaging Team | sms-dispatcher |
| Push Delivery | Messaging Team | push-dispatcher |
| In-App Delivery | Frontend Platform | inapp-dispatcher |
| Template Management | Platform Core | template-service |
| Preference Management | User Platform | preference-service |
| Delivery Intelligence | Data Platform | delivery-log-service, analytics |
| Campaign Management | Growth Team | campaign-service |

---

## Interview Discussion Points

- What happens to bounded context boundaries when you add WhatsApp as a new channel?
- How do you prevent the Preference context from becoming a God service as business rules grow?
- Why is the Anti-Corruption Layer important when a provider changes its error codes in an API update?
- How does the Campaign context avoid becoming tightly coupled to the Preference context when applying audience filters?
- At what team size does the shared-kernel approach for enums become a bottleneck?
