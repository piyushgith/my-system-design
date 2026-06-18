# Ride Sharing Service (V1)

Spring Boot modular monolith — MVP core plus V1 features with **mocked external services** (no real Redis, Kafka, FCM, or Razorpay).

## Quick start

```bash
./mvnw spring-boot:run
```

H2 console: http://localhost:8080/h2-console (JDBC URL `jdbc:h2:mem:rideshare`)

## API documentation (Swagger UI)

Interactive API docs are provided by **springdoc-openapi** (`springdoc-openapi-starter-webmvc-ui` 3.x for Spring Boot 4).

| Resource | URL |
|----------|-----|
| Swagger UI | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |

### Using Swagger UI

1. Start the app: `./mvnw spring-boot:run`
2. Open http://localhost:8080/swagger-ui.html
3. Click **Authorize** (top right)
4. Enter **`rider`** or **`driver`** in the `X-Uid` field — one short value, no UUID needed
5. Click **Authorize**, then **Close**
6. Try any protected endpoint with **Try it out**

OTP endpoints (`POST /v1/auth/otp/*`) are public and do not require authorization.

## Dev authentication

There is no JWT in V1. Protected `/v1/**` endpoints use dev headers.

### Recommended (short)

| Header | Value | Who |
|--------|-------|-----|
| `X-Uid` | `rider` | Demo rider |
| `X-Uid` | `driver` | Demo driver |

Shortcuts map internally to the seeded demo users below. **`X-Role` is not required** when using `rider` or `driver`.

### Full UUID (curl / scripts)

| Header | Rider example | Driver example |
|--------|---------------|----------------|
| `X-Uid` | `22222222-2222-2222-2222-222222222222` | `33333333-3333-3333-3333-333333333333` |
| `X-Role` | `RIDER` | `DRIVER` |

When `X-Uid` is a UUID, **`X-Role` is required**.

### Legacy headers (still supported)

| Legacy header | Replaced by |
|---------------|-------------|
| `X-User-Id` | `X-Uid` |
| `X-User-Role` | `X-Role` |

### WebSocket tracking

Trip stream uses the same auth headers on the handshake:

```bash
wscat -c ws://localhost:8080/v1/trips/<trip_id>/stream \
  -H 'X-Uid: rider'
```

### Mock OTP (public)

| Endpoint | Notes |
|----------|-------|
| `POST /v1/auth/otp/request` | No auth headers |
| `POST /v1/auth/otp/verify` | Mock OTP is **`123456`** |

## Seeded demo users (Bangalore)

| Role | `X-Uid` shortcut | UUID | `X-Role` (UUID only) |
|------|------------------|------|----------------------|
| Rider | `rider` | `22222222-2222-2222-2222-222222222222` | `RIDER` |
| Driver | `driver` | `33333333-3333-3333-3333-333333333333` | `DRIVER` |

City ID: `11111111-1111-1111-1111-111111111111` (BLR)  
Vehicle ID: `44444444-4444-4444-4444-444444444444`

Sample coordinates: pickup `12.9716, 77.5946` (MG Road) → destination `12.9352, 77.6245` (Koramangala)

## V1 features

| Feature | Implementation |
|---------|----------------|
| Auto-matching | `MatchingOrchestrator` — offers nearest driver, **15s timeout**, up to **3 radius rounds**, **180s** total |
| WebSocket tracking | `ws://localhost:8080/v1/trips/{tripId}/stream` |
| Mock Redis GEO | `MockRedisLocationStore` — logs GEOADD/GEORADIUS |
| Mock Kafka | `MockKafkaDomainEventPublisher` — logs trip events |
| Mock FCM | `MockFcmNotificationStrategy` — logs push payloads |
| Mock Razorpay | `MockRazorpayPaymentStrategy` — captures with `rzp_mock_*` txn id |
| OSRM routing | Karnataka extract via Docker; **fallback to haversine** if OSRM is down |

## Happy-path flow

```bash
# 1. Driver online (near MG Road)
curl -X POST http://localhost:8080/v1/driver/availability/online \
  -H 'Content-Type: application/json' \
  -H 'X-Uid: driver' \
  -d '{"vehicleId":"44444444-4444-4444-4444-444444444444","cityId":"11111111-1111-1111-1111-111111111111","lat":12.971,"lng":77.594}'

# 2. Fare estimate
curl -X POST http://localhost:8080/v1/fare-estimates \
  -H 'Content-Type: application/json' \
  -H 'X-Uid: rider' \
  -d '{"pickupLat":12.9716,"pickupLng":77.5946,"destinationLat":12.9352,"destinationLng":77.6245,"vehicleType":"ECONOMY","cityId":"11111111-1111-1111-1111-111111111111"}'

# 3. Request trip → auto-offer sent to driver (check logs for [mock-fcm])
curl -X POST http://localhost:8080/v1/trips \
  -H 'Content-Type: application/json' \
  -H 'X-Uid: rider' \
  -H 'Idempotency-Key: trip-1' \
  -d '{"quoteId":"<quote_id>","pickupAddress":"MG Road","destinationAddress":"Koramangala"}'

# 4. Driver sees offer / accepts within 15s
curl http://localhost:8080/v1/driver/trips/offer \
  -H 'X-Uid: driver'

curl -X POST http://localhost:8080/v1/driver/trips/<trip_id>/accept \
  -H 'X-Uid: driver'

# 5. Rider WebSocket (wscat or browser)
# wscat -c ws://localhost:8080/v1/trips/<trip_id>/stream \
#   -H 'X-Uid: rider'
```

## OSRM (Bangalore / Karnataka)

```bash
mkdir -p osrm-data
curl -L -o osrm-data/karnataka.osm.pbf https://download.geofabrik.de/asia/india/karnataka-latest.osm.pbf
docker run -t -v "${PWD}/osrm-data:/data" ghcr.io/project-osrm/osrm-backend osrm-extract -p /opt/car.lua /data/karnataka.osm.pbf
docker run -t -v "${PWD}/osrm-data:/data" ghcr.io/project-osrm/osrm-backend osrm-partition /data/karnataka.osrm
docker run -t -v "${PWD}/osrm-data:/data" ghcr.io/project-osrm/osrm-backend osrm-customize /data/karnataka.osrm
docker compose -f docker-compose.osrm.yml up -d
```

`app.routing.backend=osrm-fallback` tries OSRM first, then haversine mock.

## Configuration (`application.properties`)

| Property | Default | Purpose |
|----------|---------|---------|
| `app.routing.backend` | `osrm-fallback` | OSRM + mock fallback |
| `app.location.backend` | `mock-redis` | In-memory store with Redis-style logs |
| `app.payment.backend` | `mock-razorpay` | Online payment mock |
| `app.notification.backend` | `mock-fcm` | Push notification mock |
| `app.events.backend` | `mock-kafka` | Event bus mock |
| `app.matching.offer-timeout-seconds` | `15` | Driver accept window |
| `app.matching.max-rounds` | `3` | Radius expansion rounds |
| `app.matching.total-timeout-seconds` | `180` | Cancel if no driver |
| `springdoc.swagger-ui.path` | `/swagger-ui.html` | Swagger UI |
| `springdoc.api-docs.path` | `/v3/api-docs` | OpenAPI JSON |
| `app.auth.mock-otp` | `123456` | Mock OTP for dev auth |

Runtime entity IDs use **UUID v7** via `Uuids.v7()`. Demo seed IDs stay fixed for curl examples.

## Database migrations (Flyway)

Schema is managed by Flyway (`src/main/resources/db/migration/`), not Hibernate auto-DDL.

| Profile | Database | Flyway |
|---------|----------|--------|
| default | H2 in-memory (`MODE=PostgreSQL`) | enabled |
| `postgres` | PostgreSQL | enabled |

```bash
# Local dev (H2 + Flyway — default)
./mvnw spring-boot:run

# PostgreSQL
./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

## Architecture modules

- `identity` — riders, drivers, mock OTP
- `location` — mock Redis GEO store
- `routing` — OSRM + fallback
- `matching` — auto-offer orchestrator + trip offers
- `pricing` — fare quotes
- `trip` — state machine + REST
- `payment` — mock Razorpay / cash
- `notification` — mock FCM
- `event` — mock Kafka
- `tracking` — WebSocket live updates

## Swapping mocks for real services later

Each external integration uses a **Strategy + resolver** pattern (`app.*.backend` property). Replace mock beans with real Redis/Kafka/Razorpay/FCM clients without changing trip or matching logic.
