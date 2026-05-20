# 04 — API Design: Ride-Sharing Platform

---

## Objective

Design the complete API surface for the ride-sharing platform, covering REST endpoints, WebSocket protocol, rate limiting, versioning, pagination, idempotency, error standards, and the high-frequency driver location update endpoint. This API must serve mobile clients on unreliable networks and web clients alike.

---

## 1. API Design Principles

| Principle | Implementation |
|---|---|
| REST for CRUD and transactional operations | Standard HTTP verbs, resource-based URLs |
| WebSocket for real-time push | Server-initiated driver location updates to rider |
| SSE as fallback | For environments where WebSocket is blocked |
| gRPC for internal service communication | Matching → Location Service (high-frequency, low-latency) |
| Versioning via path prefix | /v1/, /v2/ — allows parallel version support |
| Idempotency via client-provided keys | All mutating POST operations accept Idempotency-Key header |
| Pagination via cursor (not offset) | Cursor-based pagination for stable results under concurrent writes |
| Explicit error codes | Application-level error codes beyond HTTP status codes |
| Hypermedia for state guidance | HATEOAS-lite: include allowed next actions in response |

---

## 2. Versioning Strategy

**Path-based versioning:** `/v1/trips`, `/v2/trips`

Rationale over header-based versioning:
- Path-based is visible in logs, easy to route at gateway level
- Header-based is cleaner but invisible in browser address bar and harder to cache by CDN
- Query-param versioning (`?version=2`) pollutes URLs and breaks caching

**Deprecation policy:**
- New version published → old version deprecated with `Sunset` header
- Deprecated version maintained for minimum 12 months
- Breaking change definition: removing a field, changing a field type, removing an endpoint

---

## 3. Authentication

All endpoints require a valid JWT in the `Authorization: Bearer <token>` header, except:
- `POST /v1/auth/otp/request`
- `POST /v1/auth/otp/verify`
- `POST /v1/auth/token/refresh`

JWT claims include: `sub` (user_id), `role` (RIDER/DRIVER/ADMIN), `city_id`, `exp`

Tokens: Access token TTL = 15 minutes, Refresh token TTL = 30 days (rotation on use).

---

## 4. Core API Endpoints

### 4.1 Authentication Service APIs

```
POST   /v1/auth/otp/request
       Body: { phone_number }
       Response: { otp_request_id, expires_in_seconds: 120 }

POST   /v1/auth/otp/verify
       Body: { otp_request_id, otp_code }
       Response: { access_token, refresh_token, user_type, user_id }

POST   /v1/auth/token/refresh
       Body: { refresh_token }
       Response: { access_token, refresh_token }

POST   /v1/auth/logout
       Header: Authorization: Bearer <token>
       Response: 204 No Content
```

---

### 4.2 Rider Service APIs

```
POST   /v1/riders
       Body: { phone_number, full_name, email? }
       Idempotency-Key: <client-generated UUID>
       Response: 201 { rider_id, status: "ACTIVE" }

GET    /v1/riders/me
       Response: { rider_id, full_name, rating, total_trips, ... }

PATCH  /v1/riders/me
       Body: { full_name?, email?, profile_photo_url? }
       Response: 200 { updated rider }

GET    /v1/riders/me/trips?cursor=<encoded>&limit=20
       Response: { trips: [...], next_cursor: <encoded>, has_more: true }

GET    /v1/riders/me/trips/{trip_id}
       Response: { trip detail with fare breakdown }
```

---

### 4.3 Pricing / Fare Estimate APIs

```
POST   /v1/fare-estimates
       Body: {
         pickup_lat, pickup_lng,
         destination_lat, destination_lng,
         vehicle_type: "ECONOMY" | "PREMIUM" | "SUV"
       }
       Response: {
         quote_id,
         vehicle_type,
         fare_min: 145,
         fare_max: 175,
         currency: "INR",
         surge_multiplier: 1.8,
         surge_active: true,
         estimated_duration_min: 22,
         estimated_distance_km: 8.3,
         expires_at: "2026-05-17T10:15:00Z",
         breakdown: {
           base_fare: 40,
           distance_fare: 83,
           time_fare: 22,
           surge_premium: 29,
           platform_fee: 10
         }
       }
```

**Rate limiting:** 30 fare estimates per minute per rider (prevent scraping surge data).

---

### 4.4 Trip Management APIs (Rider-Facing)

```
POST   /v1/trips
       Idempotency-Key: <client-generated UUID>
       Body: {
         quote_id,           // fare quote; validates and locks surge
         pickup_lat, pickup_lng, pickup_address,
         destination_lat, destination_lng, destination_address,
         vehicle_type,
         payment_method_id
       }
       Response: 201 {
         trip_id,
         status: "REQUESTED",
         estimated_fare: { min, max },
         surge_multiplier: 1.8,
         pickup_address,
         destination_address,
         _links: {
           self: "/v1/trips/{trip_id}",
           cancel: "/v1/trips/{trip_id}/cancel",
           stream: "wss://ws.api.example.com/v1/trips/{trip_id}/stream"
         }
       }

GET    /v1/trips/{trip_id}
       Response: {
         trip_id, status,
         driver: { driver_id, name, photo, rating, vehicle: { make, model, color, plate } },
         eta_minutes,
         fare_breakdown,
         pickup_address, destination_address,
         otp,              // 4-digit code shown to rider
         started_at, ended_at
       }

POST   /v1/trips/{trip_id}/cancel
       Idempotency-Key: <UUID>
       Body: { reason?: "CHANGED_MIND" | "DRIVER_LATE" | "WRONG_PICKUP" }
       Response: {
         trip_id, status: "CANCELLED",
         cancellation_fee: 0 | 30,   // INR, if driver already dispatched
         reason
       }
```

**Business rule on cancellation fee:** Free if cancelled before driver_matched. ₹30 fee if driver is already en route (DRIVER_MATCHED status). ₹50 if driver has arrived (DRIVER_ARRIVED status). Enforced in Trip Service, not API layer.

---

### 4.5 Trip Management APIs (Driver-Facing)

```
POST   /v1/driver/trips/{trip_id}/accept
       Idempotency-Key: <UUID>
       Response: {
         trip_id, status: "DRIVER_MATCHED",
         rider: { name, photo, rating, otp },
         pickup: { lat, lng, address },
         destination_address,
         estimated_fare_share,
         navigation_url   // deeplink to Google Maps
       }

POST   /v1/driver/trips/{trip_id}/reject
       Body: { reason?: "TOO_FAR" | "GOING_HOME" | "OTHER" }
       Response: 204 No Content

POST   /v1/driver/trips/{trip_id}/arrive
       Idempotency-Key: <UUID>
       Response: { trip_id, status: "DRIVER_ARRIVED", wait_time_started_at }

POST   /v1/driver/trips/{trip_id}/start
       Idempotency-Key: <UUID>
       Body: { otp: "4821" }
       Response: {
         trip_id, status: "IN_PROGRESS",
         destination: { lat, lng, address },
         navigation_url
       }

POST   /v1/driver/trips/{trip_id}/complete
       Idempotency-Key: <UUID>
       Body: { final_lat, final_lng }
       Response: {
         trip_id, status: "COMPLETED",
         fare: { total: 165, driver_share: 132, platform_fee: 33 },
         distance_km: 8.7,
         duration_min: 24
       }
```

---

### 4.6 Driver Availability and Location APIs

```
POST   /v1/driver/availability/online
       Body: { vehicle_id, city_id }
       Response: { status: "AVAILABLE", city_id, timestamp }

POST   /v1/driver/availability/offline
       Response: { status: "OFFLINE", timestamp }

POST   /v1/driver/location
       Content-Type: application/json
       Body: {
         lat: 19.0760,
         lng: 72.8777,
         heading: 270,
         speed_kmh: 32.5,
         accuracy_meters: 8,
         timestamp: "2026-05-17T10:00:00.000Z"
       }
       Response: 204 No Content
```

**Location update rate limiting:** This is the highest-frequency endpoint.
- Max 1 update per 2 seconds per driver (enforced by API Gateway)
- Batching allowed: driver SDK can batch up to 5 positions in one request to reduce overhead on poor networks
- Compression: GZIP required on requests
- UDP alternative: At Uber scale, the driver SDK may use UDP directly to a UDP ingestion service for lower overhead, accepting that some packets may be lost

**Important design note:** The driver location endpoint returns 204 immediately — it does NOT wait for Redis write confirmation. This is write-and-forget at the API layer. If the write fails, the driver's next ping will update the position. This is acceptable given 4-second update frequency.

---

### 4.7 Rating APIs

```
POST   /v1/trips/{trip_id}/ratings
       Body: {
         score: 5,
         comment: "Very smooth ride",
         tags: ["GOOD_CONVERSATION", "CLEAN_CAR"]
       }
       Response: 201 { rating_id, score, trip_id }
       Note: Rider rates driver; endpoint scoped by JWT role
       Rating window: Only allowed within 24 hours of trip completion
```

---

## 5. WebSocket Protocol for Real-Time Tracking

### Connection Establishment

```
Client → Server: GET /v1/trips/{trip_id}/stream
                 Upgrade: websocket
                 Connection: Upgrade
                 Sec-WebSocket-Protocol: trip-tracking-v1
                 Authorization: Bearer <access_token>

Server → Client: 101 Switching Protocols
```

**Authentication on WebSocket:** JWT is passed in the initial HTTP Upgrade request header. After upgrade, the server maintains session; no per-message auth needed.

### Message Types (Server → Client)

```
// Driver location update (pushed every 2-4 seconds during trip)
{
  "type": "DRIVER_LOCATION",
  "data": {
    "driver_id": "uuid",
    "lat": 19.0760,
    "lng": 72.8777,
    "heading": 270,
    "timestamp": "2026-05-17T10:00:00.000Z"
  }
}

// Trip status change
{
  "type": "TRIP_STATUS",
  "data": {
    "trip_id": "uuid",
    "status": "DRIVER_ARRIVED",
    "timestamp": "2026-05-17T10:05:00.000Z",
    "otp": "4821"   // included only on DRIVER_ARRIVED
  }
}

// ETA update
{
  "type": "ETA_UPDATE",
  "data": {
    "eta_minutes": 3,
    "updated_at": "2026-05-17T10:00:30.000Z"
  }
}

// Heartbeat (server → client every 30s to keep connection alive)
{
  "type": "PING",
  "data": { "server_time": "2026-05-17T10:01:00.000Z" }
}
```

### Message Types (Client → Server)

```
// Client pong response
{ "type": "PONG" }

// Client requests current trip state (e.g., after reconnect)
{ "type": "SYNC_REQUEST" }
```

### Reconnection Strategy

- Client-side: exponential backoff starting at 1 second, max 30 seconds
- On reconnect, client sends `SYNC_REQUEST`; server responds with current trip state + last known driver position
- If client disconnects while trip is IN_PROGRESS, trip continues — WebSocket is display-only; it does not control trip state

### SSE Fallback

For clients where WebSocket is blocked (corporate proxies, some browsers):

```
GET /v1/trips/{trip_id}/events
    Accept: text/event-stream
    Authorization: Bearer <token>

// Server response stream:
event: DRIVER_LOCATION
data: {"lat": 19.0760, "lng": 72.8777, "heading": 270}

event: TRIP_STATUS
data: {"status": "DRIVER_ARRIVED", "otp": "4821"}
```

SSE is simpler but unidirectional. No PONG required; HTTP keepalive handles connection.

---

## 6. Pagination Strategy

**Cursor-based pagination (not offset):**

```
GET /v1/riders/me/trips?limit=20&cursor=eyJpZCI6IjEyMyIsImNyZWF0ZWRfYXQiOiIyMDI2LTA1LTE3In0=

Response:
{
  "trips": [...],
  "pagination": {
    "limit": 20,
    "next_cursor": "eyJpZCI6IjEwMyIs...",
    "has_more": true,
    "total_count": null   // intentionally omitted — COUNT(*) is expensive
  }
}
```

**Why cursor over offset:**
- Offset pagination breaks when items are inserted/deleted during pagination (user sees duplicates or skips items)
- Cursor uses a stable anchor (last seen ID + timestamp) which always returns consistent results
- Offset requires `OFFSET n` which causes full table scans on large datasets; cursor uses indexed range scan

The cursor is a Base64-encoded JSON: `{"id": "last_trip_id", "created_at": "2026-05-17T10:00:00Z"}`. The server decodes this and queries `WHERE created_at < :cursor_time AND id < :cursor_id`.

---

## 7. Idempotency Strategy

All state-changing operations (POST) accept an `Idempotency-Key` header.

**Flow:**
1. Client sends `POST /v1/trips` with `Idempotency-Key: <UUID>`
2. Server checks Redis: `exists key = "idempotency:{UUID}"`
3. If key exists: return cached response (200 or 201 as appropriate)
4. If not: process request, store result in Redis with key and TTL = 24 hours
5. Return result to client

**Critical for:**
- Trip creation (network timeout could cause double booking)
- Payment capture (must never double-charge)
- Trip status transitions (driver marking trip complete can be retried safely)

**Not needed for:**
- GET requests (idempotent by HTTP spec)
- Location updates (stale duplicate is acceptable)

---

## 8. Error Response Standard

```json
{
  "error": {
    "code": "TRIP_ALREADY_ACTIVE",
    "message": "You already have an active trip in progress.",
    "details": {
      "active_trip_id": "uuid-here"
    },
    "request_id": "req_01J3XKYZ...",
    "timestamp": "2026-05-17T10:00:00Z"
  }
}
```

### Application Error Code Catalog

| HTTP Status | Error Code | Description |
|---|---|---|
| 400 | INVALID_LOCATION | Pickup or destination coordinates are outside service area |
| 400 | EXPIRED_QUOTE | Fare quote has expired; request a new estimate |
| 400 | INVALID_OTP | OTP does not match; trip start denied |
| 400 | INVALID_STATE_TRANSITION | e.g., trying to complete a cancelled trip |
| 401 | TOKEN_EXPIRED | JWT access token expired; refresh required |
| 401 | INVALID_TOKEN | JWT signature invalid |
| 403 | DRIVER_SUSPENDED | Driver account is suspended |
| 403 | INSUFFICIENT_PERMISSIONS | Rider trying to use driver endpoint |
| 404 | TRIP_NOT_FOUND | Trip ID does not exist or belongs to another user |
| 409 | TRIP_ALREADY_ACTIVE | Rider already has an active trip |
| 409 | DRIVER_ALREADY_MATCHED | Driver already on another trip |
| 409 | IDEMPOTENCY_CONFLICT | Same Idempotency-Key with different request body |
| 422 | NO_DRIVERS_AVAILABLE | Matching timed out; no drivers found in area |
| 429 | RATE_LIMIT_EXCEEDED | Too many requests; retry after `Retry-After` header value |
| 503 | MATCHING_UNAVAILABLE | Matching service temporarily degraded |

---

## 9. Rate Limiting Strategy

| Endpoint | Limit | Window | Scope |
|---|---|---|---|
| POST /v1/trips | 5 requests | 1 minute | Per rider |
| POST /v1/fare-estimates | 30 requests | 1 minute | Per rider |
| POST /v1/driver/location | 30 requests | 1 minute | Per driver |
| POST /v1/auth/otp/request | 5 requests | 10 minutes | Per phone number |
| GET /v1/trips/{id} | 60 requests | 1 minute | Per rider |
| POST /v1/driver/trips/{id}/accept | 10 requests | 1 minute | Per driver |
| WebSocket connections | 2 concurrent | Per rider | Per user |

**Implementation:** Token bucket algorithm in Redis. Each request deducts a token; tokens replenish at a fixed rate. Returns `429 Too Many Requests` with `Retry-After: <seconds>` header.

---

## 10. API Evolution Strategy

### Backward Compatible Changes (no version bump)
- Adding new optional fields to response bodies
- Adding new optional query parameters
- Adding new endpoints
- Adding new enum values (clients must handle unknown enums gracefully)

### Breaking Changes (require new version)
- Removing or renaming fields
- Changing field types
- Removing endpoints
- Changing authentication requirements
- Changing pagination schema

### Field Deprecation Process
1. Add deprecated field with `X-Deprecated-Field: field_name` response header
2. Include `deprecated_at` in field documentation
3. After 6 months, field returns null but key still present (grace period)
4. After 12 months, field removed in next major version

---

## 11. Internal gRPC APIs

Between microservices, use gRPC for performance-critical paths:

### Location Service gRPC

```
service LocationService {
  rpc GetNearbyDrivers (NearbyDriverRequest) returns (NearbyDriverResponse);
  rpc GetDriverPosition (DriverPositionRequest) returns (DriverPosition);
  rpc GetSurgeMultiplier (SurgeRequest) returns (SurgeResponse);
}

message NearbyDriverRequest {
  double lat = 1;
  double lng = 2;
  double radius_km = 3;
  VehicleType vehicle_type = 4;
  string city_id = 5;
  int32 max_results = 6;
}

message NearbyDriverResponse {
  repeated DriverCandidate drivers = 1;
}

message DriverCandidate {
  string driver_id = 1;
  double lat = 2;
  double lng = 3;
  double distance_km = 4;
  int32 eta_seconds = 5;
  float rating = 6;
}
```

**Why gRPC here:** Matching Service calls Location Service up to 3 times per matching attempt (initial + two fallback radius expansions), under a 3-second budget. HTTP/1.1 REST adds 10–20ms overhead per call due to connection setup. gRPC over HTTP/2 with persistent connections adds ~1ms. At 139 matching requests/sec × 3 calls each = 417 gRPC calls/sec — the savings compound.

---

## Interview-Level Discussion Points

- **Why not use polling instead of WebSocket for driver tracking?** Short polling every 2 seconds = 200K concurrent riders × 0.5 requests/sec = 100K requests/sec just for location updates. WebSocket reduces this to 200K persistent connections with server-push, no repeated HTTP overhead. The tradeoff is WebSocket servers are stateful and harder to scale; but the scale reduction is 100x in request volume.
- **How do you handle the case where a rider's app goes to background and the WebSocket disconnects?** The trip continues; WebSocket is display-only. When the rider's app comes back to foreground, it reconnects and requests a SYNC_REQUEST to get current state. The driver's progress is not affected by rider connectivity.
- **Why idempotency key on trip completion (driver POST)?** Poor mobile networks. The driver taps "End Trip" and the request succeeds on the server, but the response never reaches the driver (network drop). Driver retries. Without idempotency, this creates two "trip completed" events → double payment charge. With idempotency key (trip_id), the second attempt returns the cached success response without re-processing.
- **How does the OTP prevent fraud?** Without OTP, a malicious rider near a driver could start someone else's trip by knowing the trip_id. OTP is shown only in the rider's authenticated app and must be verbally communicated to the driver who types it in. The server validates it server-side before allowing the DRIVER_ARRIVED→IN_PROGRESS transition.
- **Why limit fare estimate API to 30/minute?** Without rate limiting, a competitor or scraper could query your surge multiplier continuously to track your pricing algorithm, build competitive intelligence, or throttle your Pricing Service. Surge data has commercial value; it should be rate-limited like any sensitive API.
