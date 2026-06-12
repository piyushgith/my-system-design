# 18 — Ownership, Governance, and Infrastructure Cost: Stock Trading Order Book

## Objective

Ownership, decision governance, and run cost. Distinctive here: the matching loop is a *protected zone* — a tiny code surface where ordinary engineering freedoms (add a log line, allocate a list, read the clock) break determinism or latency, so governance is mostly about controlling who may touch what under which gates.

---

## Ownership

| Area | Owner | On-call |
|---|---|---|
| Matching engine (book, matching loop, snapshots) | Matching team | 24×7 during + around trading hours |
| Order gateway, risk engine | Trading platform team | 24×7 trading hours |
| Market data fan-out (WebSocket, conflation) | Market data team | 24×7 trading hours |
| FIX gateway | Trading platform team | Per-counterparty escalation |
| Settlement, audit consumers | Post-trade team | Business hours (P2/P3 per failure taxonomy) |
| Trading-halt authority (circuit breakers, manual halt) | **Operations desk, not engineering** | Halt runbook in 13-deployment |

**Protected-zone rule:** changes inside the matching loop require (a) a matching-team owner as author or co-author, (b) the determinism replay suite green (17-testing-strategy — the suite *defines* what the loop may do), (c) JMH latency budget green. No exceptions for "trivial" changes — allocation in the hot path is invisible in review and visible at p99.

## Decision Governance

- ADRs for: order-type semantics, matching priority rules, durability-point changes (10-message-queue-design — the input-journal design is contractual for recovery), market-data protocol changes (clients implement it), snapshot cadence
- Doc-drift rule: behavior changes update docs 00–17 in the same PR — this system's review found a durability contradiction between NFR and queue design; the rule exists so the next drift is caught at PR time
- Market rules vs engineering: tick sizes, halt thresholds, session times are *market configuration* owned by operations with change control — engineering ships the mechanism

## Infrastructure Cost Estimate (Indicative)

Single region, V2-ish scale (multi-symbol, FIX, pre-Disruptor-everywhere). Latency-sensitive systems pay for headroom: hot-path nodes run deliberately under-utilized.

| Component | Sizing | ~Monthly (USD) |
|---|---|---|
| Matching engine nodes (compute-optimized, pinned, overprovisioned ~30% util) | 4–6 × c6i.2xlarge | 1,500–2,500 |
| Order/FIX/market-data gateway nodes | 6–10 nodes | 1,200–2,000 |
| Kafka (MSK 3 brokers, high-throughput config) | | 1,500–2,500 |
| PostgreSQL (event journal, Multi-AZ) | | 2,000–3,000 |
| Redis (risk engine, Multi-AZ) | | 500–900 |
| Observability (high-cardinality latency metrics are the expensive part) | | 1,000–2,000 |
| S3 cold path (Parquet, 7-year) | | 200–600 |
| **Total** | | **~8,000–13,500/mo** |

**Cost notes:** the headroom is the product — running matching nodes "efficiently" at 80% utilization trades latency tails for a few hundred dollars; wrong trade in this domain. V3 co-location/bare-metal (16-advanced-improvements) changes the cost model entirely (capex-ish, network engineering) — that ADR is a business decision about client latency SLAs, not an infra upgrade. Observability cost scales with metric cardinality: per-symbol × per-percentile histograms are worth it for the top symbols, sampled for the tail.

## Interview Discussion Points

- Protected-zone governance: how to let 20 engineers ship around a core that only 4 may touch, without making those 4 a bottleneck (answer: the gates are automated — determinism + latency suites — so the human constraint is review, not permission)
- Why trading-halt authority belongs to operations, with engineering providing the button
- Where the money goes in low-latency systems: headroom and observability, not raw compute
