# 04 — API Design: Food Delivery Platform

---

## Objective

Define the REST API contracts for all customer-facing, restaurant-facing, and delivery partner-facing endpoints. Establish idempotency strategy, versioning, pagination, error handling, rate limiting, and API evolution approach. Design APIs as if they will be consumed by mobile apps on 3G connections and by third-party partners.

---

## 1. API Design Principles

| Principle | Application |
|-----------|------------|
| Resource-oriented | URLs identify resources, verbs are HTTP methods |
| Stateless | No server-side session state; JWT carries identity |
| Idempotent where needed | POST /orders requires idempotency key; safe to retry |
| Versioned | `/v1/` prefix; never break existing clients |
| Paginated | All list endpoints return cursor-based pagination |
| Consistent error format | Every 4xx/5xx has the same error envelope |
| Bandwidth-efficient | Field projection support; compact JSON responses |
| Secure | All endpoints require authentication except search/browse |

---

## 2. API Versioning Strategy

### URI Path Versioning (Chosen)

```
https://api.foodplatform.com/v1/orders
https://api.foodplatform.com/v2/orders
```

**Why URI versioning?**
- Visible in logs, monitoring, and API gateway routing
- Easy to route v1 and v2 to different service versions
- Mobile apps specify version explicitly — no negotiation needed

**Alternatives Considered:**
- Header versioning (`Accept: application/vnd.platform.v2+json`) — invisible in logs, harder to route
- Query parameter (`?version=2`) — not idiomatic REST, caching issues

### Version Lifecycle

| Version | State | Support |
|---------|-------|---------|
| v1 | Current | Full support |
| v2 (future) | Alpha | Experimental |
| v0 | Deprecated | 12-month sunset period with deprecation header |

Deprecation header: `Deprecation: Sun, 31 Dec 2025 00:00:00 GMT`

---

## 3. Pagination Strategy

**Cursor-based pagination** for all list endpoints.

```
GET /v1/orders?cursor=eyJpZCI6IjEyMyJ9&limit=20

Response:
{
  "data": [...],
  "pagination": {
    "next_cursor": "eyJpZCI6IjE0MyJ9",
    "has_more": true,
    "limit": 20
  }
}
```

**Why cursor-based over offset?**
- Offset pagination breaks when new records are inserted between pages (e.g., new orders)
- Cursor is stable — based on the ID of the last seen record
- Efficient for deep pages — offset 10000 requires scanning 10000 rows; cursor jumps directly

**Exception:** Admin dashboards with stable, bounded datasets may use offset pagination for simplicity.

---

## 4. Standard Error Format

```json
{
  "error": {
    "code": "ORDER_ITEM_UNAVAILABLE",
    "message": "One or more items in your cart are no longer available",
    "details": [
      {
        "field": "items[0].menuItemId",
        "issue": "Item 'Butter Chicken' is currently unavailable"
      }
    ],
    "trace_id": "abc123xyz",
    "timestamp": "2025-01-15T12:34:56Z"
  }
}
```

**Error Code Categories:**

| HTTP Status | Category | Example |
|------------|---------|---------|
| 400 | Client error — bad input | INVALID_COUPON_CODE, CART_EMPTY |
| 401 | Unauthenticated | TOKEN_EXPIRED, INVALID_TOKEN |
| 403 | Unauthorized | INSUFFICIENT_PERMISSIONS |
| 404 | Resource not found | ORDER_NOT_FOUND |
| 409 | Conflict | ORDER_ALREADY_EXISTS (idempotency) |
| 422 | Business rule violation | ORDER_CANCELLATION_NOT_ALLOWED |
| 429 | Rate limit exceeded | RATE_LIMIT_EXCEEDED |
| 500 | Internal server error | INTERNAL_ERROR (never expose details) |
| 503 | Service unavailable | SERVICE_UNAVAILABLE |

---

## 5. Customer APIs

### 5.1 Authentication

```
POST /v1/auth/send-otp
Body: { "phone": "+919876543210" }
Response 200: { "session_token": "temp_token_for_otp_verification" }

POST /v1/auth/verify-otp
Headers: Authorization: Bearer {session_token}
Body: { "otp": "123456" }
Response 200: { "access_token": "JWT...", "refresh_token": "...", "expires_in": 3600 }

POST /v1/auth/refresh
Body: { "refresh_token": "..." }
Response 200: { "access_token": "...", "expires_in": 3600 }

POST /v1/auth/logout
Headers: Authorization: Bearer {access_token}
Response 204: No Content
```

---

### 5.2 Restaurant Search and Discovery

```
GET /v1/search/restaurants
  Query params:
    q           string   - Search query (optional, returns all if empty)
    city        string   - Required
    lat         decimal  - Customer latitude (for distance sort)
    lng         decimal  - Customer longitude
    cuisine     string[] - Filter by cuisine type (multi-value: cuisine=Indian&cuisine=Chinese)
    sort        enum     - relevance | rating | delivery_time | distance
    is_open     boolean  - Filter to open restaurants only (default: true)
    min_rating  decimal  - Minimum rating filter (1.0-5.0)
    max_delivery_time integer - Max delivery time in minutes
    cursor      string   - Pagination cursor
    limit       integer  - Page size (max: 50, default: 20)

Response 200:
{
  "data": [
    {
      "id": "rest_abc123",
      "name": "Spice Garden",
      "cuisine_types": ["Indian", "Biryani"],
      "rating": 4.3,
      "total_ratings": 2841,
      "delivery_time_minutes": 35,
      "minimum_order": { "amount": 15000, "currency": "INR" },
      "delivery_fee": { "amount": 3000, "currency": "INR" },
      "is_open": true,
      "distance_km": 2.3,
      "logo_url": "https://cdn.platform.com/...",
      "tags": ["bestseller", "new"],
      "promoted": false
    }
  ],
  "pagination": { "next_cursor": "...", "has_more": true, "limit": 20 }
}

Rate limit: 100 req/min per IP (unauthenticated), 200 req/min per user (authenticated)
Cache: Search results cached at Search Service layer (Redis, 60s TTL)
```

---

### 5.3 Menu

```
GET /v1/restaurants/{restaurant_id}/menu
Response 200:
{
  "restaurant_id": "rest_abc123",
  "categories": [
    {
      "id": "cat_123",
      "name": "Starters",
      "display_order": 1,
      "items": [
        {
          "id": "item_456",
          "name": "Chicken 65",
          "description": "Crispy spiced chicken",
          "price": { "amount": 25000, "currency": "INR" },
          "discounted_price": null,
          "is_vegetarian": false,
          "is_available": true,
          "image_url": "https://cdn...",
          "tags": ["spicy", "bestseller"],
          "prep_time_minutes": 15
        }
      ]
    }
  ],
  "last_updated_at": "2025-01-15T10:00:00Z"
}

Cache-Control: max-age=300 (5 minutes)
ETag: "abc123hash"
Rate limit: 500 req/min per restaurant_id
```

---

### 5.4 Cart (Session-Based, Redis-Backed)

```
GET /v1/cart
Response 200: { "cart_id": "...", "restaurant_id": "...", "items": [...], "subtotal": {...} }

PUT /v1/cart/items/{menu_item_id}
Body: { "quantity": 2, "customizations": "no onions" }
Response 200: { "cart": {...} }
Note: Replaces item quantity. quantity=0 removes item.

DELETE /v1/cart
Response 204: Clears entire cart

POST /v1/cart/validate-coupon
Body: { "coupon_code": "FIRST50" }
Response 200: { "valid": true, "discount": {...}, "final_amount": {...} }
Response 422: { "error": { "code": "COUPON_MINIMUM_ORDER_NOT_MET", ... } }
```

---

### 5.5 Order Placement (Critical Path)

```
POST /v1/orders
Headers:
  Authorization: Bearer {access_token}
  Idempotency-Key: {client-generated UUID}     ← REQUIRED

Body:
{
  "cart_id": "cart_abc",
  "delivery_address_id": "addr_123",
  "payment_method": "CARD",
  "payment_token": "tok_stripe_xyz",
  "coupon_code": "FIRST50",
  "special_instructions": "Please ring the bell twice",
  "scheduled_for": null
}

Response 202 Accepted:
{
  "order_id": "ord_789xyz",
  "status": "PAYMENT_PENDING",
  "estimated_delivery_time": "2025-01-15T13:15:00Z",
  "tracking_url": "/v1/orders/ord_789xyz/tracking"
}

Response 409 Conflict (duplicate idempotency key):
{
  "error": {
    "code": "ORDER_ALREADY_EXISTS",
    "message": "An order with this idempotency key already exists",
    "existing_order_id": "ord_789xyz"
  }
}
```

**Idempotency Contract:**
- The `Idempotency-Key` is the client-generated UUID.
- If the same key is received within 24 hours, the original response is returned (not a new order).
- After 24 hours, the key expires and can be reused.
- This prevents duplicate orders from network retries.

---

### 5.6 Order Tracking (Server-Sent Events)

```
GET /v1/orders/{order_id}/tracking
Headers:
  Authorization: Bearer {access_token}
  Accept: text/event-stream

Response (SSE stream):
data: {"status": "PAYMENT_CONFIRMED", "timestamp": "2025-01-15T12:35:00Z"}

data: {"status": "RESTAURANT_ACCEPTED", "timestamp": "2025-01-15T12:37:00Z", "estimated_prep_minutes": 20}

data: {"status": "DELIVERY_PARTNER_ASSIGNED", "timestamp": "2025-01-15T12:38:00Z",
       "partner": {"name": "Ravi K.", "vehicle": "Motorbike", "rating": 4.7},
       "partner_location": {"lat": 12.9716, "lng": 77.5946},
       "eta_minutes": 28}

data: {"status": "PICKED_UP", "timestamp": "2025-01-15T12:58:00Z",
       "partner_location": {"lat": 12.9750, "lng": 77.5980},
       "eta_minutes": 12}

data: {"status": "DELIVERED", "timestamp": "2025-01-15T13:10:00Z"}

event: close
data: {}
```

**SSE vs WebSocket decision:**
- SSE is unidirectional (server → client) — sufficient for order tracking
- WebSocket is bidirectional — needed only if customer sends location updates
- SSE is simpler, works with HTTP/2 multiplexing, and reconnects automatically
- WebSocket used for live chat (if implemented in V3)

---

### 5.7 Order Management

```
GET /v1/orders/{order_id}
Response 200: Full order details including current status, items, payment, partner info

GET /v1/orders
  Query: status, cursor, limit
Response 200: Paginated order history

POST /v1/orders/{order_id}/cancel
Body: { "reason": "Ordered by mistake" }
Response 202: { "order_id": "...", "status": "CANCELLING", "refund_expected_by": "2025-01-18" }
Response 422: { "error": { "code": "ORDER_CANCELLATION_NOT_ALLOWED",
                            "message": "Cannot cancel order in PICKED_UP state" } }

POST /v1/orders/{order_id}/reorder
Response 202: New order created with same items (new idempotency key required)
```

---

### 5.8 Reviews

```
POST /v1/orders/{order_id}/review
Body:
{
  "restaurant_rating": 4,
  "delivery_rating": 5,
  "review_text": "Food was great, delivery was super fast",
  "tags": ["great packaging", "hot food"]
}
Response 201: { "review_id": "...", "visible": false }

Note: Review only allowed when order.status = DELIVERED
Note: One review per order; second POST returns 409
```

---

## 6. Restaurant Partner APIs

```
POST /v1/restaurant/auth/login
Body: { "email": "...", "password": "..." }
Response 200: { "access_token": "...", "restaurant_id": "..." }

GET /v1/restaurant/orders?status=RESTAURANT_NOTIFIED&cursor=...
Response 200: Paginated list of incoming orders

PUT /v1/restaurant/orders/{order_id}/accept
Body: { "estimated_prep_minutes": 25 }
Response 200: { "order_id": "...", "status": "RESTAURANT_ACCEPTED" }

PUT /v1/restaurant/orders/{order_id}/reject
Body: { "reason": "Item unavailable" }
Response 200: { "order_id": "...", "status": "RESTAURANT_REJECTED" }

PUT /v1/restaurant/orders/{order_id}/ready
Response 200: { "order_id": "...", "status": "FOOD_PREPARED" }

PUT /v1/restaurant/menu/items/{item_id}
Body: { "is_available": false }
Response 200: Updated menu item
Note: Triggers async search index update

GET /v1/restaurant/availability
Response 200: { "is_open": true, "estimated_prep_time": 20 }

PUT /v1/restaurant/availability
Body: { "is_open": false, "reason": "Rush hour — temporary closure" }
Response 200: Updated availability
```

---

## 7. Delivery Partner APIs

```
POST /v1/delivery/auth/send-otp
Body: { "phone": "..." }

POST /v1/delivery/auth/verify-otp
Body: { "phone": "...", "otp": "..." }
Response 200: { "access_token": "...", "partner_id": "..." }

PUT /v1/delivery/status
Body: { "is_online": true }
Response 200: Partner online status updated

PUT /v1/delivery/location
Body: { "lat": 12.9716, "lng": 77.5946, "bearing": 270, "speed_kmh": 25 }
Response 204: No Content
Note: High frequency — called every 5 seconds by app
Note: Rate limit: 60 req/min per partner (12/min is normal; limit allows bursts)

GET /v1/delivery/assignments/pending
Response 200: List of available delivery assignments

PUT /v1/delivery/assignments/{assignment_id}/accept
Response 200: Assignment accepted, trip details returned

PUT /v1/delivery/assignments/{assignment_id}/reject
Body: { "reason": "Too far" }
Response 200: Assignment rejected (partner gets another)

PUT /v1/delivery/trips/{trip_id}/pickup
Body: { "otp_verified": true }
Response 200: Food picked up, status updated

PUT /v1/delivery/trips/{trip_id}/delivered
Body: { "lat": 12.9800, "lng": 77.6000, "pod_image_url": "..." }
Response 200: Order delivered, trip complete

GET /v1/delivery/trips/{trip_id}
Response 200: Trip details including route, earnings, status
```

---

## 8. Admin APIs (Internal)

```
PUT /v1/admin/restaurants/{id}/status
Body: { "status": "APPROVED", "notes": "Verification complete" }

POST /v1/admin/orders/{id}/force-cancel
Body: { "reason": "...", "refund_amount": {...} }

GET /v1/admin/cities/{city_id}/analytics
Response 200: Real-time city metrics

PUT /v1/admin/surge-pricing
Body: { "city_id": "...", "multiplier": 1.5, "valid_until": "..." }
```

---

## 9. Rate Limiting Strategy

| Client Type | Endpoint | Limit | Window |
|------------|---------|-------|--------|
| Unauthenticated | GET /search | 50 req/min | Per IP |
| Customer | GET /search, GET /menu | 200 req/min | Per user |
| Customer | POST /orders | 5 req/min | Per user (prevents order spam) |
| Customer | POST /auth/send-otp | 3 req/hour | Per phone (prevents OTP abuse) |
| Delivery Partner | PUT /delivery/location | 60 req/min | Per partner |
| Restaurant | PUT /restaurant/orders/* | 100 req/min | Per restaurant |
| Admin | All | 1000 req/min | Per admin user |

Rate limit response:
```
HTTP 429 Too Many Requests
Headers:
  X-RateLimit-Limit: 5
  X-RateLimit-Remaining: 0
  X-RateLimit-Reset: 1705317960
  Retry-After: 60
```

---

## 10. Idempotency Strategy

| Endpoint | Idempotency | Method |
|----------|------------|--------|
| POST /orders | Required — client-supplied key | DB unique constraint on idempotency_key |
| POST /payment (internal) | Required — order_id | DB unique constraint on order_id |
| PUT /orders/*/accept | Natural — idempotent PUT semantics | State machine rejects invalid transitions |
| PUT /delivery/location | Natural — last-write-wins | Redis GEOADD overwrites |
| POST /orders/*/cancel | Protected — state check | Only CANCELLABLE states can be cancelled |

---

## 11. API Evolution and Backward Compatibility

### Adding Fields
New fields can be added to responses at any time — clients must ignore unknown fields (Postel's Law). Never remove a field from v1 without creating v2 and a migration period.

### Changing Field Types
Never change a field type in the same API version. Create a new version.

### Deprecation Process
1. Add `Deprecation` and `Sunset` headers to deprecated endpoints
2. Monitor usage of deprecated endpoints in analytics
3. Notify registered developers 6 months before sunset
4. Maintain deprecated endpoint for 12 months after sunset announcement

---

## 12. Tradeoffs

| Decision | Benefit | Cost |
|----------|---------|------|
| SSE for tracking instead of WebSocket | Simpler, HTTP/2 compatible, auto-reconnect | Cannot push from client to server |
| 202 Accepted for order placement | Accurate — saga is async | Client must poll or use SSE to get final status |
| Cursor pagination | Stable, efficient for append-heavy data | Cannot jump to page 5 directly |
| Idempotency key required for orders | Prevents duplicate orders on retry | Adds client-side complexity |
| URI versioning | Visible, easy to route | URL verbosity |

---

## Interview-Level Discussion Points

1. **Why is the Idempotency-Key required for POST /orders?** Mobile networks are unreliable. The client may not know if the server received the request. Without an idempotency key, a retry creates a duplicate order. With it, the server recognizes the retry and returns the existing order.

2. **How does the SSE connection survive a server-side pod restart?** The client will receive a connection closed event and reconnect. The server uses the `Last-Event-ID` header from the client to resume the stream from where it left off. Active order state is stored in Redis — any pod can pick up the stream.

3. **Why do you return 202 instead of waiting for payment confirmation before responding?** Payment processing can take 1–3 seconds (gateway call). A synchronous wait would tie up a server thread. 202 allows the server to respond immediately and process the saga asynchronously. The client tracks status via SSE.

4. **How do you handle API versioning for the mobile app when you can't force updates?** You must maintain v1 until the vast majority of active users have upgraded. Analytics on API version usage (via request logging) drives the decision to sunset v1. Typically 12–18 months.

5. **What happens if the Idempotency-Key header is missing on POST /orders?** Return 400 Bad Request with error code `IDEMPOTENCY_KEY_REQUIRED`. Never silently process without it — the risk of duplicate orders is too high.
