# Banking Core Phase 0 — Manual Smoke

## Prerequisites

```bash
cd banking-core-service
docker compose up -d
./mvnw spring-boot:run
```

Default credentials (HTTP Basic):

| User | Password | Role |
|------|----------|------|
| teller | teller | TELLER |
| `{cifId}` | customer | CUSTOMER (username is the CIF returned from `POST /customers`, e.g. `CIF-10000001`) |

All mutating APIs require header `Idempotency-Key: <uuid>`.

**Reference (no auth):** `GET /api/v1/reference/ifsc/{code}/validate` — RBI-format IFSC check (not live master lookup).

## Flow

1. **Create customer** — `POST /api/v1/customers` with PAN, name, DOB (stub KYC → VERIFIED).
2. **Open savings** — `POST /api/v1/accounts` with `cifId`, `accountType: SAVINGS`, optional `initialDeposit`, `Idempotency-Key`. Initial deposit is posted via ledger double-entry (`AccountOpenedEvent`). Response reflects credited balance when `initialDeposit` > 0.
3. **Deposit** — `POST /api/v1/transactions/deposit` with header `Idempotency-Key: <uuid>`.
4. **Transfer** — `POST /api/v1/transactions/transfer` between two accounts, idempotent header required.
5. **History** — `GET /api/v1/accounts/{accountId}/transactions`.
6. **Statement** — `POST /api/v1/accounts/{accountId}/statements/request` with `fromDate` / `toDate`.
7. **Liens (staff)** — `POST /api/v1/accounts/{accountId}/liens` with `amount`, `reason`; `GET` lists active liens; `POST .../liens/{lienId}/release` restores available balance.

Transaction history lines include `runningBalance`. Account details include `liensTotal`.

Swagger UI: http://localhost:8080/swagger-ui.html
