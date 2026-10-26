# System Design & Written Answers

## 1. Decisions, Isolation Level & Idempotency Mechanism
- **Transaction Isolation Level:** PostgreSQL default `READ COMMITTED` combined with explicit pessimistic row-level locks (`SELECT ... FOR UPDATE`). Because every write path acquires row locks before checking balances or mutating state, `READ COMMITTED` prevents lost updates and dirty reads without incurring the high retry/serialization failure rate of `SERIALIZABLE` under high contention (such as S1.1 and S4.1).
- **Idempotency Mechanism:** Global `idempotency_keys` table in PostgreSQL. Before executing a mutating endpoint, the service attempts an atomic `INSERT ... ON CONFLICT (idem_key) DO NOTHING` with status `IN_PROGRESS` and a SHA-256 hash of the request payload. If the row already exists with a different hash, `409 IDEMPOTENCY_CONFLICT` is returned immediately. If the original request is `IN_PROGRESS`, concurrent duplicates poll briefly (up to 1.5s) to return the exact completed status/body (satisfying S1.2) or fall back to `409 REQUEST_IN_PROGRESS`.

## 2. Q1: Every Wait
1. **Tomcat Request Thread Pool (`server.tomcat.threads.max=50`):** Incoming HTTP requests wait in the OS accept queue if all 50 threads are busy. Bounded by keeping all HTTP handlers non-blocking of external I/O (payouts return 202 immediately after DB reservation).
2. **HikariCP Connection Pool (`maximum-pool-size=10`, `connection-timeout=2000`):** Threads acquiring a DB connection wait up to `2000 ms` before throwing `DataAccessResourceFailureException` (mapped to `503 SERVICE_UNAVAILABLE`). Bounded by never performing HTTP calls to the bank inside a `@Transactional` or `TransactionTemplate` block; transactions only execute fast indexed SQL queries (<15ms).
3. **PostgreSQL Row Locks (`SELECT ... FOR UPDATE`):** Concurrent transactions touching the same `wallet_id`, `transfer_id`, or `payout_id` wait on row-level locks. Deadlocks are prevented by sorting `wallet_id` values alphabetically before locking. Wait duration is bounded by the short transaction duration (<15ms) and connection timeout.
4. **Idempotency In-Flight Wait:** Concurrent identical requests poll up to 30 times (`50ms` interval, bounded at `1500ms` max) waiting for the leader transaction to commit before returning `409 REQUEST_IN_PROGRESS`.
5. **External Bank HTTP Call (`bankWorkers` pool):** Background threads wait up to `8000ms` (`HttpRequest` timeout) on `POST /bank/payouts` and `4000ms` on `GET /bank/payouts/{id}`. This occurs completely outside Tomcat request threads and Hikari DB connections.

## 3. Q2: Database Restart During a Payout
1. **Before Restart:** `POST /mpesa/v1/payouts` commits Transaction 1 in PostgreSQL (`reserved` increased by `amount`, payout row inserted as `PENDING`, idempotency key saved) and returns `202 ACCEPTED`.
2. **During Bank Call & DB Restart:** A background worker sends `POST /bank/payouts` to the bank while PostgreSQL restarts.
    - If the bank returns `ACCEPTED`/`REJECTED` or fires an early callback to `/callbacks/bank` while PostgreSQL is unreachable, the DB update fails and `/callbacks/bank` returns `503 SERVICE_UNAVAILABLE`.
    - Per Section 4.3, the bank retries failed callbacks every 3 seconds up to 5 times (covering a 15-second DB restart window).
3. **After PostgreSQL Recovers:**
    - The money remains safely reserved (`reserved` was committed before `202` was returned, so no funds can be double-spent).
    - Once PostgreSQL is back up, the `@Scheduled` reconciler (`reconcilePendingPayouts`) scans `PENDING` payouts older than 8 seconds, queries `GET /bank/payouts/{payout_id}`, and atomically transitions the payout to `SUCCEEDED` (deducting `balance` and `reserved`) or `FAILED` (releasing `reserved`), or re-submits if `NOT_FOUND`.

## 4. Q3: What You Left Out
- **API 5.6 (`POST /mpesa/v1/b2c/disbursements`):** Not implemented to prioritize correctness on 5.1–5.5.
    - **How to build it:** Store a `batches` record (`batch_id`, `payload_hash`, `status`) and insert all 5,000 `batch_rows` in batches. Return `202 ACCEPTED` immediately. Process rows asynchronously in micro-batches of 50 rows per `TransactionTemplate` execution rather than locking `M001` for all 5,000 rows in a single massive transaction.
    - **What makes it hard:** Requirement S6.3 mandates that P2P transfers (5.1) out of `M001` stay under `300ms (p95)` while 5,000 disbursement rows are draining `M001`. Locking `M001` once for all 5,000 rows would block 5.1 transfers; instead, micro-batching 25–50 rows per short transaction releases the `M001` row lock every few milliseconds so concurrent P2P transfers can interleave cleanly.
- **API 5.7 (`GET /mpesa/v1/wallets/{id}/statement`):** Not implemented.
    - **How to build it:** Create a `ledger_entries` table (`seq_id BIGSERIAL PRIMARY KEY`, `wallet_id`, `type`, `amount`, `balance_after`, `reference`, `created_at`) with composite index `idx_ledger_wallet_seq ON ledger_entries (wallet_id, seq_id DESC)`.
    - **What makes it hard:** Keyset (cursor) pagination over 1,000,000 rows in under `150ms` (S7.2) while concurrent writes occur (S7.1). `OFFSET` pagination degrades linearly at depth 1,000. Encoding the initial `max(seq_id)` snapshot and last-seen `seq_id` into `next_cursor` allows `WHERE wallet_id = ? AND seq_id < ? ORDER BY seq_id DESC LIMIT ?` to execute via an O(log N) B-Tree index seek in <5ms at any depth.