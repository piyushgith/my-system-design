# 06 — Event Flow: Social Media Feed System

## Objective

Map all asynchronous event flows through the system — from tweet creation to feed delivery, from follow action to timeline backfill, and from engagement events to notifications. Define Kafka topic design, consumer groups, event schemas, and the precise sequence of operations for each critical path.

---

## Event Architecture Overview

The system uses Kafka as the central nervous system for all asynchronous communication. Every significant state change produces an event. Downstream systems react to events independently without coupling to the producer's business logic.

```mermaid
graph LR
    subgraph Producers
        TweetSvc[Tweet Service]
        FollowSvc[Follow Service]
        LikeSvc[Like Service]
        UserSvc[User Service]
        ModerationSvc[Moderation Service]
    end

    subgraph Kafka Topics
        T1[tweet.events]
        T2[follow.events]
        T3[engagement.events]
        T4[user.events]
        T5[moderation.events]
        T6[notification.commands]
        T7[fanout.tasks]
        T8[search.index.commands]
        T9[analytics.events]
    end

    subgraph Consumers
        FanoutWorker[Fanout Workers]
        NotifSvc[Notification Service]
        SearchIdx[Search Indexer]
        TrendSvc[Trending Service]
        AnalyticsSvc[Analytics Service]
        ModerationConsumer[Moderation Pipeline]
    end

    TweetSvc --> T1
    FollowSvc --> T2
    LikeSvc --> T3
    UserSvc --> T4
    ModerationSvc --> T5

    T1 --> FanoutWorker
    T1 --> SearchIdx
    T1 --> TrendSvc
    T1 --> ModerationConsumer
    T1 --> AnalyticsSvc

    T2 --> FanoutWorker
    T2 --> NotifSvc
    T2 --> AnalyticsSvc

    T3 --> NotifSvc
    T3 --> AnalyticsSvc

    T4 --> FanoutWorker
    T4 --> SearchIdx

    T5 --> TweetSvc
    T5 --> FanoutWorker

    FanoutWorker --> T7
    T7 --> T6
    T6 --> NotifSvc
```

---

## Critical Event Flow: Tweet Creation to Feed Delivery

```mermaid
sequenceDiagram
    participant User
    participant TweetSvc as Tweet Service
    participant PG as PostgreSQL
    participant OutboxK as Outbox Table
    participant K as Kafka (tweet.events)
    participant FanoutSvc as Fanout Service
    participant FollowSvc as Follow Graph Service
    participant Redis as Redis Timeline
    participant Cassandra as Cassandra Timeline
    participant NotifSvc as Notification Service

    User->>TweetSvc: POST /tweets
    TweetSvc->>PG: INSERT tweet (+ insert outbox record) [single transaction]
    TweetSvc-->>User: 201 Created (fanout is async)

    Note over OutboxK,K: Outbox Relay Pattern
    OutboxK->>K: relay tweet.created event

    K->>FanoutSvc: tweet.created {tweet_id, author_id, celebrity_flag}

    alt Regular User (< 1M followers)
        FanoutSvc->>FollowSvc: GetFollowers(author_id) [paginated, 1000/batch]
        loop For each batch of 1000 followers
            FanoutSvc->>Redis: ZADD timeline:{follower_id} score tweet_id [batch pipeline]
            FanoutSvc->>Cassandra: INSERT home_timelines (batch write)
        end
    else Celebrity User (≥ 1M followers)
        FanoutSvc->>Redis: ZADD celebrity_timeline:{author_id} score tweet_id
        FanoutSvc->>Cassandra: INSERT celebrity_timelines
        Note over FanoutSvc: No per-follower write
    end

    FanoutSvc->>NotifSvc: Publish mention/hashtag notifications if applicable
    K->>NotifSvc: tweet.created for @mention notifications
```

**Key Timing Characteristics**:
- Tweet creation to write acknowledgment: < 100ms
- Regular user fanout completion (200 followers): < 500ms
- Celebrity tweet visible to all followers: immediately (pulled on read)
- 10M-follower regular user fanout: ~30 seconds (parallelized across 100 workers)

---

## Event: User Follows Another User

```mermaid
sequenceDiagram
    participant Follower
    participant FollowSvc as Follow Service
    participant PG as PostgreSQL
    participant K as Kafka (follow.events)
    participant FanoutSvc as Fanout Service
    participant TweetStore as Tweet Store (Cassandra)
    participant Redis as Redis Timeline
    participant NotifSvc as Notification Service

    Follower->>FollowSvc: POST /users/{id}/follows
    FollowSvc->>PG: INSERT follows + UPDATE follower_count/following_count [transaction]
    FollowSvc-->>Follower: 201 Created

    FollowSvc->>K: follow.created {follower_id, followee_id}

    K->>FanoutSvc: follow.created
    Note over FanoutSvc: Retroactive backfill
    FanoutSvc->>TweetStore: Fetch last N tweets from followee (N=100)
    FanoutSvc->>Redis: ZADD timeline:{follower_id} for each fetched tweet
    FanoutSvc->>Cassandra: INSERT into home_timelines

    K->>NotifSvc: follow.created
    NotifSvc->>NotifStore: INSERT notification for followee
    NotifSvc->>WebSocket: Push real-time notification to followee
```

**Retroactive Backfill Logic**:
When User A follows User B, User A's feed should immediately show User B's recent tweets. The Fanout Service fetches the last 100 tweets from User B (or the last week, whichever is smaller) and inserts them into User A's timeline. This is the "follow backfill" problem.

Backfill size is capped at 100 tweets to prevent a user following a prolific account from overwhelming their feed.

---

## Event: Tweet Deletion

```mermaid
sequenceDiagram
    participant User
    participant TweetSvc as Tweet Service
    participant PG as PostgreSQL
    participant K as Kafka (tweet.events)
    participant FanoutSvc as Fanout Service
    participant Redis as Redis Timeline
    participant Cassandra as Cassandra Timeline
    participant SearchIdx as Search Indexer

    User->>TweetSvc: DELETE /tweets/{tweet_id}
    TweetSvc->>PG: UPDATE tweets SET is_deleted=TRUE, deleted_at=NOW()
    TweetSvc-->>User: 200 OK

    TweetSvc->>K: tweet.deleted {tweet_id, author_id, follower_count}

    alt Celebrity (10M followers) — brute force cleanup too expensive
        Note over FanoutSvc: Mark tweet as deleted in tweet store only
        Note over FanoutSvc: Feed Read Service filters is_deleted on hydration
    else Regular User — cleanup timeline entries
        K->>FanoutSvc: tweet.deleted
        FanoutSvc->>FollowSvc: GetFollowers(author_id)
        loop For each follower batch
            FanoutSvc->>Redis: ZREM timeline:{follower_id} tweet_id
            FanoutSvc->>Cassandra: DELETE FROM home_timelines WHERE tweet_id=?
        end
    end

    K->>SearchIdx: tweet.deleted
    SearchIdx->>ES: DELETE document {tweet_id}
```

**The Celebrity Delete Problem**: Removing a celebrity tweet from 10M follower timelines would take hours and consume enormous resources. The pragmatic solution: mark the tweet deleted in the source of truth, and filter it out at read time. The Feed Read Service always checks `is_deleted` during hydration. The stale ID in Redis is eventually cleaned up by a background TTL expiry.

---

## Kafka Topic Design

### Topic Catalog

| Topic | Partitions | Retention | Consumers | Notes |
|---|---|---|---|---|
| `tweet.events` | 512 | 7 days | Fanout, Search, Trending, Moderation | Highest throughput |
| `follow.events` | 128 | 7 days | Fanout, Notification | Lower volume |
| `engagement.events` | 256 | 3 days | Notification, Analytics | Likes, retweets |
| `user.events` | 64 | 30 days | All services | Account lifecycle |
| `moderation.events` | 64 | 7 days | Tweet Service, Fanout | Moderation verdicts |
| `fanout.tasks` | 1024 | 24 hours | Fanout Workers | Internal use only |
| `notification.commands` | 256 | 24 hours | Notification Service | Push commands |
| `analytics.raw` | 1024 | 30 days | Analytics | High volume raw events |

### Partition Strategy

**`tweet.events`** — partitioned by `author_id % num_partitions`. All tweets from the same author land on the same partition, preserving ordering for the same user's tweet stream. This is critical for retweet ordering.

**`fanout.tasks`** — partitioned by `target_user_id % num_partitions`. This ensures all fanout writes for the same user timeline go to the same consumer, preventing concurrent write races on the same timeline.

**`analytics.raw`** — partitioned by `event_type`, allowing different analytics consumers to subscribe to only their relevant event types.

### Event Schemas (Avro)

All events use Avro with the Confluent Schema Registry for schema evolution.

**`tweet.created` event**:
```
{
  "tweet_id": "long",
  "author_id": "string",
  "content": "string",
  "hashtags": ["string"],
  "mentions": ["string"],
  "tweet_type": "string",
  "visibility": "string",
  "is_celebrity_tweet": "boolean",
  "author_follower_count": "long",
  "created_at": "long"  // epoch millis
}
```

**`follow.created` event**:
```
{
  "follower_id": "string",
  "followee_id": "string",
  "is_followee_celebrity": "boolean",
  "followee_follower_count": "long",
  "created_at": "long"
}
```

---

## Consumer Group Design

### Fanout Consumer Group

```
Group ID: fanout-workers
Topic: tweet.events
Instances: 100 pods (auto-scaled based on consumer lag)
Processing: Parallel per partition, sequential within partition
Error handling: Retry 3× with exponential backoff → Dead Letter Topic
```

Each fanout worker processes tweet.events and publishes individual per-follower fanout tasks to `fanout.tasks`. The `fanout.tasks` consumers then do the actual Redis/Cassandra writes.

This two-stage approach prevents a single slow Cassandra write from blocking the consumption of new tweet events.

### Moderation Pipeline Consumer

```
Group ID: moderation-pipeline
Topic: tweet.events
Processing: Async — run ML classifier, update tweet status
SLA: < 5 seconds for automated moderation decision
```

---

## The Outbox Pattern

Why it matters: Without the outbox pattern, there is a race condition:

1. Tweet Service inserts tweet to PostgreSQL — SUCCESS
2. Tweet Service publishes to Kafka — NETWORK FAILURE
3. Result: Tweet is in DB but no fanout ever happens

With the outbox pattern:
1. Tweet Service atomically inserts tweet + outbox record to PostgreSQL — one transaction
2. Outbox relay daemon reads unprocessed outbox records and publishes to Kafka
3. On successful Kafka publish, mark outbox record as processed

This guarantees at-least-once delivery. Consumers must be idempotent (handle duplicate events).

```mermaid
sequenceDiagram
    participant TweetSvc
    participant PG
    participant OutboxRelay
    participant Kafka

    TweetSvc->>PG: BEGIN; INSERT tweet; INSERT outbox_events; COMMIT;
    OutboxRelay->>PG: SELECT * FROM outbox_events WHERE processed=FALSE ORDER BY id LIMIT 100
    OutboxRelay->>Kafka: Publish events
    Kafka-->>OutboxRelay: ACK
    OutboxRelay->>PG: UPDATE outbox_events SET processed=TRUE
```

---

## Real-Time Feed Updates

### Long Polling vs WebSockets vs SSE

| Approach | Use Case | Latency | Server Cost |
|---|---|---|---|
| Polling (10s interval) | Legacy mobile apps | 0–10 sec | High (wasted requests) |
| Long Polling | New tweet notification | 0–30 sec | Medium |
| Server-Sent Events (SSE) | Feed updates, one-directional | ~1 sec | Low (stateful connections) |
| WebSockets | Chat, bidirectional | < 500ms | Medium (bidirectional) |

**Decision**: Use SSE for feed update notifications. When a user is actively viewing their feed, the client holds an SSE connection. When a new tweet arrives in their timeline, the server pushes a `new_tweet_available` signal. The client then re-fetches from the feed API.

The SSE signal does NOT carry tweet content — it just says "fetch new content." This keeps the SSE connection lightweight and allows the full feed response to be cached at the CDN layer.

WebSockets are used for DMs (bidirectional) and live event features, not for feed delivery.

---

## Trending Topics Event Flow

```mermaid
sequenceDiagram
    participant TweetSvc
    participant Kafka as Kafka (tweet.events)
    participant TrendWorker as Trending Worker
    participant Redis as Redis (Trending Windows)
    participant TrendAPI as Trending API

    TweetSvc->>Kafka: tweet.created {hashtags: ["#WorldCup"]}
    Kafka->>TrendWorker: tweet.created
    TrendWorker->>Redis: ZINCRBY trending:15min:{window_id} 1 "#WorldCup"
    TrendWorker->>Redis: ZINCRBY trending:1hr:{window_id} 1 "#WorldCup"

    Note over TrendWorker: Every 30 seconds
    TrendWorker->>Redis: ZRANGEBYSCORE trending:15min:{window_id} -inf +inf WITHSCORES
    TrendWorker->>Redis: Compare with baseline (24h average)
    TrendWorker->>Redis: SET trending:global ZSET with top 50 trending

    TrendAPI->>Redis: ZRANGE trending:global 0 49 WITHSCORES
    TrendAPI-->>Client: Top 50 trending topics
```

**Trending Algorithm**:
1. Count hashtag usage in rolling 15-minute windows stored in Redis sorted sets
2. Compare current window count against 7-day average usage (stored in a separate counter)
3. A hashtag is "trending" if `current_count > baseline × multiplier (e.g., 2.5×)`
4. Rank by `(current_count - baseline) / sqrt(baseline)` — normalizes for large vs small accounts

---

## Interview-Level Discussion Points

1. **Why at-least-once vs exactly-once delivery?**: Kafka supports exactly-once semantics (EOS) but it adds complexity and reduces throughput. At-least-once is sufficient if consumers are idempotent. The fanout consumer uses `(user_id, tweet_id)` as a natural idempotency key — a duplicate fanout write is a no-op (ZADD on an existing member just updates the score).

2. **Consumer lag as an SLA metric**: The time between a tweet being created and it appearing in all follower feeds is determined by consumer lag on the `tweet.events` topic. If consumer lag grows above 30 seconds, auto-scale fanout workers. This is a key operational metric.

3. **The "thundering herd" on celebrity tweet**: When a celebrity with 10M followers tweets, the celebrity_timeline cache update is instant. But the millions of concurrent feed reads that happen in the next 30 seconds all trigger the same celebrity timeline lookup. Mitigate with Redis pipelining and read-through cache warming.

4. **Event ordering guarantees**: Kafka preserves order within a partition. All tweets from the same author land on the same partition (partitioned by author_id). This means their relative order is preserved. However, tweets from different partitions may arrive at the fanout consumer out of order. The timeline's sorted set uses tweet_id (Snowflake) as the score, which preserves global time ordering regardless of event delivery order.

5. **Schema evolution with Avro**: Adding a new field to an event schema with a default value is backward compatible. Removing a field is a breaking change that requires a new schema version. The Confluent Schema Registry enforces compatibility rules and prevents accidental breaking changes.
