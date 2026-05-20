# Implementation Notes (Phase 0 MVP)

## Money storage

Design doc [`05-database-design.md`](05-database-design.md) uses `NUMERIC(20,4)`. The MVP implementation uses **`BIGINT` paise** per [`15-implementation-roadmap.md`](15-implementation-roadmap.md) non-negotiables. API responses expose rupees as `BigDecimal`; persistence is always integer paise.

## Databases

| Profile | Flyway location | Use |
|---------|-----------------|-----|
| default | `db/migration` | PostgreSQL (Docker Compose) |
| h2 / test | `db/migration-h2` | In-memory H2 for `mvn test` |

PostgreSQL includes a deferred double-entry balance trigger (`V8`). H2 relies on application-layer validation only.

## Phase 0 fixes (post-MVP audit)

- Customer creation restricted to `ROLE_TELLER` only
- Concurrent account-open: `open_idempotency_claim` (V12) + retry on conflict
- Lien placement/release uses pessimistic account lock (`findByIdForUpdate`)
- Transaction history: correct running balance for historical `toDate`; `pagination.truncated` when >500 entries
- Statement/history reject `fromDate` after `toDate`
- `initialDeposit` must be `@PositiveOrZero`
- Transfer requires access to both from and to accounts
- Application-layer double-entry balance check (H2 + Postgres)
- Request fingerprint via `RequestFingerprint` (no hashCode fallback)

- CIF IDs from DB sequence `cif.cif_number_seq` (V9)
- Initial deposit on account open: `AccountOpenedEvent` → `AccountOpenedEventListener` → `PostingService` (same transaction via `@EventListener`)
- Idempotency scoped per `(idempotency_key, initiated_by)`; in-flight returns 409 `IN_PROGRESS`; payload mismatch returns 409 `DUPLICATE_TRANSACTION` (V10 `request_fingerprint`)
- Customers authenticate with username = `cifId`, password `customer`; access limited to own CIF/accounts (`AccountAccessValidator` in `shared`)
- PAN format validation (`AAAAA9999A`); IFSC format validation + `GET /api/v1/reference/ifsc/{code}/validate`
- Available balance = current balance − sum(active liens); `liensTotal` on account details
- Liens API (TELLER): `POST/GET /api/v1/accounts/{id}/liens`, `POST .../liens/{lienId}/release`
- Transaction history includes `runningBalance` (computed ASC, returned DESC, max 500 lines in range)
- Account open idempotency stores `request_fingerprint` (V11); mismatch → 409
- Savings account IDs: Luhn check digit; `AccountNumberGenerator.isValidSavingsAccountId`
- Spring Modulith: modules `kyc`, `account`, `ledger`, `auth`, `shared`; named interfaces `KycApi`, `AccountApi`, `AccountEvents`

## Testing

- `./mvnw clean test` — H2 profile, includes Modulith structure verification
- `PostgresIntegrationTest` — Testcontainers; skipped when Docker is unavailable
- Integration tests use `SecurityMockMvcRequestPostProcessors.user(...)` for stable auth across multiple MockMvc calls in one test
