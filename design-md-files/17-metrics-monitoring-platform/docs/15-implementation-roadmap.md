# 15 — Implementation Roadmap: Metrics & Monitoring Platform

## Objective

Phase-wise plan from MVP to global-scale observability platform. Each phase is independently deployable and delivers production value before the next phase begins.

---

## Phase 0 — MVP (Weeks 1–6)

### Goal
Working observability for a single team's services. No multi-tenancy. Manual configuration. Proves the stack works end-to-end.

### Features
- Single Prometheus instance scraping all services via static scrape configs
- Grafana connected to Prometheus datasource
- 5 pre-built dashboards: JVM metrics, HTTP request rates, error rates, database pool, Kafka lag
- AlertManager (single instance) with email + Slack receivers
- 10 baseline alert rules: HighErrorRate, HighLatency, ServiceDown, DiskFull, HighMemory
- ELK stack (single ES node + Logstash + Kibana) for log aggregation
- Log shipping via Filebeat agents on each host
- 15-day local retention for metrics, 7-day for logs

### Architecture
```
Services → Prometheus (scrape, 15s) → Grafana
                                    → AlertManager → Slack/Email
Services → Filebeat → Logstash → Elasticsearch → Kibana
```

### Infra
- 3 VMs: 1 × metrics node (8 CPU, 32GB RAM, 500GB SSD), 1 × ES node (8 CPU, 16GB RAM), 1 × Grafana+AlertManager (4 CPU, 8GB RAM)
- Docker Compose for local dev environment (all services, 1 command startup)

### Risks
- Single Prometheus = SPOF for metrics
- Single ES node = no replica, data loss on disk failure
- No retention policy → disk fills in ~3 weeks at current volume

### Team
1–2 engineers. One to configure Prometheus/Grafana, one for ELK.

---

## Phase 1 — Production Hardening (Weeks 7–16)

### Goal
Remove single points of failure. Add long-term storage. Make alert management operational.

### Features
- Prometheus HA: 2 replicas scraping identical targets, same scrape configs
- Thanos Sidecar on each Prometheus → upload 2h blocks to S3 (30-day retention in S3)
- Thanos Querier as global query endpoint → Grafana points to Thanos, not directly to Prometheus
- Thanos Compactor (singleton) for S3 block compaction and retention enforcement
- AlertManager 3-node cluster (gossip HA)
- Silences persisted to PersistentVolume
- Dead man's switch Watchdog alert configured
- ES 3-node cluster (1 master + 2 data) with 1 replica per index
- ES ILM: hot 7 days → warm 30 days → delete 90 days
- Kubernetes deployment via Helm (Prometheus Operator, ECK for Elasticsearch)
- ServiceMonitor CRDs for automatic target discovery in Kubernetes
- Basic RBAC in Grafana (Admin / Editor / Viewer roles)

### Architecture
```
Services → Prometheus-1 ──┐
                           ├─ Thanos Querier → Grafana
Services → Prometheus-2 ──┘       │
               │                  └─ AlertManager HA → PagerDuty/Slack
Thanos Sidecar-1/2 → S3 ← Thanos Compactor
                    ↑
         Thanos Store Gateway

Services → Filebeat → Kafka (logs-raw) → Logstash → ES 3-node → Kibana
```

### Infra
- Kubernetes cluster (3 nodes for metrics, 3 nodes for ES)
- S3 bucket with versioning enabled (Thanos block storage)
- PersistentVolumes for Prometheus WAL, AlertManager state

### Architecture Evolution from Phase 0
- Pull model stays but now HA
- Long-term storage via object storage (first appearance)
- Kafka introduced in log pipeline (decouples Logstash from ES write latency)

### Risks
- Thanos Compactor must be singleton — two compactors corrupt S3 blocks
- ES rolling upgrade requires careful shard relocation management
- Prometheus HA with Thanos dedup requires matching `external_labels` configuration

### Complexity Increase
Medium. Thanos adds ~5 new components. ES cluster management is non-trivial.

### Team
2–3 engineers. Dedicated SRE role emerging.

---

## Phase 2 — Multi-Tenancy & Self-Service (Weeks 17–32)

### Goal
Support multiple teams/products with isolation. Enable engineers to create their own dashboards and alerts without ops involvement.

### Features
- Migrate from Prometheus+Thanos to **Grafana Mimir** for multi-tenant metrics storage
  - Per-tenant cardinality limits (default 1M series/tenant, configurable)
  - Per-tenant retention policies (free: 30d, standard: 90d, premium: 1yr)
  - Per-tenant query rate limits
- OpenTelemetry Collector fleet replaces per-service agents
  - Single OTel config pushed to all collectors via OpAMP (remote management)
  - Metrics + logs + traces collected via OTLP
- Distributed tracing: Grafana Tempo as trace backend
  - Tail-sampling processor: always sample errors + slow traces (>500ms) + 1% of rest
  - Trace-to-log and trace-to-metric correlation via trace_id field
- Grafana self-service:
  - Dashboard provisioning via GitOps (dashboards as YAML in Git, auto-deployed)
  - Alert rule management UI with approval workflow
  - Grafana OnCall for on-call schedule management (replaces manual PagerDuty config)
- Centralized alert rule governance: teams own their rules; platform team reviews cardinality impact
- PII detection and masking in log enrichment pipeline

### Architecture
```
Services (OTel SDK) → OTel Collector → Kafka ──┬──► Mimir (metrics)
                                                ├──► Elasticsearch (logs)
                                                └──► Tempo (traces)

Grafana ──► Mimir (PromQL)
       ──► Elasticsearch (KQL)
       ──► Tempo (TraceQL)
       └──► Exemplar correlation (metric spike → trace_id → Tempo)

Mimir Ruler → AlertManager HA → Grafana OnCall → PagerDuty/Slack
```

### Infra
- Mimir: 3 distributors, 9 ingesters (3 zones × 3 replicas), 3 store-gateways, 1 compactor, 3 query-frontends
- Tempo: 3 ingesters, 3 queriers, 1 compactor, S3 backend
- ES: 5-node cluster (3 data hot, 2 data warm, 3 dedicated masters)

### Architecture Evolution from Phase 1
- Prometheus Agent mode (no local storage) → remote_write to Mimir → Prometheus instances are now stateless
- Multi-store query: Grafana Explore unified search across metrics + logs + traces
- GitOps for configuration management (Infrastructure as Code)

### Risks
- Mimir migration: existing Prometheus data in S3 not directly compatible; parallel run period needed
- OTel Collector fleet management: config drift if OpAMP not properly implemented
- Tail sampling introduces 30s latency in trace availability

### Complexity Increase
High. Mimir has 8 distinct components. Operational runbooks for each.

### Team
4–6 engineers. Dedicated observability platform team. On-call rotation established.

---

## Phase 3 — Global Scale & Intelligence (Weeks 33–52+)

### Goal
Handle 10M+ active series, 50TB+/day logs, global multi-region deployment with cross-region federation. Add intelligent alerting.

### Features
- **Multi-region Mimir**: dedicated Mimir cluster per region; global Mimir Query Frontend federates across regions for cross-region queries
- **Global log search**: cross-region ES federation or migrate high-volume logs to ClickHouse for better compression and SQL-based analytics
- **Streaming alert evaluation**: Apache Flink-based streaming evaluator consuming from Kafka (sub-second SLO burn rate alerts; not limited by 1-minute eval interval)
- **SLO management**: automated SLO burn rate alerts using multi-window error budget approach; SLO dashboards auto-generated from SLO definitions
- **Anomaly detection**: Prometheus recording rules + statistical models for baseline deviation alerts (replaces manual threshold alerts for variable traffic patterns)
- **Continuous profiling**: Grafana Pyroscope for CPU + memory profiling; flame graphs linked to traces
- **eBPF-based collection**: Cilium/Pixie for network-layer observability without application instrumentation (legacy services, third-party binaries)
- **Cost attribution**: per-tenant, per-service observability cost reporting (cardinality, storage, query cost)
- **Automated remediation hooks**: alert → webhook → automated runbook execution (restart pod, scale deployment) for known failure patterns

### Architecture
```
Global:
  Mimir Query Frontend (global) → Regional Mimir clusters (US/EU/APAC)
  Global Grafana → All data sources

Per Region:
  OTel Collectors (eBPF + SDK) → Kafka (per-region)
       ├──► Mimir (metrics)
       ├──► Elasticsearch/ClickHouse (logs)
       └──► Tempo (traces) + Pyroscope (profiles)

  Kafka → Flink Streaming Alert Evaluator → AlertManager

S3 (per-region, cross-region replication for DR)
```

### Infra Evolution
- Mimir ingesters: 30+ nodes across 3 availability zones per region
- ES: 20+ node cluster with frozen tier on S3 for compliance archival
- Flink cluster: 10+ TaskManagers for streaming evaluation
- Total: ~150+ pods for full platform per region

### Architecture Risks
- Cross-region query latency: 50-200ms RTT — acceptable for dashboards, not for alert evaluation
- Flink operational complexity: stateful streaming with exactly-once semantics requires expertise
- Cost explosion: Mimir at this scale requires dedicated cost optimization team
- Config sprawl: 100s of alert rules, 50s of dashboards — governance becomes full-time job

### Team
8–12 engineers. Observability Platform as a product. Product manager. Internal developer portal for self-service.

---

## Phase Summary Table

| Aspect | MVP | Phase 1 | Phase 2 | Phase 3 |
|---|---|---|---|---|
| Active series | < 500K | < 5M | < 50M | 50M+ |
| Log volume | < 10GB/day | < 500GB/day | < 5TB/day | 5TB+/day |
| Tenants | 1 | 1–3 | 10–100 | 100+ |
| Retention | 15d metrics, 7d logs | 30d+S3, 90d logs | Per-tenant, up to 1yr | 1yr+ with cold tier |
| HA | None | Prometheus HA + ES cluster | Mimir HA + Tempo HA | Multi-region HA |
| Tracing | None | None | Tempo + exemplars | Tempo + profiling + eBPF |
| Alert eval | 1m interval | 1m interval | 1m interval | Sub-second (Flink) |
| Team size | 1–2 | 2–3 | 4–6 | 8–12 |
| Infra cost | ~$500/mo | ~$3K/mo | ~$15K/mo | ~$100K+/mo |

---

## MVP Scope Explicitly Out of Scope (to avoid Phase 0 over-engineering)

- No Kafka in Phase 0 — direct Filebeat → Logstash → ES
- No multi-tenancy in Phase 0 — single namespace, shared dashboards
- No distributed tracing in Phase 0 — too complex, zero ROI until you have microservices scale
- No custom retention tiers in Phase 0 — flat 15-day retention
- No HA for anything in Phase 0 — accept downtime risk during initial validation

**Overengineering trap:** Teams try to build Phase 2 from day 1. Mimir adds 8 new components and doubles operational burden. At < 2M series, it provides zero benefit over single Prometheus.

---

## Interview Discussion Points

**Q: When would you skip phases and jump ahead?**
If the company acquires another company's infrastructure (suddenly 50M series overnight) or if SaaS product requires multi-tenancy from day 1 (can't refactor later cheaply). Otherwise, premature scaling is waste.

**Q: How do you migrate from Phase 1 (Prometheus+Thanos) to Phase 2 (Mimir)?**
Run Mimir in parallel receiving remote_write from Prometheus agents. Validate data parity for 2 weeks. Switch Grafana datasources from Thanos to Mimir. Decommission Thanos after 2 more weeks (in case rollback needed). Historical data in S3 accessible via Thanos Store Gateway during transition period.

**Q: What would you do differently if starting fresh today (2026)?**
Start with OpenTelemetry everywhere from day 1 — instrumentation is vendor-neutral. Use Grafana stack (Mimir + Loki + Tempo) for unified UI from Phase 1. Avoid ELK for logs at scale — Loki (log-lines-as-metrics model) is cheaper and integrates better with Grafana.
