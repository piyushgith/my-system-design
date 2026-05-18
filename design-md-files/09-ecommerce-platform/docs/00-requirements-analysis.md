# 00 — Requirements Analysis: E-Commerce Platform (Amazon-Scale)

---

## Objective

Define the functional and non-functional requirements for a production-grade, Taking-scale e-commerce platform. Establish capacity estimates, traffic assumptions, and design constraints that will govern every architectural decision downstream.

---

## 1. Problem Statement

Design a horizontally scalable e-commerce marketplace platform supporting:
- Millions of buyers and sellers
- A catalog of hundreds of millions of products
- Real-time inventory management with flash-sale support
- End-to-end order lifecycle from placement to delivery
- Integrated payment processing and fraud detection
- Personalized recommendations
- Full-text product search

The platform must handle Black Friday-scale traffic spikes, support a seller/marketplace model, and maintain high availability with strong consistency where money and inventory are involved.

---

## 2. Functional Requirements

### 2.1 User Management
- Buyer registration, login, profile management
- Seller onboarding, KYC (Know Your Customer) verification
- Role-based access: buyer, seller, admin, support agent
- Address book management (multiple shipping addresses)
- Saved payment methods (tokenized)

### 2.2 Product Catalog
- Sellers can create, update, archive product listings
- Product variants (size, color, material) linked to a parent product
- Category hierarchy (multi-level taxonomy)
- Product images, videos, and rich description (HTML/markdown)
- Product attributes schema per category (custom fields)
- Bundle and kit products
- Digital goods support (e-books, software licenses)

### 2.3 Inventory Management
- Per-SKU inventory tracking across multiple fulfillment centers (warehouses)
- Soft reservation on add-to-cart (TTL-based, e.g., 15 minutes)
- Hard reservation on order placement
- Inventory deduction on shipment confirmation
- Backorder and pre-order support
- Low-stock alerts for sellers
- Flash sale inventory allocation with distributed locking

### 2.4 Search and Discovery
- Full-text product search with typo tolerance
- Faceted filtering (category, price range, brand, rating, availability)
- Autocomplete and search suggestions
- Sponsored/promoted listings mixed into organic results
- Personalized ranking based on user history

### 2.5 Cart and Checkout
- Persistent cart (survives session loss)
- Guest checkout with cart merge on login
- Multi-seller cart with per-seller subtotals
- Coupon/promo code application
- Real-time price and availability validation at checkout
- Address validation
- Shipping method selection with estimated delivery dates
- Tax calculation per jurisdiction
- Order summary with itemized breakdown

### 2.6 Payment Processing
- Credit/debit card, UPI, wallets, buy-now-pay-later
- PCI-DSS compliant tokenization (delegated to payment gateway)
- Payment authorization → capture flow
- Split payments to multiple sellers via marketplace model
- Refund processing (full and partial)
- Payment failure retry with exponential backoff
- Fraud scoring prior to authorization

### 2.7 Order Management
- Order lifecycle: `PLACED → CONFIRMED → PROCESSING → SHIPPED → OUT_FOR_DELIVERY → DELIVERED`
- Cancellation window before fulfillment begins
- Order splitting by seller or warehouse
- Returns and refunds: `RETURN_REQUESTED → PICKUP_SCHEDULED → RECEIVED → REFUND_INITIATED → REFUNDED`
- Real-time order tracking with shipment events
- Email/SMS/push notifications at each status transition
- Invoice generation

### 2.8 Recommendations Engine
- Collaborative filtering (users like you also bought)
- Content-based filtering (similar products)
- Trending items per category
- Recently viewed
- "Frequently bought together"
- Personalized homepage feed

### 2.9 Seller/Marketplace
- Seller dashboard: inventory, orders, revenue analytics
- Commission structure per category
- Seller rating and reputation system
- Dispute resolution workflow
- Seller payout cycles (weekly/biweekly)
- Seller-specific promotions

### 2.10 Reviews and Ratings
- Verified purchase reviews only
- Star ratings per product
- Review helpfulness voting
- Admin moderation queue
- Seller response to reviews

---

## 3. Non-Functional Requirements

| Dimension | Requirement |
|---|---|
| Availability | 99.99% for checkout, payment, and order services; 99.9% for catalog, search |
| Read Latency (p99) | Product page: < 200ms; Search: < 300ms; Cart: < 100ms |
| Write Latency (p99) | Order placement: < 500ms; Inventory update: < 100ms |
| Throughput | 500K RPS sustained; 2M RPS during peak (Black Friday) |
| Consistency | Strong consistency for inventory and payments; eventual for catalog and reviews |
| Durability | Zero data loss for orders and payment transactions |
| Scalability | Horizontal scaling; no single point of failure |
| Partition Tolerance | System remains operational during partial network failures |
| Disaster Recovery | RTO < 15 minutes; RPO < 1 minute |
| Data Retention | Orders and financial records: 7 years; User activity: 2 years |
| Compliance | PCI-DSS, GDPR, SOC2, local tax laws |

---

## 4. Assumptions

- Platform operates as a **third-party marketplace** (sellers list, platform fulfills or drop-ships)
- Payment gateway integration is external (Stripe, Razorpay, Adyen) — we own the orchestration, not card processing
- Logistics integration is via third-party APIs (FedEx, UPS, local couriers) — we own tracking aggregation
- Tax engine is a third-party service (Avalara, TaxJar) with caching layer
- Fraud detection uses a combination of internal scoring + external vendor (Sift, Kount)
- Recommendations are batch-computed (near-real-time for session-based, offline for collaborative filtering)
- Email/SMS delivery is via third-party providers (SendGrid, Twilio)
- Product images served via CDN (CloudFront or similar)
- Initial launch is B2C with B2B as a future consideration

---

## 5. Constraints

- PCI-DSS: Raw card data must never touch our servers
- GDPR: User data deletion must propagate within 30 days
- Inventory accuracy: Overselling must be prevented (distributed locking + transactional reservation)
- Financial ledger: Double-entry bookkeeping for all money movements
- Audit trail: All state changes must be logged with actor, timestamp, and reason

---

## 6. Scale Estimation

### 6.1 User Scale
| Metric | Estimate |
|---|---|
| Registered users | 500M |
| Monthly active users (MAU) | 150M |
| Daily active users (DAU) | 30M |
| Sellers | 5M |
| Daily orders | 5M |
| Peak orders/second (Black Friday) | 5,000 OPS |

### 6.2 Catalog Scale
| Metric | Estimate |
|---|---|
| Total products | 500M |
| Total SKUs (with variants) | 2B |
| New listings per day | 500K |
| Product page views per day | 3B |
| Catalog read/write ratio | 200:1 |

### 6.3 Traffic Assumptions
| Event | RPS |
|---|---|
| Product page loads | 100,000 |
| Search queries | 50,000 |
| Cart updates | 20,000 |
| Checkout initiations | 5,000 |
| Order placements | 2,000 (avg), 5,000 (peak) |
| Inventory checks | 80,000 |

### 6.4 Storage Estimation

| Data Type | Size/Record | Total |
|---|---|---|
| Product records | ~5KB avg | 2.5 TB |
| Product images (CDN) | ~500KB avg, 10 images | 10 PB (CDN-backed) |
| Orders | ~2KB | 3.6 TB/year |
| User profiles | ~1KB | 500 GB |
| Event log (Kafka) | ~500B | 2 TB/day retention |
| Search index (Elasticsearch) | ~8KB/product | 16 TB |
| Session data (Redis) | ~2KB | 500 GB |
| Analytics (Redshift/BigQuery) | — | 100 TB/year |

### 6.5 Bandwidth Estimation
- Product images: 3B views/day × 500KB average = ~1.5 PB/day (CDN absorbs 99%)
- API traffic: ~500K RPS × 5KB avg payload = 2.5 GB/s inbound + outbound
- Kafka throughput: ~100K events/sec × 1KB = 100 MB/s

---

## 7. Read/Write Patterns

| Operation | Pattern | Consistency Need |
|---|---|---|
| Product page load | Read-heavy (200:1) | Eventual OK |
| Search | Read-heavy | Eventual OK |
| Inventory check | Read-heavy with hot writes on flash sales | Strong required |
| Cart update | Read-write balanced | Strong preferred |
| Order placement | Write-heavy atomic | Strong required |
| Payment processing | Write, idempotent | Strong required |
| Reviews | Mostly reads, writes occasional | Eventual OK |
| Seller analytics | Batch reads, near-real-time | Eventual OK |
| Recommendations | Mostly reads (pre-computed) | Eventual OK |

---

## 8. Latency Expectations

| Flow | Target p50 | Target p99 |
|---|---|---|
| Homepage load | 50ms | 150ms |
| Search results | 80ms | 300ms |
| Product detail page | 30ms | 150ms |
| Cart add/update | 20ms | 100ms |
| Checkout page load | 100ms | 400ms |
| Order placement (end-to-end) | 200ms | 800ms |
| Payment authorization | 300ms | 1000ms |
| Order status fetch | 20ms | 80ms |

---

## 9. Availability Targets

| Service | SLA | Downtime Budget/Year |
|---|---|---|
| Payment Service | 99.99% | 52 minutes |
| Order Service | 99.99% | 52 minutes |
| Inventory Service | 99.99% | 52 minutes |
| Product Catalog | 99.9% | 8.7 hours |
| Search | 99.9% | 8.7 hours |
| Recommendations | 99.5% | 43.8 hours |
| Seller Dashboard | 99.5% | 43.8 hours |

---

## 10. Interview-Level Discussion Points

- **Why separate SLAs?** Different services have different business impact on failures. Payment downtime costs revenue directly; recommendation downtime is annoying but not blocking.
- **How do you handle Black Friday traffic spikes?** Pre-scaling via scheduled autoscaling, load shedding for non-critical paths, circuit breakers on downstream dependencies, feature flags to disable expensive features (recommendations, personalization) under extreme load.
- **What is the biggest scaling challenge?** Inventory during flash sales: hundreds of thousands of users hitting a single SKU's inventory record simultaneously. Standard DB transactions will collapse — requires Redis-based distributed counters with eventual DB reconciliation.
- **How do you prevent overselling?** Reservation model: Redis atomic DECR for soft reservation, transactional DB write for hard reservation on order placement. If Redis and DB diverge, reconciliation job corrects within seconds.
- **Startup vs Taking difference:** A startup would start with a monolith, a single Postgres DB, and Stripe. Taking has dedicated teams per domain, separate data stores per service, petabytes of ML training data for personalization. The architecture gap is driven by team size and traffic, not technical preference.
