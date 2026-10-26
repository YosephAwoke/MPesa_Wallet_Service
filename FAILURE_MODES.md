# FAILURE_MODES.md

## Cross-Cutting / Infrastructure
- [FM-01] Race conditions across 2 JVM instances -> Prevented by Postgres row-level locking (`SELECT ... FOR UPDATE`) and unique constraints.
- [FM-02] Floating-point rounding errors in santim -> Prevented by storing all money as `BIGINT` (`long`) in santim.

## 5.1 P2P Transfers
- [FM-03] Concurrent transfers overdrawing available balance -> Prevented by `SELECT ... FOR UPDATE` row locks and DB `CHECK` constraints.
- [FM-04] Bidirectional transfer deadlocks -> Prevented by acquiring wallet locks in strict alphabetical order.
- [FM-05] Crash between debit and credit losing money -> Prevented by wrapping debit, credit, and ledger entries in a single atomic transaction.

## 5.2 Reversals
- [FM-06] Reversing the same transfer twice -> Prevented by locking the transfer row and enforcing status transition `COMPLETED -> REVERSED`.
- [FM-07] Reversal overdrawing `to_wallet` -> Prevented by locking both wallets alphabetically and checking active status and available funds.

## 5.3 Wallets
- [FM-08] Debit committing after a wallet is frozen -> Prevented by `PUT /status` acquiring a write lock; in-flight requests either finish or read `FROZEN` and fail with 422.
- [FM-09] Inconsistent balance reads during concurrent payouts -> Prevented by reading balance and reserved from a single row snapshot.

## 5.4 Payouts
- [FM-10] Slow bank calls exhausting Tomcat/Hikari pools -> Prevented by committing reservations fast (<20ms), returning 202 instantly, and using an async background thread pool.
- [FM-11] Payout stuck reserved forever on instance crash (`kill -9`) -> Prevented by a background DB polling scheduler (`FOR UPDATE SKIP LOCKED`) that reconciles or retries within 2 minutes.

## 5.5 Bank Callbacks
- [FM-12] Callback arriving before submit returns -> Prevented by saving the `PENDING` payout first and synchronizing state transitions via row locks.
- [FM-13] Duplicate callbacks triggering double releases/deductions -> Prevented by row locking; final states (`SUCCEEDED`/`FAILED`) make duplicate callbacks a safe no-op.