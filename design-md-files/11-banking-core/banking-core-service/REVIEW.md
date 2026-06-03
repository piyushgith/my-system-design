# Banking Core Service — Senior Engineer Review

Scope: `com.test.banking.core` (Spring Modulith). Review only — **no production code changed**.
Before→After snippets are illustrative; say the word to apply any of them.

---

## 1. Architecture Summary

**Style:** Modular monolith (Spring Modulith) with a per-module hexagonal split.

```
core
├── kyc        api │ application │ infrastructure
├── account    api │ application │ infrastructure
├── ledger     api │ application │ infrastructure
└── shared     audit │ security │ money │ web │ exception │ validation │ util
```

**Module contracts (good):** cross-module calls go through published interfaces, not entities:
- `KycPublicApi` → `KycPublicApiImpl` (customer exists / KYC verified)
- `AccountPublicApi` → `AccountPublicApiImpl` (balance read + credit/debit/lock)
- `AccountOwnershipLookup` → `AccountOwnershipLookupImpl` (auth ownership)

**Flow (deposit):**
`TransactionController → TransactionService.deposit` (idempotency guard) `→ IdempotencyService.execute → PostingService.postDeposit` (writes `TransactionEntity` + 2 `JournalEntryEntity`, asserts double-entry, calls `AccountPublicApi.creditAccount`) `→ AuditService.record`.

**Account open → ledger** is decoupled via `ApplicationEventPublisher` (`AccountOpenedEvent`) consumed by `AccountOpenedEventListener` for the opening deposit.

**Persistence:** JPA + Flyway (separate `migration/` for Postgres, `migration-h2/` for H2). Pessimistic write locks (`findByIdForUpdate`) on accounts. Money stored as `long paise`. Double-entry enforced in code (`assertDoubleEntryBalanced`) and DB (`V8__double_entry_check.sql`, Postgres only).

**Security:** HTTP Basic, roles `TELLER`/`CUSTOMER`, `@PreAuthorize` + `AccountAccessValidator` (teller bypass, else principal CIF must own account).

**Verdict:** structure is genuinely good for a capstone — clear boundaries, real idempotency, real double-entry. Problems are in the *application layer* (redundant work, hidden side effects, latent bugs), not the architecture.

---

## 2. Key Problem Areas

### 🔴 Correctness (fix first)
1. **`Money.ofPaise` throws on negative → 500 in transaction history.** `TransactionService.toLine` wraps the running balance in `Money.ofPaise(runningBalancePaise)`, which rejects `< 0`. Any overdraft / ledger-drift line crashes the whole history call.
2. **Dual source of truth for balance.** `account.currentBalancePaise` is mutated directly in `creditAccount`/`debitAccount`, *and* the journal holds the authoritative entries. `getHistory` back-computes balances from `account.currentBalancePaise` minus journal aggregates — if the two ever drift, every running balance is silently wrong. Real ledgers derive balance *from* the journal.

### 🟠 Performance
3. **Account row locked/loaded 3× (deposit) / 6× (transfer) per request.** `PostingService` already `lockAndGetBalance` + `creditAccount`/`debitAccount` (each a `SELECT … FOR UPDATE`); then `TransactionService.executeDeposit/Transfer` calls `lockAndGetBalance` *again* just to read the post-balance.
4. **`assertDoubleEntryBalanced` re-`SELECT`s rows just saved.** `findByTxnId` re-reads the two entries already in the in-memory `entries` list.
5. **`refreshAvailableBalance` fires a `sumActiveLiensPaise` aggregate on every call**, and it is called 2× inside `creditAccount`/`debitAccount` and again in callers — many redundant lien-sum queries per request.
6. **`getHistory` loads up to 501 rows, runs 3 aggregate queries, reverses in memory, and paginates with `subList`.** "Pagination" is post-load slicing capped at `HISTORY_MAX_ENTRIES`; DB paging is bypassed.
7. **`resolveOpenIdempotencyConflict` busy-waits** `Thread.sleep(50)` up to 10×/500 ms on a request thread.

### 🟡 Structure / SOLID
8. **`AvailableBalanceCalculator` is a misnamed command, not a calculator** — `refreshAvailableBalance` mutates the managed entity as a side effect, including inside `@Transactional(readOnly = true)` reads. Hidden write.
9. **`IdempotencyService.execute(..., requestFingerprint, ...)` ignores `requestFingerprint`** (dead param; the real check lives in `assertPayloadMatches`). `findCachedResponse(key, type)` public overload appears unused.
10. **`AccountService.openAccount` is a 70-line method** mixing two-phase idempotency (claim table + record table), KYC checks, entity build, event publish, and audit.
11. **Two idempotency tables for one concern** (`OpenIdempotencyClaimEntity` + `OpenIdempotencyEntity`).

### 🟢 Code quality
12. **Silent exception swallow:** `finalizeTransaction` → `catch (Exception ignored)`; `deserialize` swallows `JsonProcessingException`. No log line anywhere — **zero SLF4J in the service layer.**
13. **Magic strings** for status/entry-type/currency/GL codes: `"ACTIVE"`, `"POSTED"`, `"PENDING"`, `"C"`/`"D"`, `"INR"`, `"GL-TRANSFER"`, `"SAVINGS"`. No enums/constants.
14. **`@EventListener` (sync, same tx) on the opening deposit** — runs inside the open-account transaction, so it neither decouples nor isolates failure. If async was intended, it should be `@TransactionalEventListener(AFTER_COMMIT)`; if atomic coupling was intended, an event is the wrong tool (just call the service).
15. **Inconsistent idempotency-header reference:** `AccountController` uses a static import; `TransactionController` uses an inline fully-qualified constant.

---

## 3. Refactoring Plan (prioritized, behavior-preserving)

| # | Action | Why | Risk |
|---|--------|-----|------|
| P0-1 | Guard running-balance against negative in `Money` usage | Removes 500 on overdraft/drift | Low |
| P0-2 | Pass already-locked balances out of `PostingService` instead of re-locking | Removes 2–4 `SELECT FOR UPDATE`/txn | Low–Med |
| P1-3 | Sum in-memory `entries` in `assertDoubleEntryBalanced` | Drops a query, no semantic change | Low |
| P1-4 | Memoize lien sum per request / compute once | Cuts redundant aggregates | Low |
| P1-5 | Add SLF4J + stop swallowing serialization errors | Observability | Low |
| P2-6 | Introduce status/entry-type enums | Type safety, readability | Med (wide touch) |
| P2-7 | Rename `AvailableBalanceCalculator.refresh*` → make side effect explicit; keep a pure `computeAvailablePaise` | Honesty about mutation | Low |
| P2-8 | Drop dead `requestFingerprint` param / unused overload in `IdempotencyService` | Clarity | Low |
| P3-9 | Extract idempotency orchestration out of `openAccount` | Readability | Med |
| P3-10 | Decide event semantics (`@TransactionalEventListener` vs direct call) | Correct decoupling | Med |

Decision to surface (Rule 7): **the dual balance model (#2) is the one architectural fork.** Either (a) keep `currentBalancePaise` as a cached projection and add a reconciliation/derivation path, or (b) make the journal authoritative and compute balance on read. Don't leave both mutable. Pick one before scaling.

---

## 4. Code Improvements (Before → After)

### 4.1 — P0: history crash on negative running balance

**Before** — `TransactionService.toLine`:
```java
Money.ofPaise(runningBalancePaise).toRupees()   // throws if runningBalancePaise < 0
```
`Money.ofPaise` rejects negatives, but a running/overdraft balance is legitimately signed.

**After** — use a signed factory for *display* amounts; keep the guard for *value* amounts:
```java
// Money.java — add a signed factory, keep ofPaise strict for credits/debits
public static Money ofSignedPaise(long paise) {
    return new Money(paise);
}

// TransactionService.toLine
Money.ofSignedPaise(runningBalancePaise).toRupees()
```
*Trade-off:* two factories, but intent is explicit — `ofPaise` = a non-negative movement, `ofSignedPaise` = a balance that may be negative. No behavior change for valid data; removes the 500.

---

### 4.2 — P0: stop re-locking the account just to read the post-balance

**Before** — `PostingService.postDeposit` locks + credits, then `TransactionService.executeDeposit` locks *again*:
```java
// PostingService
accountPublicApi.lockAndGetBalance(accountId);
...
accountPublicApi.creditAccount(accountId, amountPaise);   // loadForUpdate #2
return new PostedTransaction(txnId, valueDate, txn);

// TransactionService
var posted = postingService.postDeposit(...);
AccountBalanceDto after = accountPublicApi.lockAndGetBalance(request.accountId()); // lock #3
```

**After** — return the post-credit balance from the call that already holds the lock:
```java
// AccountPublicApi
AccountBalanceDto creditAccount(String accountId, long amountPaise); // return new balance

// AccountPublicApiImpl.creditAccount (already inside the lock)
account.setCurrentBalancePaise(account.getCurrentBalancePaise() + amountPaise);
availableBalanceCalculator.refreshAvailableBalance(account);
...
return toDto(account);

// TransactionService.executeDeposit
AccountBalanceDto after = postingService.postDeposit(...).balanceAfter();
```
*Why:* same row, same transaction — re-`SELECT … FOR UPDATE` is wasted round-trips. Returning the balance the locking method already computed removes 1 lock on deposit and 2 on transfer. *Trade-off:* `creditAccount`/`debitAccount` change signature; callers updated in one module.

---

### 4.3 — P1: balance the double entry from memory, not a re-query

**Before** — `PostingService`:
```java
journalEntryRepository.saveAll(entries);
assertDoubleEntryBalanced(txnId);            // re-SELECTs the rows just saved
...
private void assertDoubleEntryBalanced(String txnId) {
    for (JournalEntryEntity entry : journalEntryRepository.findByTxnId(txnId)) { ... }
}
```

**After**:
```java
journalEntryRepository.saveAll(entries);
assertBalanced(entries);                     // entries already in hand

private void assertBalanced(List<JournalEntryEntity> entries) {
    long debits = 0, credits = 0;
    for (JournalEntryEntity e : entries) {
        if ("D".equals(e.getEntryType())) debits += e.getAmountPaise();
        else credits += e.getAmountPaise();
    }
    if (debits != credits) {
        throw new BusinessRuleException("LEDGER_IMBALANCE",
                "Double-entry imbalance for transaction " + entries.get(0).getTxnId());
    }
}
```
*Trade-off:* trusts the in-memory list reflects what was persisted (it does — same objects). One fewer query per posting.

---

### 4.4 — P1: add logging, stop swallowing serialization failures

**Before** — `PostingService.finalizeTransaction`:
```java
} catch (Exception ignored) {
    txn.setResponseSnapshot("{}");
}
```

**After**:
```java
private static final Logger log = LoggerFactory.getLogger(PostingService.class);
...
} catch (JsonProcessingException e) {
    log.warn("Failed to serialize response snapshot for txn {}: {}", txn.getTxnId(), e.getMessage());
    txn.setResponseSnapshot("{}");
}
```
Also add a single info log on successful posting and a warn on `LEDGER_IMBALANCE` / idempotency conflicts. *Why:* a banking core with no application logs is undebuggable in prod; audit rows aren't a substitute for failure logs.

---

### 4.5 — P2: name the side effect honestly

**Before** — a "Calculator" that mutates and is called inside read-only transactions:
```java
public void refreshAvailableBalance(AccountEntity account) {
    account.setAvailableBalancePaise(computeAvailablePaise(account)); // hidden write
}
```

**After** — keep the pure function, make the mutation explicit at call sites:
```java
public long computeAvailablePaise(AccountEntity account) { ... }   // pure, unchanged

public void applyAvailableBalance(AccountEntity account) {          // clearly a command
    account.setAvailableBalancePaise(computeAvailablePaise(account));
}
```
Read paths (`getBalance`, `getAccount`) should call `computeAvailablePaise` and map to the DTO **without** mutating the managed entity in a `readOnly` transaction. *Trade-off:* a few extra call-site edits; removes accidental dirty-checking writes during reads.

---

### 4.6 — P2: drop the dead idempotency param

**Before**:
```java
public <T> T execute(String idempotencyKey, String requestFingerprint, Class<T> responseType, Supplier<T> action)
// requestFingerprint never read in the body
```

**After** — remove the unused param (fingerprint matching stays in `assertPayloadMatches`, already called separately by `TransactionService`):
```java
public <T> T execute(String idempotencyKey, Class<T> responseType, Supplier<T> action)
```
*Trade-off:* touches 2 call sites; eliminates a misleading signature.

---

## 5. What an interviewer will challenge

- **"Where is the real balance?"** The split between `account.currentBalancePaise` and the journal is the headline weakness — be ready to defend it as a cached projection *with* a reconciliation story, or move to derive-on-read.
- **"Why pessimistic locks?"** Defensible for an MVP / single-node, but it caps write throughput per account and risks lock-wait under contention; mention optimistic `@Version` or per-account serialized queues as the scale path.
- **"Sync `@EventListener` for the opening deposit?"** Either own the coupling (call the service directly) or commit to async after-commit. Current form is the worst of both.
- **"History pagination"** is in-memory slicing capped at 500 — won't survive a high-volume account; needs keyset/DB paging.

---

## Notes
- No tests were run and no source modified by this review.
- Build/verify any applied change with: `./mvnw -q clean verify` (Postgres profile exercises `V8` double-entry constraint).
