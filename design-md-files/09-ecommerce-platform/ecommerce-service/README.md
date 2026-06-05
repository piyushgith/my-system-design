# ecommerce-service — MVP (Phase 0)

Modular-monolith MVP for the e-commerce platform. Implements the Phase 0 scope
from [docs/15-implementation-roadmap.md](../docs/15-implementation-roadmap.md):
catalog browsing + search, email/password auth, Redis cart, COD checkout, order
history, and an admin surface for product/order management.

## Stack

- Spring Boot 3.2 / Java 21
- PostgreSQL (system of record) — Flyway migrations
- Redis (cart storage)
- JWT auth (stateless), BCrypt password hashing

Deliberately **not** in the MVP (see roadmap): Kafka, Elasticsearch, payments,
multi-seller, S3 images, inventory service. Search is Postgres full-text.

## Run

```bash
# 1. Dependencies
docker run -d --name ecom-pg    -e POSTGRES_DB=ecommerce -e POSTGRES_USER=ecommerce -e POSTGRES_PASSWORD=ecommerce -p 5432:5432 postgres:16
docker run -d --name ecom-redis -p 6379:6379 redis:7

# 2. App (Flyway runs V1–V4 on startup, seeding demo catalog)
mvn spring-boot:run
```

App listens on `:8080`. Override via env: `DB_USER`, `DB_PASSWORD`, `REDIS_HOST`,
`REDIS_PORT`, `JWT_SECRET`.

### Becoming an admin

No admin is seeded (we don't ship a fake password hash). Register, then promote:

```sql
UPDATE users SET role = 'ADMIN' WHERE email = 'you@example.com';
```

Re-login to get a token carrying the `ADMIN` role.

## API

Money is in **minor units** (paise/cents). All non-public routes need
`Authorization: Bearer <token>`.

### Auth — public
| Method | Path | Body |
|---|---|---|
| POST | `/v1/auth/register` | `{name,email,password}` → token |
| POST | `/v1/auth/login` | `{email,password}` → token |
| GET  | `/v1/users/me` | — |

### Catalog — public read
| Method | Path | Notes |
|---|---|---|
| GET | `/v1/categories` | all categories |
| GET | `/v1/products?categoryId=&q=&page=&size=` | `q` ⇒ full-text search |
| GET | `/v1/products/{id}` | product detail |

### Cart — auth (Redis)
| Method | Path | Body |
|---|---|---|
| GET    | `/v1/cart` | — |
| POST   | `/v1/cart/items` | `{productId,quantity}` |
| PUT    | `/v1/cart/items/{productId}` | `{quantity}` (0 removes) |
| DELETE | `/v1/cart/items/{productId}` | — |
| DELETE | `/v1/cart` | clear |

### Orders — auth
| Method | Path | Body |
|---|---|---|
| POST | `/v1/orders/checkout` | `{shippingAddress,idempotencyKey}` — converts cart → COD order, decrements stock, clears cart |
| GET  | `/v1/orders?page=&size=` | order history |
| GET  | `/v1/orders/{id}` | detail |
| POST | `/v1/orders/{id}/cancel` | cancel (PLACED/CONFIRMED only); restocks |

### Admin — `ROLE_ADMIN`
| Method | Path | Body |
|---|---|---|
| POST  | `/v1/admin/categories` | `{name,slug}` |
| POST  | `/v1/admin/products` | `{categoryId,title,description,priceAmount,currency,stockQuantity,imageUrl}` |
| PATCH | `/v1/admin/products/{id}` | partial product update |
| GET   | `/v1/admin/orders?status=&page=&size=` | all orders |
| PATCH | `/v1/admin/orders/{id}/status` | `{status}` — drives the lifecycle state machine |

## Key MVP design choices

- **Stock on the product row** (no inventory service yet). Oversell is prevented
  by an optimistic-lock `@Version` on `Product`: concurrent checkouts that race
  on the same row get a `409 CONFLICT` and retry. Roadmap V2 replaces this with
  Redis atomic counters.
- **Idempotent checkout** via `orders.idempotency_key` unique constraint —
  replays return the original order instead of double-charging stock.
- **Order lifecycle** is a guarded state machine (`OrderStatus.canTransitionTo`),
  so invalid admin transitions are rejected (`422`).
- **Cart in Redis** keyed `cart:{userId}`, TTL 7 days; prices/titles resolved
  from Postgres at read time so the cart always reflects live catalog pricing.
- **Search** uses a Postgres generated `tsvector` column + GIN index
  (`plainto_tsquery`) — good enough until catalog size justifies Elasticsearch.
```
