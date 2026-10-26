package com.mpesa.mpesa_wallet;


import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/mpesa/v1")
public class WalletAndTransferController {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IdempotencyService idempotency;

    public WalletAndTransferController(JdbcTemplate jdbc, TransactionTemplate tx, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.idempotency = idempotency;
    }

    // POST /mpesa/v1/p2p/transfers
    @PostMapping("/p2p/transfers")
    public ResponseEntity<Object> transfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @RequestBody Map<String, Object> body) {

        String from = (String) body.get("from_wallet");
        String to = (String) body.get("to_wallet");
        Number amountNum = (Number) body.get("amount");
        String ref = (String) body.get("reference");

        String hash = IdempotencyService.hash(from + "|" + to + "|" + amountNum + "|" + ref);

        return idempotency.execute(idemKey, hash, () -> {
            if (from == null || to == null || amountNum == null || ref == null ||
                    ref.isEmpty() || ref.length() > 64) {
                throw new ApiException(400, "VALIDATION_FAILED", "Invalid fields");
            }
            long amount = amountNum.longValue();
            if (amount < 1 || amount > 5_000_000L) {
                throw new ApiException(400, "VALIDATION_FAILED", "Amount out of bounds");
            }
            if (from.equals(to)) {
                throw new ApiException(422, "SAME_WALLET", "Cannot transfer to same wallet");
            }

            return tx.execute(status -> {
                String first = from.compareTo(to) < 0 ? from : to;
                String second = from.compareTo(to) < 0 ? to : from;

                Map<String, Object> w1 = lockWallet(first);
                Map<String, Object> w2 = lockWallet(second);

                Map<String, Object> fromW = from.equals(first) ? w1 : w2;
                Map<String, Object> toW = to.equals(first) ? w1 : w2;

                if (!"ACTIVE".equals(fromW.get("status")) || !"ACTIVE".equals(toW.get("status"))) {
                    throw new ApiException(422, "WALLET_NOT_ACTIVE", "Both wallets must be ACTIVE");
                }

                long balance = ((Number) fromW.get("balance")).longValue();
                long reserved = ((Number) fromW.get("reserved")).longValue();
                long available = balance - reserved;

                if (available < amount) {
                    throw new ApiException(422, "INSUFFICIENT_FUNDS", "Insufficient available balance");
                }

                jdbc.update("UPDATE wallets SET balance = balance - ?, updated_at = NOW() WHERE wallet_id = ?", amount, from);
                jdbc.update("UPDATE wallets SET balance = balance + ?, updated_at = NOW() WHERE wallet_id = ?", amount, to);

                String transferId = "T-" + UUID.randomUUID().toString().substring(0, 8);
                Instant now = Instant.now();
                jdbc.update("INSERT INTO transfers (transfer_id, from_wallet, to_wallet, amount, reference, status, created_at) VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?)",
                        transferId, from, to, amount, ref, java.sql.Timestamp.from(now));

                return ResponseEntity.status(201).body(Map.of(
                        "transfer_id", transferId,
                        "status", "COMPLETED",
                        "from_available_after", available - amount,
                        "created_at", now.toString()
                ));
            });
        });
    }

    // POST /mpesa/v1/p2p/transfers/{id}/reversal
    @PostMapping("/p2p/transfers/{id}/reversal")
    public ResponseEntity<Map<String, Object>> reverse(@PathVariable("id") String transferId, @RequestBody Map<String, Object> body) {
        return tx.execute(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM transfers WHERE transfer_id = ? FOR UPDATE", transferId);
            if (rows.isEmpty()) {
                throw new ApiException(404, "TRANSFER_NOT_FOUND", "Transfer not found");
            }
            Map<String, Object> t = rows.get(0);
            String tStatus = (String) t.get("status");
            if ("REVERSED".equals(tStatus)) {
                throw new ApiException(409, "ALREADY_REVERSED", "Transfer already reversed");
            }
            if (!"COMPLETED".equals(tStatus)) {
                throw new ApiException(422, "NOT_REVERSIBLE", "Transfer not reversible");
            }

            String origFrom = (String) t.get("from_wallet");
            String origTo = (String) t.get("to_wallet");
            long amount = ((Number) t.get("amount")).longValue();

            String first = origFrom.compareTo(origTo) < 0 ? origFrom : origTo;
            String second = origFrom.compareTo(origTo) < 0 ? origTo : origFrom;

            Map<String, Object> w1 = lockWallet(first);
            Map<String, Object> w2 = lockWallet(second);

            Map<String, Object> debitedWallet = origTo.equals(first) ? w1 : w2; // original to_wallet is debited

            if (!"ACTIVE".equals(debitedWallet.get("status"))) {
                throw new ApiException(422, "WALLET_NOT_ACTIVE", "Debited wallet must be ACTIVE");
            }
            long avail = ((Number) debitedWallet.get("balance")).longValue() - ((Number) debitedWallet.get("reserved")).longValue();
            if (avail < amount) {
                throw new ApiException(422, "INSUFFICIENT_FUNDS", "Insufficient funds to reverse");
            }

            jdbc.update("UPDATE wallets SET balance = balance - ? WHERE wallet_id = ?", amount, origTo);
            jdbc.update("UPDATE wallets SET balance = balance + ? WHERE wallet_id = ?", amount, origFrom);

            String revId = "R-" + UUID.randomUUID().toString().substring(0, 8);
            jdbc.update("UPDATE transfers SET status = 'REVERSED', reversal_id = ? WHERE transfer_id = ?", revId, transferId);

            return ResponseEntity.ok(Map.of(
                    "transfer_id", transferId,
                    "reversal_id", revId,
                    "status", "REVERSED",
                    "amount", amount
            ));
        });
    }

    // GET /mpesa/v1/wallets/{id}
    @GetMapping("/wallets/{id}")
    public ResponseEntity<Map<String, Object>> getWallet(@PathVariable("id") String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM wallets WHERE wallet_id = ?", id);
        if (rows.isEmpty()) throw new ApiException(404, "WALLET_NOT_FOUND", "Wallet not found");
        Map<String, Object> w = rows.get(0);
        long bal = ((Number) w.get("balance")).longValue();
        long res = ((Number) w.get("reserved")).longValue();
        return ResponseEntity.ok(Map.of(
                "wallet_id", id,
                "status", w.get("status"),
                "balance", bal,
                "reserved", res,
                "available", bal - res
        ));
    }

    // PUT /mpesa/v1/wallets/{id}/status
    @PutMapping("/wallets/{id}/status")
    public ResponseEntity<Map<String, Object>> updateStatus(@PathVariable("id") String id, @RequestBody Map<String, String> body) {
        String newStatus = body.get("status");
        if (!"ACTIVE".equals(newStatus) && !"FROZEN".equals(newStatus)) {
            throw new ApiException(400, "VALIDATION_FAILED", "Invalid status");
        }
        return tx.execute(status -> {
            lockWallet(id);
            Instant now = Instant.now();
            jdbc.update("UPDATE wallets SET status = ?, updated_at = ? WHERE wallet_id = ?", newStatus, java.sql.Timestamp.from(now), id);
            return ResponseEntity.ok(Map.of(
                    "wallet_id", id,
                    "status", newStatus,
                    "changed_at", now.toString()
            ));
        });
    }

    private Map<String, Object> lockWallet(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM wallets WHERE wallet_id = ? FOR UPDATE", id);
        if (rows.isEmpty()) throw new ApiException(404, "WALLET_NOT_FOUND", "Wallet " + id + " not found");
        return rows.get(0);
    }
}