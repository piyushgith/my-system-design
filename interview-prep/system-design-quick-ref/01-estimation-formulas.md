# Back-of-Envelope Estimation Formulas

## Traffic

```
RPS = DAU × actions_per_day / 86400
Peak RPS = avg RPS × 3 (rule of thumb)
Read:Write ratio — state it explicitly for every system
```

## Storage

```
Storage/year = write_RPS × record_size_bytes × 86400 × 365
Add 20% overhead for indexes
Add replication factor (typically 3×)
```

## Bandwidth

```
Bandwidth = RPS × avg_payload_bytes
CDN offload: apply if read-heavy and static content
```

## Servers

```
Servers = Peak_RPS / RPS_per_server
RPS per server: ~1K (heavy DB), ~10K (compute), ~100K (static cache)
```

## Cache hit ratio impact

```
DB load = Total_reads × (1 - cache_hit_rate)
Aim for 95%+ hit rate on hot data
```

## Common estimates

| System | DAU | Write RPS | Read RPS |
|---|---|---|---|
| URL shortener | 100M | 1K | 100K |
| Chat (WhatsApp) | 500M | 500K | 500K |
| Social feed (Twitter) | 300M | 6K | 600K |
| Video (YouTube) | 2B | 500 | 5M |
| Ride sharing (Uber) | 20M | 200 | 2K (location updates: 200K) |
