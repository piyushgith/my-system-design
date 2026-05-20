# 04 — API Design: Stock Trading Order Book

## Objective

Define REST and WebSocket APIs for order submission, management, market data, and account queries. Establish idempotency, versioning, pagination, and error handling standards.

---

## API Layers

| Layer | Protocol | Consumers | Latency Target |
|-------|---------|-----------|----------------|
| Order Management API | REST (HTTPS) | Retail clients, internal services | < 10ms |
| FIX Gateway | FIX 4.2/4.4 | Institutional, market makers | < 1ms |
| Market Data API | WebSocket | All subscribers | < 5ms fan-out |
| Market Data REST | REST | Snapshot on connect | < 50ms |
| Account API | REST | Client portfolio view | < 100ms |

---

## REST API — Order Management

### Base URL

```
https://api.exchange.com/v1
```

### Versioning Strategy

URL-based major versioning (`/v1`, `/v2`). Minor, backward-compatible changes (new optional fields) do not increment version. Breaking changes (field removal, type changes) require new version with 6-month deprecation window.

---

### Submit Order

```
POST /v1/orders
```

**Request:**
```json
{
  "clientOrderId": "client-uuid-001",
  "symbol": "AAPL",
  "side": "BUY",
  "type": "LIMIT",
  "price": "180.50",
  "quantity": 100,
  "timeInForce": "GTC"
}
```

**Response 202 Accepted:**
```json
{
  "orderId": "system-uuid-abc",
  "clientOrderId": "client-uuid-001",
  "symbol": "AAPL",
  "status": "NEW",
  "submittedAt": "2024-01-15T09:30:00.123456789Z"
}
```

**Idempotency:** `clientOrderId` is client-supplied unique key. Duplicate submission returns the original response (idempotent). Enforced via Redis cache with 24-hour TTL.

**Error codes:**

| HTTP | Code | Meaning |
|------|------|---------|
| 400 | INVALID_SYMBOL | Symbol not found or not trading |
| 400 | INVALID_PRICE | Price not multiple of tick size |
| 400 | INVALID_QUANTITY | Quantity not multiple of lot size |
| 422 | INSUFFICIENT_BUYING_POWER | Risk check failed — not enough cash |
| 422 | POSITION_LIMIT_EXCEEDED | Would exceed max position limit |
| 409 | DUPLICATE_ORDER | clientOrderId already exists |
| 503 | MARKET_HALTED | Circuit breaker active |

---

### Cancel Order

```
DELETE /v1/orders/{orderId}
```

**Response 200:**
```json
{
  "orderId": "system-uuid-abc",
  "status": "CANCELLED",
  "cancelledAt": "2024-01-15T09:31:00.000Z"
}
```

**Concurrency:** Cancel and fill can race. If order fills before cancel reaches the engine, response returns `FILLED` status, not error.

---

### Modify Order (Replace)

```
PUT /v1/orders/{orderId}
```

**Request:**
```json
{
  "newPrice": "181.00",
  "newQuantity": 150
}
```

**Implementation note:** Implemented as cancel + resubmit internally. Order loses time priority. This is standard exchange behavior — clients must accept priority loss on modify.

---

### Get Order Status

```
GET /v1/orders/{orderId}
```

```
GET /v1/orders?symbol=AAPL&status=OPEN&page=0&size=20
```

**Pagination:** Cursor-based for active orders (volatile dataset — offset pagination produces gaps/duplicates). Offset-based acceptable for historical query.

**Cursor format:**
```json
{
  "orders": [...],
  "nextCursor": "eyJzdWJtaXR0ZWRBdCI6Ii4uLiIsIm9yZGVySWQiOiIuLi4ifQ==",
  "hasMore": true
}
```

---

### Get Trades / Execution History

```
GET /v1/trades?symbol=AAPL&from=2024-01-15T09:30:00Z&to=2024-01-15T16:00:00Z&page=0&size=50
```

Offset pagination acceptable — historical, stable dataset.

---

## REST API — Market Data (Snapshot)

### Best Bid/Ask (Level 1)

```
GET /v1/market-data/{symbol}/quote
```

```json
{
  "symbol": "AAPL",
  "bidPrice": "180.48",
  "bidQuantity": 500,
  "askPrice": "180.52",
  "askQuantity": 300,
  "lastTradePrice": "180.50",
  "lastTradeTime": "2024-01-15T09:31:00.000Z",
  "timestamp": "2024-01-15T09:31:00.100Z"
}
```

### Order Book Depth (Level 2)

```
GET /v1/market-data/{symbol}/orderbook?depth=10
```

```json
{
  "symbol": "AAPL",
  "bids": [
    { "price": "180.48", "quantity": 500, "orderCount": 3 },
    { "price": "180.47", "quantity": 1200, "orderCount": 7 }
  ],
  "asks": [
    { "price": "180.52", "quantity": 300, "orderCount": 2 },
    { "price": "180.53", "quantity": 800, "orderCount": 5 }
  ],
  "sequenceNumber": 10023456
}
```

`sequenceNumber` allows clients to detect missed updates and request a fresh snapshot.

---

## WebSocket API — Real-Time Market Data

### Connection

```
wss://stream.exchange.com/v1
```

### Subscribe

```json
{
  "action": "subscribe",
  "channels": ["quote.AAPL", "depth.AAPL", "trade.AAPL"]
}
```

### Level 1 Update Message

```json
{
  "channel": "quote.AAPL",
  "data": {
    "symbol": "AAPL",
    "bidPrice": "180.49",
    "askPrice": "180.51",
    "seq": 10023457
  },
  "ts": "2024-01-15T09:31:01.000Z"
}
```

### Trade Ticker Message

```json
{
  "channel": "trade.AAPL",
  "data": {
    "tradeId": "trade-uuid-xyz",
    "price": "180.50",
    "quantity": 100,
    "side": "BUY",
    "seq": 10023458
  },
  "ts": "2024-01-15T09:31:01.001Z"
}
```

### Gap Detection

Every message carries `seq` (sequence number per symbol). Client detects gaps (`seq` jumps). On gap: re-subscribe or request REST snapshot. WebSocket server does NOT buffer or replay — Kafka handles replay if needed.

---

## WebSocket API — Order Execution Reports

Clients receive execution reports via a separate authenticated WebSocket:

```
wss://api.exchange.com/v1/executions
```

```json
{
  "type": "EXECUTION_REPORT",
  "orderId": "system-uuid-abc",
  "clientOrderId": "client-uuid-001",
  "execType": "FILL",
  "fillPrice": "180.50",
  "fillQuantity": 100,
  "cumulativeQuantity": 100,
  "leavesQuantity": 0,
  "orderStatus": "FILLED",
  "ts": "2024-01-15T09:31:01.001Z"
}
```

---

## FIX Protocol (Institutional)

FIX 4.2/4.4 supported via QuickFIX/J gateway. Translates to internal `OrderEvent` format before entering risk check and ring buffer.

Key FIX message types:

| FIX Message | Type | Description |
|-------------|------|-------------|
| New Order Single (D) | 35=D | Submit new order |
| Order Cancel Request (F) | 35=F | Cancel existing order |
| Order Cancel/Replace (G) | 35=G | Modify price/quantity |
| Execution Report (8) | 35=8 | Fill / status notification |
| Order Cancel Reject (9) | 35=9 | Cancel rejected |

**Latency:** FIX gateway adds ~0.1ms overhead for parsing. Acceptable for institutional; not for HFT (HFT uses binary protocol, not FIX text).

---

## Error Handling Standards

All REST errors follow RFC 7807 Problem Details:

```json
{
  "type": "https://api.exchange.com/errors/insufficient-buying-power",
  "title": "Insufficient Buying Power",
  "status": 422,
  "detail": "Order requires $18,050 but only $5,000 available",
  "orderId": "system-uuid-abc",
  "requiredAmount": "18050.00",
  "availableAmount": "5000.00"
}
```

---

## Rate Limiting

| Endpoint | Limit | Window |
|----------|-------|--------|
| POST /orders | 100 req/sec | Per participant |
| DELETE /orders | 100 req/sec | Per participant |
| GET /orders | 500 req/sec | Per participant |
| GET /market-data | 1000 req/sec | Per IP |
| WebSocket connections | 10 concurrent | Per participant |

Rate limit headers returned on every response:
```
X-RateLimit-Limit: 100
X-RateLimit-Remaining: 87
X-RateLimit-Reset: 1705311061
```

---

## API Evolution Strategy

| Change Type | Strategy |
|-------------|---------|
| New optional field | Add to existing version, backward compatible |
| New required field | New API version with migration guide |
| Deprecated field | Mark in docs + Sunset header, remove after 6 months |
| Breaking type change | New version, parallel run during transition |
| New endpoint | Add to current version |
| Removed endpoint | Sunset header 6 months before removal |

**Sunset header:**
```
Sunset: Sat, 01 Jun 2024 00:00:00 GMT
Deprecation: Mon, 01 Jan 2024 00:00:00 GMT
Link: <https://api.exchange.com/v2/orders>; rel="successor-version"
```
