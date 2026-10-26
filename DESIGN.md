# DESIGN.md

Isolation: Postgres READ COMMITTED + explicit SELECT FOR UPDATE row locks. Prevents lost updates under heavy load (S1.1) without the high failure rate of SERIALIZABLE.

Idempotency: Global idempotency_keys table with INSERT ... ON CONFLICT DO NOTHING. SHA-256 body hashes protect against key reuse conflicts (409 IDEMPOTENCY_CONFLICT), and in-flight requests poll briefly to return identical results (S1.2).

## Q1: Every Wait

Tomcat threads (max 50): Requests queue if threads are busy. Handled by making HTTP request handlers non-blocking (payouts return 202 instantly).

HikariCP pool (max 10, timeout 2s): Threads wait up to 2s for a DB connection, throwing 503 if exhausted. Safe because transactions only run quick indexed queries (<15ms) and never make HTTP calls.

Row locks (SELECT FOR UPDATE): Concurrent transactions on the same wallet queue up. Prevented deadlocks by sorting wallet IDs alphabetically before locking.

Idempotency poll: Concurrent duplicates wait up to 1.5s (50ms intervals) for the leader transaction to finish.

Bank HTTP calls: Background worker threads wait up to 8s on payouts and 4s on status checks outside Tomcat and Hikari pools.

## Q2: Database Restart During a Payout

Before restart: POST /payouts commits Transaction 1 (reserving money, inserting PENDING payout, saving idempotency key) and returns 202.

During bank call / restart: Background thread talks to the bank. If the DB is down when a callback arrives, it returns 503, and the bank retries up to 5 times (every 3s).

After recovery: Money stays reserved because that commit happened before 202. The scheduled reconciler picks up PENDING payouts older than 8s, queries GET /bank/payouts/{id}, and finishes them (success moves reserved to balance; failure releases it).

## Q3: What You Left Out

API 5.6 (Disbursements): Skipped to focus on 5.1-5.5. Build by saving batch headers and processing rows async in small chunks of 50 per transaction so M001 row locks don't block normal P2P transfers (S6.3).

API 5.7 (Statement): Skipped. Build with a ledger_entries table and composite index (wallet_id, seq_id DESC). Encodes max sequence snapshots into cursors to do O(log N) index seeks under 150ms.