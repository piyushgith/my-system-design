# 00 — Requirements Analysis: Social Media Feed System (Twitter/X)

## Objective

Define the functional and non-functional requirements for a production-grade social media feed system capable of serving personalized, ranked timelines to hundreds of millions of users globally. This document establishes the scope, constraints, assumptions, and capacity planning foundation upon which all architectural decisions are made.

---

## Functional Requirements

### Core Features (MVP)

| Feature | Description |
|---|---|
| Post/Tweet creation | Users can create short-form posts (text, images, links) |
| Follow/Unfollow | Users can follow/unfollow other users |
| Home timeline | Personalized feed of posts from followed accounts |
| User timeline | All posts by a specific user |
| Like / Retweet | Engagement actions on posts |
| Infinite scroll | Cursor-based pagination of feeds |
| Real-time updates | New posts surface without full page reload |
| Trending topics | Surface globally and locally trending hashtags |

### Extended Features (V2+)

| Feature | Description |
|---|---|
| Feed ranking | ML-based relevance scoring, not purely chronological |
| Notifications | Real-time alerts for likes, retweets, follows, mentions |
| Content moderation | Automated and human-in-the-loop content filtering |
| Search | Full-text search over tweets and users |
| Media handling | Image/video upload and CDN delivery |
| Lists | Curated sub-feeds from user-defined groupings |
| Mute/Block | Relationship graph suppression |
| Ads/Promoted content | Injection of sponsored tweets into the feed |

---

## Non-Functional Requirements

| Property | Target |
|---|---|
| Availability | 99.99% uptime (< 52 minutes downtime/year) |
| Feed read latency | p99 < 200ms globally |
| Feed write fanout latency | p99 < 5 seconds for non-celebrity tweets |
| Consistency | Eventual consistency acceptable for feed delivery |
| Durability | Zero tweet loss; Kafka + DB persistence |
| Scalability | Horizontal scaling to 500M+ users |
| Fault isolation | Celebrity tweet storm must not degrade regular feed reads |

---

## Assumptions

- A "celebrity" is any user with more than **1 million followers**. Hybrid fanout kicks in above this threshold.
- Users are globally distributed; CDN and regional caching is required.
- Feed ranking starts chronological at MVP; ML ranking introduced at V2.
- Tweets are immutable after a short edit window (e.g., 30 minutes), simplifying cache invalidation.
- Media (images, videos) is handled by a separate media service and delivered via CDN; this document focuses on the feed pipeline.
- Deleted tweets must be removed from all user timelines within 60 seconds.
- A user can follow at most 5,000 other accounts (same as Twitter's current policy).

---

## Constraints

- Max tweet length: 280 characters (text-only for MVP, media attachments as IDs)
- Follow graph size: up to 5,000 follows per user; up to 10M+ followers for celebrities
- Timeline depth: a user can scroll back up to 800 posts in their home timeline
- Rate limits: 300 tweets per 15-minute window per user (write); unlimited reads with backoff
- GDPR / data residency: EU users' data must remain in EU regions

---

## Scale Estimation

### User Base

```
Total registered users:     500M
Daily Active Users (DAU):   300M
Monthly Active Users (MAU): 400M
Average follows per user:   50
Celebrity accounts (>1M followers): ~50,000
Max celebrity followers:    10M (e.g., @elonmusk tier)
```

### Write Traffic

```
Tweets created per day:     500M tweets/day
Peak multiplier:            3x over average
Average tweets/second:      500M / 86,400 ≈ 5,800 tweets/sec
Peak tweets/second:         ~17,500 tweets/sec
Retweets (2x multiplier):   ~35,000 fanout-triggering events/sec at peak
```

### Read Traffic

```
Timeline reads per DAU:     ~10 per day = 3B reads/day
Reads per second (avg):     ~35,000 reads/sec
Reads per second (peak):    ~100,000 reads/sec
Cache hit target:           >95% of timeline reads served from cache
```

### Fanout Estimate

```
Average followers per user:         200 (write fanout to 200 timelines)
Celebrity tweet fanout:             10M (NOT precomputed — pulled on read)
Fanout events per second (avg):     5,800 × 200 = 1.16M fanout ops/sec
Fanout events per second (peak):    ~3.5M fanout ops/sec
Kafka throughput required:          ~3.5M messages/sec across partitions
```

### Storage Estimation

```
Tweet object size (metadata + text):   ~1KB
Tweets per day:                        500M
Daily tweet storage:                   500 GB/day
3-year hot storage (no media):         ~550 TB
Timeline cache per user (800 posts):   800 × 8 bytes (tweet_id) = 6.4KB
300M active users × 6.4KB:            ~1.9 TB Redis memory for timeline IDs
Full tweet object cache (top 300M):    ~300M × 1KB = 300 GB Redis
```

### Bandwidth

```
Average tweet payload (API response): ~2KB
100K reads/sec × 2KB:                 ~200 MB/sec outbound
CDN offload target:                   80% of media traffic
```

---

## Read/Write Patterns

| Pattern | Description |
|---|---|
| Read-heavy | 10:1 to 100:1 read-to-write ratio on feed queries |
| Write amplification | 1 tweet → up to 10M fanout writes (celebrity) |
| Hot data | Last 48 hours of timeline is accessed 90% of the time |
| Cold data | Historical tweets > 7 days old rarely accessed |
| Bursty writes | Live events (elections, sports) cause write spikes |
| Spatial locality | Users in the same region tend to follow celebrities together |

---

## Latency Expectations

| Operation | Target SLA |
|---|---|
| Home timeline load (first page) | p50 < 50ms, p99 < 200ms |
| Post a tweet | p99 < 500ms (acknowledgment); fanout async |
| Follow/unfollow | p99 < 300ms |
| Trending topics refresh | Eventually consistent, < 30 sec lag |
| Search | p99 < 500ms |
| Real-time push (new tweet notification) | < 2 seconds |

---

## Availability Targets

```
Home feed service:          99.99% (4 nines)
Tweet write service:        99.95% (3.5 nines)
Trending service:           99.9%  (3 nines) — degraded is acceptable
Notification service:       99.9%  (3 nines)
```

---

## Interview-Level Discussion Points

1. **Why eventual consistency is acceptable for feeds**: A user seeing a tweet 2 seconds late is not a correctness issue. However, a tweet deletion must propagate quickly for legal/safety reasons — this creates an asymmetry in consistency requirements.

2. **Back-of-envelope discipline**: The 1.16M fanout ops/sec figure is the single most important number — it drives the Kafka partition count, Redis cluster size, and number of fanout workers.

3. **Storage tiering**: Hot (< 48hr) timelines live in Redis. Warm (48hr–30 days) in Cassandra. Cold (30 days+) in object storage (S3). This dramatically reduces Redis memory cost.

4. **The 500M user vs 300M DAU gap**: Only active users need precomputed timelines. Lazy-precompute on login or first access for dormant users.

5. **Why 95%+ cache hit rate is critical**: With 100K reads/sec hitting the database directly, PostgreSQL would be obliterated. Cache is the first line of defense and must be designed before the database schema.
