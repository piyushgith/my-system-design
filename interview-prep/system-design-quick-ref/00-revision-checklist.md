# System Design Revision Checklist

Use 30 min before interview. Each line = one mental model to activate.

## Fundamentals

- [ ] CAP theorem — CP vs AP, when to pick each
- [ ] Consistency levels — strong / eventual / read-your-write / monotonic
- [ ] Load balancing — L4 vs L7, sticky sessions, consistent hashing
- [ ] Caching — read-through, write-through, write-behind, cache-aside; TTL vs eviction
- [ ] CDN — push vs pull, when NOT to use CDN
- [ ] Rate limiting — token bucket vs leaky bucket vs sliding window counter
- [ ] Database choice — relational vs document vs wide-column vs time-series
- [ ] Sharding — range vs hash vs directory; hotspot prevention
- [ ] Replication — leader-follower vs multi-leader vs leaderless
- [ ] Message queues — at-least-once vs at-most-once vs exactly-once; back-pressure

## Patterns

- [ ] Saga (choreography vs orchestration) — distributed transactions
- [ ] Outbox pattern — reliable event publishing
- [ ] CQRS — read/write model separation
- [ ] Event sourcing — when state IS the event log
- [ ] Circuit breaker — fail-fast, half-open probe
- [ ] Bulkhead — isolation of failure domains
- [ ] Strangler fig — incremental monolith decomposition

## Numbers to remember

| Thing | Ballpark |
|---|---|
| L1 cache | 1 ns |
| RAM read | 100 ns |
| SSD read | 100 µs |
| HDD seek | 10 ms |
| Network round-trip (same DC) | 0.5 ms |
| Network round-trip (cross-region) | 150 ms |
| MySQL row read (index hit) | 1 ms |
| Kafka throughput | 1 M msg/s |

## Scale anchors

- Twitter: 500M tweets/day → ~6K writes/s, ~600K reads/s
- WhatsApp: 100B messages/day → ~1.1M msg/s
- YouTube: 500 hours video uploaded/min
