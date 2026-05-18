# 05 — Database Design: E-Commerce Platform

---

## Objective

Define the data model, schema design, indexing strategy, partitioning, sharding, and data lifecycle management for each bounded context of the e-commerce platform.

---

## 1. Database Selection per Context

| Context | Primary Store | Why |
|---|---|---|
| Catalog | PostgreSQL | Relational hierarchy (categories, variants), ACID for listing management |
| Inventory | PostgreSQL + Redis | Redis for atomic counters; PostgreSQL for persistent audit trail |
| Cart | Redis | Ephemeral, sub-millisecond reads, TTL-based expiry |
| Order | PostgreSQL (event-sourced) | Durable, ACID, audit trail, complex state machine |
| Payment | PostgreSQL | ACID critical; double-entry ledger requires transactional integrity |
| Search | Elasticsearch | Full-text, faceted, distributed |
| Recommendations | Redis + S3/Data Warehouse | Pre-computed sets in Redis; training data in warehouse |
| User/Identity | PostgreSQL + Redis | PostgreSQL for profiles; Redis for session tokens |
| Seller | PostgreSQL | Relational, ACID for KYC and financial config |
| Fulfillment | PostgreSQL | Relational with status tracking |
| Reviews | PostgreSQL | Relational with moderation workflow |
| Analytics | Redshift / BigQuery | OLAP, columnar, aggregation-heavy |

---

## 2. Core Schema: Catalog Service

```
TABLE products
  id              UUID        PK
  seller_id       UUID        FK → sellers.id (indexed)
  title           VARCHAR(500) NOT NULL
  description     TEXT
  category_id     UUID        FK → categories.id
  status          ENUM('DRAFT','PENDING_REVIEW','ACTIVE','ARCHIVED') NOT NULL
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  deleted_at      TIMESTAMPTZ NULL      -- soft delete
  INDEX: (seller_id, status)
  INDEX: (category_id, status)
  INDEX: (created_at DESC)

TABLE product_skus
  id              UUID        PK
  product_id      UUID        FK → products.id
  seller_sku_ref  VARCHAR(100)             -- seller's own SKU code
  attributes      JSONB                   -- {color: "Blue", size: "L"}
  base_price      NUMERIC(12,2) NOT NULL
  currency        CHAR(3) NOT NULL DEFAULT 'USD'
  weight_grams    INTEGER
  dimensions_cm   JSONB                   -- {l, w, h}
  barcode         VARCHAR(100)
  status          ENUM('ACTIVE','INACTIVE') NOT NULL DEFAULT 'ACTIVE'
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  INDEX: (product_id)
  INDEX: GIN (attributes)              -- for attribute-based queries

TABLE categories
  id              UUID        PK
  parent_id       UUID        NULL FK → categories.id  -- self-referential
  name            VARCHAR(200) NOT NULL
  slug            VARCHAR(200) UNIQUE NOT NULL
  level           INTEGER NOT NULL         -- 0 = root, 1 = top-level, etc.
  path            LTREE                    -- materialized path for hierarchy queries
  commission_rate NUMERIC(5,2)             -- platform commission %
  INDEX: (parent_id)
  INDEX: (path) USING GIST

TABLE product_images
  id              UUID        PK
  sku_id          UUID        FK → product_skus.id
  url             TEXT NOT NULL            -- S3/CDN URL
  display_order   INTEGER NOT NULL
  is_primary      BOOLEAN NOT NULL DEFAULT false
  INDEX: (sku_id, display_order)
```

**Design notes:**
- `JSONB` for SKU attributes allows flexible schema without an EAV (Entity-Attribute-Value) nightmare
- GIN index on attributes supports queries like `attributes @> '{"color": "Blue"}'`
- `LTREE` for category path enables efficient subtree queries (all products in category + subcategories)
- Soft delete on products for legal/compliance retention

---

## 3. Core Schema: Inventory Service

```
TABLE inventory_records
  id              UUID        PK
  sku_id          UUID        NOT NULL UNIQUE per warehouse
  warehouse_id    UUID        NOT NULL
  total_quantity  INTEGER NOT NULL DEFAULT 0
  reserved_qty    INTEGER NOT NULL DEFAULT 0
  available_qty   INTEGER GENERATED ALWAYS AS (total_quantity - reserved_qty) STORED
  low_stock_threshold INTEGER DEFAULT 10
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  UNIQUE (sku_id, warehouse_id)
  CHECK (total_quantity >= 0)
  CHECK (reserved_qty >= 0)
  CHECK (reserved_qty <= total_quantity)

TABLE inventory_reservations
  id              UUID        PK
  sku_id          UUID        NOT NULL
  warehouse_id    UUID        NOT NULL
  quantity        INTEGER NOT NULL
  type            ENUM('SOFT','HARD') NOT NULL
  buyer_session   VARCHAR(200)
  order_id        UUID        NULL
  expires_at      TIMESTAMPTZ NULL         -- NULL for HARD reservations
  status          ENUM('ACTIVE','EXPIRED','CONVERTED','RELEASED') NOT NULL
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  INDEX: (sku_id, status)
  INDEX: (expires_at) WHERE status = 'ACTIVE'   -- for expiry sweep job

TABLE stock_movements
  id              UUID        PK
  sku_id          UUID        NOT NULL
  warehouse_id    UUID        NOT NULL
  quantity_delta  INTEGER NOT NULL          -- positive = stock in, negative = stock out
  reason          ENUM('PURCHASE','RETURN','MANUAL_ADJUSTMENT','DAMAGED','INITIAL','TRANSFER')
  order_id        UUID        NULL
  performed_by    UUID        NULL          -- user who performed manual adjustment
  note            TEXT
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  INDEX: (sku_id, created_at DESC)
  PARTITION BY RANGE (created_at)           -- monthly partitions
```

**Redis keys for Inventory:**
```
inventory:{sku_id}:{warehouse_id}:available  →  INTEGER (atomic counter)
inventory:{sku_id}:{warehouse_id}:reserved   →  INTEGER
reservation:{reservation_id}                →  HASH {sku_id, qty, expiry, type}
reservation:session:{session_id}            →  SET of reservation_ids (for cleanup)
```

**Why dual-store?** Redis provides the atomic DECR/INCR needed for concurrent reservation without locks. PostgreSQL is the system of record. A reconciliation job runs every 30 seconds to detect and correct drift between Redis and PostgreSQL.

---

## 4. Core Schema: Order Service (Event-Sourced)

```
TABLE order_events
  id              BIGSERIAL   PK           -- monotonic ID for ordering
  order_id        UUID        NOT NULL
  event_type      VARCHAR(100) NOT NULL    -- 'OrderPlaced', 'OrderConfirmed', etc.
  event_data      JSONB NOT NULL           -- full event payload
  actor_id        UUID                     -- who triggered this event
  actor_type      ENUM('BUYER','SELLER','SYSTEM','ADMIN')
  occurred_at     TIMESTAMPTZ NOT NULL DEFAULT now()
  INDEX: (order_id, id)   -- event replay per order
  INDEX: (occurred_at)
  PARTITION BY RANGE (occurred_at)

TABLE orders  -- denormalized read model, rebuilt from events
  id              UUID        PK
  buyer_id        UUID        NOT NULL
  status          ENUM('PLACED','CONFIRMED','PROCESSING','SHIPPED','OUT_FOR_DELIVERY','DELIVERED','CANCELLED','PAYMENT_FAILED')
  total_amount    NUMERIC(12,2) NOT NULL
  currency        CHAR(3) NOT NULL
  shipping_address JSONB NOT NULL          -- snapshot at order creation
  coupon_code     VARCHAR(100)
  coupon_discount NUMERIC(12,2)
  placed_at       TIMESTAMPTZ NOT NULL
  confirmed_at    TIMESTAMPTZ
  delivered_at    TIMESTAMPTZ
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  version         INTEGER NOT NULL DEFAULT 0   -- optimistic locking
  INDEX: (buyer_id, placed_at DESC)
  INDEX: (status, updated_at)

TABLE order_lines
  id              UUID        PK
  order_id        UUID        FK → orders.id
  sku_id          UUID        NOT NULL
  product_id      UUID        NOT NULL
  seller_id       UUID        NOT NULL
  title_snapshot  VARCHAR(500) NOT NULL    -- product title at order time
  quantity        INTEGER NOT NULL
  unit_price      NUMERIC(12,2) NOT NULL
  total_price     NUMERIC(12,2) NOT NULL
  reservation_id  UUID        NOT NULL
  line_status     ENUM('ACTIVE','CANCELLED','RETURNED','REFUNDED')
  INDEX: (order_id)
  INDEX: (seller_id, line_status)
  INDEX: (sku_id)

TABLE returns
  id              UUID        PK
  order_line_id   UUID        FK → order_lines.id
  reason          ENUM('DEFECTIVE','WRONG_ITEM','NOT_AS_DESCRIBED','CHANGED_MIND','OTHER')
  status          ENUM('REQUESTED','APPROVED','REJECTED','PICKUP_SCHEDULED','IN_TRANSIT','RECEIVED','REFUND_INITIATED','REFUNDED','DISPUTED')
  requested_at    TIMESTAMPTZ NOT NULL DEFAULT now()
  resolution_at   TIMESTAMPTZ
  notes           TEXT
  INDEX: (order_line_id)
  INDEX: (status, requested_at)
```

**Event Sourcing rationale:** The Order domain has strict compliance requirements — every state change must be auditable. Event sourcing naturally provides this. The `orders` table is a projection (read model) rebuilt from `order_events`. If the projection becomes corrupted, it can be rebuilt by replaying events.

---

## 5. Core Schema: Payment Service

```
TABLE payments
  id              UUID        PK
  order_id        UUID        NOT NULL UNIQUE
  buyer_id        UUID        NOT NULL
  amount          NUMERIC(12,2) NOT NULL
  currency        CHAR(3) NOT NULL
  status          ENUM('INITIATED','AUTHORIZED','CAPTURED','FAILED','REFUNDED','PARTIALLY_REFUNDED')
  gateway         VARCHAR(50)              -- 'stripe', 'razorpay', 'adyen'
  gateway_ref     VARCHAR(200)             -- gateway's payment ID
  auth_token      VARCHAR(500)             -- payment method token
  idempotency_key VARCHAR(200) UNIQUE NOT NULL
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  INDEX: (order_id)
  INDEX: (buyer_id, created_at DESC)
  INDEX: (status, created_at)

TABLE payment_attempts
  id              UUID        PK
  payment_id      UUID        FK → payments.id
  attempt_number  INTEGER NOT NULL
  gateway_request JSONB        -- sanitized request payload
  gateway_response JSONB       -- gateway response (no card data)
  result          ENUM('SUCCESS','FAILURE','TIMEOUT')
  failure_reason  VARCHAR(200)
  attempted_at    TIMESTAMPTZ NOT NULL DEFAULT now()

TABLE ledger_entries
  id              UUID        PK
  reference_id    UUID        NOT NULL    -- payment_id or refund_id
  reference_type  ENUM('PAYMENT','REFUND','COMMISSION','PAYOUT','ADJUSTMENT')
  debit_account   VARCHAR(100) NOT NULL   -- account being debited
  credit_account  VARCHAR(100) NOT NULL   -- account being credited
  amount          NUMERIC(12,2) NOT NULL
  currency        CHAR(3) NOT NULL
  description     TEXT
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  INDEX: (reference_id)
  INDEX: (debit_account, created_at)
  INDEX: (credit_account, created_at)
  -- IMMUTABLE: no updates or deletes ever

TABLE refunds
  id              UUID        PK
  payment_id      UUID        FK → payments.id
  order_line_ids  UUID[]                  -- which lines are being refunded
  amount          NUMERIC(12,2) NOT NULL
  reason          VARCHAR(200)
  status          ENUM('INITIATED','PROCESSING','COMPLETED','FAILED')
  gateway_refund_ref VARCHAR(200)
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
  completed_at    TIMESTAMPTZ
```

---

## 6. Indexing Strategy

| Table | Index | Type | Reason |
|---|---|---|---|
| products | (seller_id, status) | B-Tree | Seller's product dashboard |
| products | (category_id, status) | B-Tree | Category browsing |
| product_skus | GIN(attributes) | GIN | Attribute-based filtering |
| categories | path | GIST (LTREE) | Subtree traversal |
| orders | (buyer_id, placed_at DESC) | B-Tree | Order history |
| orders | (status, updated_at) | B-Tree | Status-based processing |
| order_events | (order_id, id) | B-Tree | Event replay |
| inventory_reservations | (expires_at) WHERE active | Partial B-Tree | Expiry sweep |
| ledger_entries | (debit_account, created_at) | B-Tree | Account statement |
| payments | (order_id) | B-Tree Unique | Payment lookup |

---

## 7. Partitioning Strategy

| Table | Strategy | Key | Partition Size |
|---|---|---|---|
| order_events | Range by occurred_at | Monthly | ~30GB/month |
| stock_movements | Range by created_at | Monthly | ~20GB/month |
| ledger_entries | Range by created_at | Monthly | ~10GB/month |
| payments | Range by created_at | Quarterly | ~5GB/quarter |
| orders | Range by placed_at | Monthly | ~15GB/month |

**Partition management:** Old partitions (> 2 years) are detached from the parent table and archived to S3 (with Parquet export for analytics). The table remains queryable via a foreign data wrapper for compliance purposes.

---

## 8. Sharding Considerations

For Catalog (500M products) and Orders (5M/day), single-instance PostgreSQL will hit limits. Sharding strategy:

| Service | Sharding Key | Strategy |
|---|---|---|
| Catalog | seller_id | Seller's products always on the same shard |
| Orders | buyer_id | Buyer's order history is co-located |
| Inventory | warehouse_id | Warehouse stock is co-located |
| Payments | buyer_id | Buyer's payment history is co-located |

**Implementation:** Use Citus (distributed PostgreSQL) for horizontal scaling without changing application code. Alternatively, application-level sharding with a shard routing layer.

**Hotspot risk:** A popular seller with millions of products could create a hot shard. Mitigate with consistent hashing and virtual nodes.

---

## 9. Read Replica Strategy

| Service | Replica Config | Read Traffic Directed To |
|---|---|---|
| Catalog | 3 read replicas per region | Product page loads, category browsing |
| Orders | 1 read replica | Order history, seller dashboard |
| Inventory | 0 (read from Redis) | Redis for real-time; replica for reports |
| Payments | 1 read replica (separate VPC) | Payment status, refund status |

**Replica lag:** Acceptable for Catalog (seconds of lag is fine — a product description update is not time-critical). Not acceptable for Inventory (use Redis for real-time reads).

---

## 10. Audit and Soft Delete

| Strategy | Implementation |
|---|---|
| Soft delete | `deleted_at TIMESTAMPTZ NULL`; queries include `WHERE deleted_at IS NULL` |
| Hard delete exemption | Financial records (ledger, payments) are never deleted; only anonymized for GDPR |
| Audit trail | All writes go through a service layer that writes to an audit log table |
| Immutable records | ledger_entries, order_events, stock_movements: no UPDATE or DELETE ever |

**GDPR compliance:** User PII (name, email, address) is replaced with pseudonymous tokens on deletion request. Order history is retained (legally required for financial records) but de-linked from the user identity.

---

## 11. Data Archival

| Data | Retention in Hot Store | Archive Destination |
|---|---|---|
| Orders | 2 years | S3 + Glacier, queryable via Athena |
| Ledger entries | 7 years (legal) | S3 + Glacier |
| Product events | 1 year | S3 |
| User activity logs | 1 year | S3 |
| Kafka events | 7 days | S3 (compressed, partitioned by topic/date) |

---

## 12. Multi-Tenancy Consideration

The platform is multi-tenant by nature (multiple sellers). Each seller's data coexists in shared tables. Isolation is enforced at the application layer (every query includes `seller_id = ?`).

For enterprise sellers (B2B future), dedicated schemas or separate database instances may be warranted. This is a schema-per-tenant model, supported by Citus.

---

## 13. Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| Event sourcing in Orders | Perfect audit trail, replay, temporal queries | Storage overhead (events grow forever), projection rebuild cost |
| JSONB for SKU attributes | Flexible schema, no migration for new attributes | Harder to index individual attributes, no FK constraints |
| Redis as Inventory source of truth | Atomic INCR/DECR, high throughput | Redis data loss risk if AOF fails, reconciliation complexity |
| Separate DBs per service | Service isolation, independent scaling | No cross-service SQL joins, data duplication |
| Partitioned tables for time-series | Query performance on recent data, easy archival | Partition management overhead, cross-partition queries slower |

---

## 14. Interview-Level Discussion Points

- **Why not MongoDB for Catalog?** MongoDB's flexible schema is appealing for product attributes, but PostgreSQL with JSONB gives the same flexibility plus ACID transactions, a mature ecosystem, and the ability to model the relational aspects (category hierarchy, seller-product relationship) correctly. MongoDB's lack of true multi-document transactions is a problem for product + SKU operations.
- **How do you handle inventory consistency between Redis and PostgreSQL?** Redis is the authoritative source for real-time availability. PostgreSQL is the persistent record. A reconciliation job runs every 30 seconds comparing Redis counts against PostgreSQL inventory records. Any discrepancy triggers an alert and auto-correction. The key insight: if Redis says 0 and PostgreSQL says 5, we trust the more conservative number (0) until reconciled.
- **What is the write amplification risk in event-sourced orders?** Every state transition writes a new event. At 5M orders/day with an average of 6 state transitions each, that's 30M event writes per day. At ~1KB per event, that's 30GB/day. Partitioning and archival (as described above) keep the hot table size manageable.
- **How do you handle N+1 queries in the seller dashboard?** Batch loading. The seller dashboard shows a list of orders with buyer name, product title, and status. Instead of loading each order and then fetching related data per order, use JOINs for in-database fetching or batch load via `WHERE id IN (...)`. For the most complex dashboards, use a pre-computed materialized view refreshed on a schedule.
