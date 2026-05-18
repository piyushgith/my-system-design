# 04 — API Design: E-Commerce Platform

---

## Objective

Define the REST API design principles, key endpoint specifications, versioning strategy, pagination, idempotency, error handling, and API evolution strategy for the e-commerce platform.

---

## 1. API Design Principles

- **REST over HTTP/1.1 and HTTP/2** for all external (consumer-facing) APIs
- **gRPC** for internal service-to-service communication where latency and throughput are critical (Inventory reservation, Fraud scoring)
- **Resource-oriented URLs:** URLs represent resources (nouns), not actions (verbs)
- **Stateless:** Every request contains all information needed; no server-side session state at API layer
- **Idempotent mutations:** POST endpoints for order and payment creation accept idempotency keys
- **Versioning:** URI-based versioning (`/v1/`, `/v2/`) for external APIs; header-based for internal
- **HATEOAS:** Implemented for Order lifecycle only (hypermedia links for next valid actions)

---

## 2. API Versioning Strategy

| Strategy | Decision | Reason |
|---|---|---|
| URI versioning (`/v1/orders`) | **Chosen for external APIs** | Explicit, cacheable, easy to test |
| Header versioning (`API-Version: 2`) | For internal service APIs | Less URL pollution for service mesh |
| Query param versioning | Rejected | Not cacheable by CDNs |
| Accept header negotiation | Rejected | Poor tooling support, complex gateway routing |

**Deprecation policy:**
- A version is deprecated with 12 months notice
- Deprecated endpoints return `Deprecation: true` and `Sunset: <date>` headers
- Breaking changes always require a new version
- Additive changes (new optional fields) are backward-compatible and do not require a new version

---

## 3. Core API Endpoints

### 3.1 Product Catalog API

```
GET    /v1/products                          List products (paginated, filterable)
GET    /v1/products/{productId}              Get product detail
GET    /v1/products/{productId}/skus         List SKUs for a product
GET    /v1/products/{productId}/reviews      List reviews for a product
POST   /v1/products                          Create product listing (seller only)
PUT    /v1/products/{productId}              Update product (seller only)
DELETE /v1/products/{productId}              Archive product (soft delete)
GET    /v1/categories                        Get category tree
GET    /v1/categories/{categoryId}/products  Products in category (paginated)
```

### 3.2 Inventory API (Internal-facing)

```
GET  /v1/inventory/{skuId}                  Get stock level for a SKU
POST /v1/inventory/reserve                  Soft-reserve inventory (cart add)
POST /v1/inventory/confirm-reserve          Hard-reserve (pre-order placement)
POST /v1/inventory/release                  Release soft reservation
POST /v1/inventory/deduct                   Deduct on shipment (internal)
POST /v1/inventory/adjust                   Manual stock adjustment (seller/admin)
GET  /v1/inventory/bulk                     Bulk stock check for multiple SKUs
```

### 3.3 Cart API

```
GET    /v1/cart                             Get current user's cart
POST   /v1/cart/items                       Add item to cart
PUT    /v1/cart/items/{itemId}              Update quantity
DELETE /v1/cart/items/{itemId}              Remove item
DELETE /v1/cart                             Clear cart
POST   /v1/cart/apply-coupon               Apply coupon code
DELETE /v1/cart/coupon                      Remove coupon
POST   /v1/cart/validate                    Validate cart (prices + availability) — pre-checkout
```

### 3.4 Order API

```
POST   /v1/orders                          Place order (idempotent with X-Idempotency-Key)
GET    /v1/orders                          List user's orders (paginated)
GET    /v1/orders/{orderId}               Get order detail
POST   /v1/orders/{orderId}/cancel        Cancel order
GET    /v1/orders/{orderId}/tracking      Get shipment tracking
POST   /v1/orders/{orderId}/return        Initiate return for order
GET    /v1/orders/{orderId}/invoice       Download invoice (PDF)

# Seller endpoints
GET    /v1/seller/orders                  List orders for seller's products
PUT    /v1/seller/orders/{orderId}/accept Accept order
PUT    /v1/seller/orders/{orderId}/reject Reject order
```

### 3.5 Payment API (Internal + External)

```
POST   /v1/payments/initiate              Initiate payment (returns client secret for frontend)
POST   /v1/payments/capture               Capture authorized payment (webhook-triggered, internal)
POST   /v1/payments/{paymentId}/refund    Initiate refund
GET    /v1/payments/{paymentId}           Get payment status
POST   /v1/webhooks/payment-gateway       Incoming payment gateway webhook
```

### 3.6 Search API

```
GET    /v1/search/products                Full-text search with facets
GET    /v1/search/suggest                 Autocomplete suggestions
GET    /v1/search/facets                  Get available facets for a query
```

### 3.7 User API

```
POST   /v1/auth/register                  Register user
POST   /v1/auth/login                     Login (returns JWT)
POST   /v1/auth/refresh                   Refresh JWT
POST   /v1/auth/logout                    Invalidate session
GET    /v1/users/me                       Get current user profile
PUT    /v1/users/me                       Update profile
GET    /v1/users/me/addresses             List addresses
POST   /v1/users/me/addresses             Add address
PUT    /v1/users/me/addresses/{id}        Update address
DELETE /v1/users/me/addresses/{id}        Delete address
```

---

## 4. Request/Response Standards

### 4.1 Successful Responses

```
HTTP 200 OK         - Successful read
HTTP 201 Created    - Successful creation (with Location header)
HTTP 202 Accepted   - Async operation accepted (order placement)
HTTP 204 No Content - Successful update/delete with no response body
```

### 4.2 Standard Response Envelope

All responses follow a consistent envelope:

```json
{
  "data": { ... },
  "meta": {
    "request_id": "req_abc123",
    "timestamp": "2026-05-18T10:30:00Z",
    "version": "v1"
  },
  "pagination": {
    "page": 1,
    "page_size": 20,
    "total_pages": 50,
    "total_count": 1000,
    "next_cursor": "eyJpZCI6MTAwfQ=="
  }
}
```

### 4.3 Error Response Standard

```json
{
  "error": {
    "code": "INVENTORY_INSUFFICIENT",
    "message": "Requested quantity exceeds available stock",
    "details": [
      {
        "field": "quantity",
        "issue": "Available: 3, Requested: 5",
        "sku_id": "sku_xyz789"
      }
    ],
    "request_id": "req_abc123",
    "timestamp": "2026-05-18T10:30:00Z",
    "doc_url": "https://docs.platform.com/errors/INVENTORY_INSUFFICIENT"
  }
}
```

### 4.4 HTTP Error Codes

| Code | Usage |
|---|---|
| 400 Bad Request | Validation failure, malformed request |
| 401 Unauthorized | Missing or invalid authentication |
| 403 Forbidden | Authenticated but insufficient permissions |
| 404 Not Found | Resource does not exist |
| 409 Conflict | Optimistic locking failure, duplicate idempotency key |
| 410 Gone | Resource permanently deleted |
| 422 Unprocessable Entity | Business rule violation (e.g., cancelling a shipped order) |
| 429 Too Many Requests | Rate limit exceeded (with Retry-After header) |
| 500 Internal Server Error | Unexpected server failure |
| 502 Bad Gateway | Upstream dependency failure |
| 503 Service Unavailable | Service temporarily unavailable (with Retry-After) |

---

## 5. Pagination Strategy

### 5.1 Cursor-Based Pagination (Primary)

Used for: Search results, Order history, Product listings

**Why cursor over offset?** Offset pagination breaks when items are inserted/deleted between pages. Cursor-based is stable for real-time data.

**Request:**
```
GET /v1/orders?limit=20&cursor=eyJpZCI6MTAwfQ==
```

**Response:**
```json
{
  "data": [...],
  "pagination": {
    "limit": 20,
    "next_cursor": "eyJpZCI6MTIwfQ==",
    "has_more": true
  }
}
```

Cursor is a base64-encoded JSON containing the sort key and ID of the last item returned.

### 5.2 Offset Pagination (Secondary)

Used for: Admin dashboards, seller analytics, contexts where total count is needed

```
GET /v1/seller/orders?page=2&page_size=50
```

**Limit:** Max page_size = 100. Reject requests exceeding this.

---

## 6. Idempotency Strategy

All state-changing operations that have financial consequences must be idempotent.

**Implementation:**
- Client sends `X-Idempotency-Key: <uuid>` header
- Server stores (idempotency_key, user_id, response) in Redis with 24-hour TTL
- If same key received again: return stored response without re-processing
- If same key with different payload: return 422 Unprocessable Entity

**Applies to:**
- `POST /v1/orders` — Order placement
- `POST /v1/payments/initiate` — Payment initiation
- `POST /v1/payments/{id}/refund` — Refund
- `POST /v1/inventory/reserve` — Inventory reservation

**Idempotency key collisions:** If two requests with different bodies but the same key arrive, the second returns 422 with error code `IDEMPOTENCY_KEY_CONFLICT`.

---

## 7. Rate Limiting

Applied at the API Gateway level using Redis-backed token bucket algorithm.

| Tier | Limit | Burst |
|---|---|---|
| Guest / Unauthenticated | 30 RPM | 10 RPS |
| Authenticated Buyer | 120 RPM | 30 RPS |
| Seller API | 300 RPM | 60 RPS |
| Admin API | 600 RPM | 120 RPS |
| Internal Service-to-Service | No limit (internal network) | — |

**Response headers on every request:**
```
X-RateLimit-Limit: 120
X-RateLimit-Remaining: 87
X-RateLimit-Reset: 1716025200
```

**On 429:** Response includes `Retry-After: 15` (seconds until reset).

---

## 8. Search API Design

The search endpoint supports rich filtering and must be highly tuned for performance.

```
GET /v1/search/products?
  q=running+shoes
  &category=footwear
  &brand=nike,adidas
  &price_min=500
  &price_max=5000
  &rating_min=4
  &in_stock=true
  &sort=relevance|price_asc|price_desc|newest|rating
  &limit=20
  &cursor=...
```

**Facets response:**
```json
{
  "data": { "products": [...] },
  "facets": {
    "brand": [
      { "value": "Nike", "count": 1234 },
      { "value": "Adidas", "count": 987 }
    ],
    "price_ranges": [
      { "range": "0-500", "count": 450 },
      { "range": "500-2000", "count": 2300 }
    ],
    "rating": [
      { "value": "4+", "count": 3456 }
    ]
  }
}
```

---

## 9. Webhook Design (Inbound from Payment Gateway)

Payment gateways call our webhook to confirm payment events. This is an external write path.

**Security:**
- HMAC-SHA256 signature on webhook body using shared secret
- Replay attack prevention: reject events with timestamp > 5 minutes old
- Idempotent processing: event_id deduplication in Redis

**Webhook retry from gateway:** Gateways retry with exponential backoff for up to 72 hours. Our endpoint must return 2xx within 3 seconds or gateway retries.

**Processing:** Webhook handler writes event to an internal Kafka topic. A consumer processes the payment event asynchronously. The webhook handler responds immediately without waiting for business logic.

---

## 10. API Evolution Strategy

| Change Type | Backward Compatible? | Strategy |
|---|---|---|
| Adding optional request field | Yes | Deploy, no version bump |
| Adding response field | Yes | Deploy, no version bump |
| Removing response field | No | New version, 12-month sunset |
| Changing field type | No | New version |
| Changing field name | No | New version |
| Changing error codes | No | New version |
| Changing URL structure | No | New version, redirect old |
| Adding required request field | No | New version |

**Contract testing:** Every service has consumer-driven contract tests (Pact) that verify API compatibility before deployment. A service cannot deploy if it breaks an existing consumer contract.

---

## 11. Internal Service API (gRPC)

For performance-critical internal calls, gRPC is used.

| Service | gRPC Method | Description |
|---|---|---|
| InventoryService | `ReserveStock(ReserveRequest) → ReserveResponse` | Hard inventory reservation |
| InventoryService | `CheckAvailability(CheckRequest) → AvailabilityResponse` | Bulk availability check |
| FraudService | `ScoreTransaction(TransactionContext) → RiskScore` | Fraud scoring |
| PricingService | `CalculatePrice(CartContext) → PriceBreakdown` | Cart pricing |
| IdentityService | `ValidateToken(TokenRequest) → UserClaims` | Token validation (internal only) |

**Service mesh:** gRPC services are registered in the service mesh (Istio or AWS App Mesh). mTLS is enforced for all inter-service gRPC calls.

---

## 12. Tradeoffs

| Decision | Benefit | Cost |
|---|---|---|
| URI versioning | Simple, cacheable, explicit | URL pollution, routing complexity at gateway |
| Cursor pagination | Stable under concurrent writes | Cannot jump to arbitrary page |
| Idempotency keys | Safe retries for clients | Storage overhead, key management for clients |
| gRPC for internal | Performance, type safety | Learning curve, less HTTP tooling |
| Webhook over polling | Real-time payment events | Must handle retries, idempotency, security |

---

## 13. Interview-Level Discussion Points

- **How do you handle API versioning without breaking existing clients?** URI versioning with a 12-month sunset policy. Old versions run in parallel with new versions. We use feature flags at the API gateway level to route traffic, allowing gradual cutover.
- **Why cursor pagination instead of offset for order history?** Order history is append-only. But for product search, results change as inventory depletes and new products are added. Offset pagination would cause users to see duplicate or skipped items as they page through results. Cursor-based pagination ensures a stable view of the result set.
- **How do you prevent payment double-charges on network retries?** Idempotency keys. The client sends a UUID with each payment request. The server stores the key and result. On retry with the same key, the server returns the stored result without re-processing. The key is tied to the user ID to prevent cross-user key reuse attacks.
- **What is the payment gateway webhook contract?** We implement a webhook endpoint that: validates HMAC signature, checks event timestamp (reject if > 5 min), checks event ID for deduplication, and returns 2xx immediately. The actual business processing happens asynchronously. This ensures we don't miss payment confirmations if our processing is slow.
