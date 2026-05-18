# 03 — DDD Bounded Contexts: Social Media Feed System

## Objective

Define the Domain-Driven Design bounded contexts, their responsibilities, internal models, and inter-context communication contracts. Bounded contexts establish the deployment and ownership boundaries that drive service decomposition.

---

## Bounded Context Map

```mermaid
graph TD
    subgraph Identity Context
        UserSvc[User Service]
        AuthSvc[Auth Service]
    end

    subgraph Content Context
        TweetSvc[Tweet Service]
        MediaSvc[Media Service]
        ModerationSvc[Moderation Service]
    end

    subgraph Social Graph Context
        FollowSvc[Follow Graph Service]
    end

    subgraph Feed Context
        FanoutSvc[Fanout Service]
        FeedReadSvc[Feed Read Service]
        RankingSvc[Feed Ranking Service]
    end

    subgraph Discovery Context
        TrendingSvc[Trending Service]
        SearchSvc[Search Service]
        RecommendSvc[Recommendation Service]
    end

    subgraph Engagement Context
        LikeSvc[Like Service]
        RetweetSvc[Retweet Service]
        NotifSvc[Notification Service]
    end

    subgraph Analytics Context
        AnalyticsSvc[Analytics Service]
        MLSvc[ML Feature Store]
    end

    Content Context -->|tweet_created event| Feed Context
    Content Context -->|tweet_created event| Discovery Context
    Content Context -->|tweet_created event| Engagement Context
    Social Graph Context -->|user_followed event| Feed Context
    Social Graph Context -->|user_followed event| Engagement Context
    Engagement Context -->|like_created event| Analytics Context
    Feed Context -->|feed_served event| Analytics Context
    Identity Context -->|user_suspended event| Content Context
    Identity Context -->|user_suspended event| Feed Context
```

---

## Bounded Context Definitions

### 1. Identity Context

**Responsibility**: User registration, authentication, profile management, account lifecycle.

**Core Model**:
- `User` aggregate: profile data, account status, verification status
- `AuthToken`: JWT issuance and validation

**Ubiquitous Language**: "account", "profile", "handle", "login", "signup", "suspension"

**Owns**: User table, auth tokens, password hashes, OAuth provider links

**Does NOT own**: Follow relationships, tweet content, feed data

**Communication**:
- Publishes `user.created`, `user.suspended`, `user.deleted`
- Consumed by: all other contexts for permission checks and user hydration

**Key Decision**: Auth token validation happens at the API Gateway, not in each service. Services receive a validated `user_id` in headers. This prevents every service from needing to call the Identity Context on every request.

---

### 2. Content Context

**Responsibility**: Tweet creation, media attachment, content moderation, tweet lifecycle.

**Core Model**:
- `Tweet` aggregate: content, metadata, engagement counters, lifecycle state
- `Media` entity: uploaded media metadata and CDN URLs
- `ModerationResult`: verdict from moderation pipeline

**Ubiquitous Language**: "tweet", "post", "reply", "retweet", "quote tweet", "thread", "mention", "hashtag"

**Owns**: Tweet store (Cassandra + PostgreSQL), media metadata, moderation decisions

**Does NOT own**: Who sees the tweet (that's Feed Context), engagement counts are shared with Engagement Context

**Consistency Boundary**: A tweet is either fully created or not — atomic within this context. The downstream fanout is eventually consistent.

**Key Decision**: Content moderation runs asynchronously. A tweet is initially published in a `PENDING_MODERATION` state, becomes `PUBLISHED` within seconds after an automated check, and may be `WITHHELD` if flagged. This keeps the write path fast.

---

### 3. Social Graph Context

**Responsibility**: Managing follow/unfollow relationships, blocking, muting, follow graph traversal.

**Core Model**:
- `FollowEdge`: directed relationship between two users
- `BlockEdge`: bidirectional block (suppresses all interaction)
- `MuteEdge`: follower still follows but suppresses posts from muted account

**Ubiquitous Language**: "follow", "unfollow", "block", "mute", "follower", "following", "mutual"

**Owns**: Follow graph data, block/mute lists

**Does NOT own**: User profiles (borrows `user_id` as foreign key), feed construction

**Storage Choice**: PostgreSQL for the follow table with an adjacency list model. For graph traversal at FAANG scale, a dedicated graph database (Neo4j, JanusGraph) or a custom graph service could be considered. At 500M users with avg 200 follows, the follow table has ~100B rows — this is where PostgreSQL reaches its limits and a custom distributed follow store becomes necessary.

**Critical Operation**: `get_followers(user_id)` — called by Fanout Service on every non-celebrity tweet. This must be a paginated, bulk-read operation. Return followers in batches of 1,000 to allow parallel fanout processing.

---

### 4. Feed Context

**Responsibility**: Building, storing, and serving personalized home timelines. The heart of the system.

**Sub-contexts**:
- **Fanout Sub-context**: Write path — distributing tweet IDs to follower timelines
- **Feed Read Sub-context**: Read path — assembling and serving the timeline
- **Ranking Sub-context**: Scoring and ordering feed entries

**Core Model**:
- `Timeline`: ordered collection of `TimelineEntry` objects per user
- `TimelineEntry`: tweet_id + score + source
- `FeedPage`: hydrated, paginated response

**Ubiquitous Language**: "fanout", "timeline", "feed", "precomputed feed", "push", "pull", "ranking score", "cursor"

**Owns**: Timeline data in Redis (hot) and Cassandra (warm/cold), ranking scores

**Does NOT own**: Tweet content (borrows from Content Context on hydration), follow graph (borrows from Social Graph Context)

**The Celebrity Problem**: This context is where the hybrid push-pull decision lives. The Fanout Sub-context is responsible for detecting celebrity status and routing tweets to either the push path or the celebrity timeline path.

---

### 5. Discovery Context

**Responsibility**: Trending topics, search, recommendations, explore tab.

**Core Model**:
- `TrendingTopic`: hashtag + usage counts across time windows
- `SearchResult`: ranked list of tweets/users matching a query
- `Recommendation`: suggested tweet or user for a feed slot

**Ubiquitous Language**: "trending", "explore", "search", "recommendation", "viral"

**Owns**: Trending state in Redis, search index in Elasticsearch, recommendation model outputs

**Does NOT own**: Core tweet content (indexes it, doesn't own it)

**Key Design**: Trending detection uses sliding window counters in Redis. For each hashtag, maintain a sorted set keyed by 15-minute windows. A hashtag is "trending" if its window count exceeds a dynamic threshold relative to its baseline usage.

---

### 6. Engagement Context

**Responsibility**: Likes, retweets, replies (as engagement actions), notifications.

**Core Model**:
- `Like`: user-tweet engagement record
- `NotificationQueue`: pending notifications per user
- `EngagementCounter`: atomic like/retweet count updates

**Ubiquitous Language**: "like", "unlike", "engagement", "notification", "mention", "alert"

**Owns**: Like table, notification store, engagement events

**Does NOT own**: Tweet engagement counts (those live in Content Context; this context updates them via events)

**Hot Path Consideration**: A popular tweet going viral will have thousands of simultaneous likes. The Like Service must handle this gracefully: use Redis atomic INCR for counter updates with async DB persistence, not a database write per like.

---

### 7. Analytics Context

**Responsibility**: Event ingestion, metric computation, A/B testing support, ML feature serving.

**Core Model**:
- `FeedEvent`: impression, click, like, dwell time
- `FeatureVector`: per-user ML features for ranking
- `ExperimentAssignment`: which A/B variant a user is in

**Ubiquitous Language**: "impression", "click-through rate", "engagement rate", "feature store", "A/B test", "variant"

**Owns**: Event warehouse (Kafka → data lake), ML feature store, experiment assignments

**Does NOT own**: Real-time serving of content (consumes events but does not serve the hot path)

---

## Context Relationships and Integration Patterns

| Upstream Context | Downstream Context | Pattern | Consistency |
|---|---|---|---|
| Content → Feed | `tweet.created` event | Async Kafka | Eventual |
| Content → Discovery | `tweet.created` event | Async Kafka | Eventual |
| Social Graph → Feed | `user.followed` event | Async Kafka | Eventual |
| Identity → All | `user.suspended` event | Sync gRPC + Kafka | Strong (for suspension) |
| Feed → Analytics | `feed.served` event | Async Kafka | Eventual |
| Discovery → Feed | Recommendation injection | Sync gRPC (read path) | N/A |

---

## Anti-Corruption Layer Patterns

When the Feed Context calls the Social Graph Context to retrieve followers, it uses a client adapter that translates the Social Graph's domain model (`FollowEdge`) into the Feed Context's model (`FollowerBatch`). This prevents the Feed Context from having a dependency on the Social Graph's internal schema.

Similarly, the Feed Read Service does not call the Content Context's Tweet aggregate directly — it queries a read-optimized tweet cache that projects only the fields needed for feed rendering.

---

## Aggregate Design Principles

### Tweet Aggregate Boundary
- The `Tweet` aggregate includes: content, media refs, hashtags, mentions
- Engagement counters (likes, retweets) are **outside** the aggregate boundary — they are handled by the Engagement Context with eventual consistency to the tweet's denormalized counts
- This prevents the Tweet aggregate from becoming a contention hotspot when a viral tweet gets thousands of likes per second

### Timeline Aggregate
- A user's `Timeline` is the aggregate root in the Feed Context
- Operations: `append(tweet_id, score)`, `truncate(max_length)`, `get_page(cursor, size)`
- The timeline does not contain tweet content — it is a pure ordering structure

---

## Interview-Level Discussion Points

1. **Context boundaries as team boundaries**: In a real org, each bounded context would be owned by a separate team (Identity team, Feed team, Content team). API contracts between contexts must be versioned and backward-compatible.

2. **The follow graph at 500M users**: A `follow` table with 100B rows (500M users × avg 200 follows) cannot live in a single PostgreSQL instance. Options: (a) shard by `follower_id`, (b) move to a dedicated distributed key-value store, (c) use a graph database. Twitter's Flock service is a custom in-memory social graph service.

3. **Why separate Fanout and Feed Read services?**: Their scaling profiles are opposite. Fanout is CPU-bound and write-heavy (burst on tweet creation). Feed Read is read-heavy and latency-sensitive. Coupling them forces you to over-provision one to satisfy the other.

4. **Moderation as a context, not a feature**: Content moderation has its own complex domain model (classifiers, human review queues, appeals). Treating it as a separate bounded context with its own team allows it to evolve independently without coupling to the tweet write path.
