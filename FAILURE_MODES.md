# Failure Modes Analysis

## Cross-Cutting / Infrastructure
- [FM-01] In-memory locks/caches used across 2 JVM instances allow race conditions -> Prevented by using PostgreSQL row-level locking (`SELECT ... FOR UPDATE`) and DB unique constraints.
- [FM-02] Floating point math causes rounding drift in santim balances -> Prevented by storing and computing all money as `BIGINT` (`long`) in santim.


## 5.1 POST /mpesa/v1/p2p/transfers
- [FM-03] Two concurrent transfers from the same wallet read stale `available` balance and overdraw below zero -> Prevented by locking wallet rows with `SELECT ... FOR UPDATE` and DB `CHECK (balance >= reserved AND reserved >= 0)`.
- [FM-04] Concurrent transfers, deadlock waiting on each other's row locks -> Prevented by always acquiring `SELECT ... FOR UPDATE` locks in deterministic alphabetical order of `wallet_id`.
- [FM-05] Instance crashes after debiting `from_wallet` before crediting `to_wallet` (losing money) -> Prevented by executing debit, credit, transfer record, and ledger entries inside a single atomic DB transaction.

## 5.2 POST /mpesa/v1/p2p/transfers/{id}/reversal
- [FM-06] Concurrent reversal requests on both instances reverse the same transfer twice (creating/moving money twice) -> Prevented by locking the transfer row `SELECT ... FOR UPDATE` and enforcing status transition `COMPLETED -> REVERSED`
- [FM-07] Reversal races with a normal outbound transfer from `to_wallet` and overdraws `to_wallet` -> Prevented by locking both wallets in alphabetical order via `SELECT ... FOR UPDATE` and checking `to_wallet` `available >= amount` and `status = ACTIVE`.

## 5.3 GET & PUT /mpesa/v1/wallets/{id}
- [FM-08] Debit commits after `PUT /status` (FROZEN) has already returned 200 OK -> Prevented by `PUT /status` acquiring `SELECT ... FOR UPDATE` on the wallet row; in-flight transfers either finish before the lock or read `FROZEN` after acquiring the lock and fail with `422 WALLET_NOT_ACTIVE`.
- [FM-09] `GET /wallets/{id}` reads inconsistent `balance`, `reserved`, and `available` during concurrent payouts -> Prevented by reading balance and reserved from a single atomic row snapshot and computing `available = balance - reserved`.

## 5.4 POST /mpesa/v1/payouts
- [FM-10] Calling the slow external bank (2-60s latency) inside the HTTP request thread or DB transaction exhausts the 10-connection Hikari pool and 50 Tomcat threads, causing 5.1 transfers to breach 300ms p95 or fail with 5xx -> Prevented by committing the reservation in a fast (<20ms) DB transaction, returning `202 ACCEPTED` immediately, and dispatching the bank HTTP call to a bounded background thread pool without holding a DB connection.
- [FM-11] Instance crashes (`kill -9`) after reserving funds or while waiting for a bank 60s hang, leaving funds reserved forever -> Prevented by a background DB polling scheduler on both instances (`FOR UPDATE SKIP LOCKED`) that queries `GET /bank/payouts/{id}` or retries unsubmitted payouts so every payout reaches a final state within 2 minutes.


## 5.5 POST /mpesa/v1/callbacks/bank
- [FM-12] Bank callback arrives *before* `POST /bank/payouts` returns, causing a race condition between the submit worker and callback handler -> Prevented by persisting the `PENDING` payout before calling the bank, and synchronizing state transitions on the payout row via `SELECT ... FOR UPDATE`.
- [FM-13] Duplicate concurrent callbacks (2-3 copies with same `event_id`) release the reservation or deduct `balance` multiple times -> Prevented by locking the payout row; if already in final state (`SUCCEEDED` or `FAILED`), second identical callback is a no-op returning `200 OK`.
