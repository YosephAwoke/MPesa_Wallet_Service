package com.mpesa.mpesa_wallet;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/mpesa/v1")
@EnableScheduling
public class PayoutController {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService bankWorkers = Executors.newFixedThreadPool(64);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Value("${bank.base-url:http://localhost:9090}")
    private String bankBaseUrl;

    public PayoutController(JdbcTemplate jdbc, TransactionTemplate tx, IdempotencyService idempotency) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.idempotency = idempotency;
    }

    // 5.4 POST /mpesa/v1/payouts
    @PostMapping("/payouts")
    public ResponseEntity<Object> createPayout(
            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey,
            @RequestBody Map<String, Object> body) {

        String walletId = (String) body.get("wallet_id");
        String bankAccount = (String) body.get("bank_account");
        Number amountNum = (Number) body.get("amount");

        String hash = IdempotencyService.hash(walletId + "|" + bankAccount + "|" + amountNum);

        return idempotency.execute(idemKey, hash, () -> {
            if (walletId == null || bankAccount == null || amountNum == null || bankAccount.isBlank()) {
                throw new ApiException(400, "VALIDATION_FAILED", "Invalid payout request");
            }
            long amount = amountNum.longValue();
            if (amount < 1 || amount > 5_000_000L) {
                throw new ApiException(400, "VALIDATION_FAILED", "Invalid amount");
            }

            String payoutId = "P-" + UUID.randomUUID().toString().substring(0, 8);

            // Step 1: Fast atomic DB reservation (<10ms)
            tx.executeWithoutResult(status -> {
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM wallets WHERE wallet_id = ? FOR UPDATE", walletId);
                if (rows.isEmpty()) throw new ApiException(404, "WALLET_NOT_FOUND", "Wallet not found");
                Map<String, Object> w = rows.get(0);

                if (!"ACTIVE".equals(w.get("status"))) {
                    throw new ApiException(422, "WALLET_NOT_ACTIVE", "Wallet is not ACTIVE");
                }
                long bal = ((Number) w.get("balance")).longValue();
                long res = ((Number) w.get("reserved")).longValue();
                if (bal - res < amount) {
                    throw new ApiException(422, "INSUFFICIENT_FUNDS", "Insufficient available funds");
                }

                jdbc.update("UPDATE wallets SET reserved = reserved + ?, updated_at = NOW() WHERE wallet_id = ?", amount, walletId);
                jdbc.update("INSERT INTO payouts (payout_id, wallet_id, bank_account, amount, status) VALUES (?, ?, ?, ?, 'PENDING')",
                        payoutId, walletId, bankAccount, amount);
            });

            // Step 2: Submit to slow bank asynchronously off the request thread!
            bankWorkers.submit(() -> submitToBank(payoutId, bankAccount, amount));

            return ResponseEntity.status(202).body(Map.of(
                    "payout_id", payoutId,
                    "status", "PENDING"
            ));
        });
    }

    // 5.4 GET /mpesa/v1/payouts/{id}
    @GetMapping("/payouts/{id}")
    public ResponseEntity<Map<String, Object>> getPayout(@PathVariable("id") String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM payouts WHERE payout_id = ?", id);
        if (rows.isEmpty()) throw new ApiException(404, "PAYOUT_NOT_FOUND", "Payout not found");
        Map<String, Object> p = rows.get(0);
        return ResponseEntity.ok(Map.of(
                "payout_id", p.get("payout_id"),
                "wallet_id", p.get("wallet_id"),
                "amount", ((Number) p.get("amount")).longValue(),
                "status", p.get("status"),
                "bank_ref", p.get("bank_ref") == null ? "" : p.get("bank_ref"),
                "conflict", p.get("conflict"),
                "updated_at", p.get("updated_at").toString()
        ));
    }

    // 5.5 POST /mpesa/v1/callbacks/bank
    @PostMapping("/callbacks/bank")
    public ResponseEntity<Void> handleCallback(@RequestBody Map<String, String> body) {
        String payoutId = body.get("payout_id");
        String bankRef = body.get("bank_ref");
        String cbStatus = body.get("status"); // SUCCEEDED or FAILED

        tx.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM payouts WHERE payout_id = ? FOR UPDATE", payoutId);
            if (rows.isEmpty()) {
                throw new ApiException(404, "PAYOUT_NOT_FOUND", "Unknown payout_id");
            }
            applyFinalPayoutState(rows.get(0), cbStatus, bankRef);
        });
        return ResponseEntity.ok().build();
    }

    @SuppressWarnings("unchecked")
    private void submitToBank(String payoutId, String bankAccount, long amount) {
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "payout_id", payoutId,
                    "bank_account", bankAccount,
                    "amount", amount
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(bankBaseUrl + "/bank/payouts"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(8)) // Section 4.1: >8s is unknown
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                Map<String, Object> resBody = mapper.readValue(resp.body(), Map.class);
                String result = (String) resBody.get("result");
                if ("REJECTED".equals(result)) {
                    tx.executeWithoutResult(s -> {
                        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM payouts WHERE payout_id = ? FOR UPDATE", payoutId);
                        if (!rows.isEmpty()) applyFinalPayoutState(rows.get(0), "FAILED", null);
                    });
                } else if ("ACCEPTED".equals(result)) {
                    String bankRef = (String) resBody.get("bank_ref");
                    jdbc.update("UPDATE payouts SET bank_ref = COALESCE(bank_ref, ?), updated_at = NOW() WHERE payout_id = ?", bankRef, payoutId);
                }
            }
        } catch (Exception ignored) {
            // Timeout (>8s) or network error: outcome is unknown, background reconciler will resolve it
        }
    }

    // Background Reconciler for 8s timeouts and killed instances (S4.3)
    @Scheduled(fixedDelay = 3000)
    @SuppressWarnings("unchecked")
    public void reconcilePendingPayouts() {
        List<Map<String, Object>> stale = jdbc.queryForList(
                "SELECT * FROM payouts WHERE status = 'PENDING' AND created_at < NOW() - INTERVAL '8 seconds' LIMIT 50"
        );
        for (Map<String, Object> p : stale) {
            bankWorkers.submit(() -> {
                String payoutId = (String) p.get("payout_id");
                String bankAccount = (String) p.get("bank_account");
                long amount = ((Number) p.get("amount")).longValue();
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(bankBaseUrl + "/bank/payouts/" + payoutId))
                            .timeout(Duration.ofSeconds(4))
                            .GET()
                            .build();
                    HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200) {
                        Map<String, Object> body = mapper.readValue(resp.body(), Map.class);
                        String st = (String) body.get("status");
                        if ("SUCCEEDED".equals(st) || "FAILED".equals(st) || "REJECTED".equals(st)) {
                            String mapped = "SUCCEEDED".equals(st) ? "SUCCEEDED" : "FAILED";
                            tx.executeWithoutResult(s -> {
                                List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM payouts WHERE payout_id = ? FOR UPDATE", payoutId);
                                if (!rows.isEmpty()) applyFinalPayoutState(rows.get(0), mapped, null);
                            });
                        } else if ("NOT_FOUND".equals(st)) {
                            // Bank never received it during the 60s hang or crash -> safe to re-submit (bank is idempotent on payout_id)
                            submitToBank(payoutId, bankAccount, amount);
                        }
                    }
                } catch (Exception ignored) {}
            });
        }
    }

    private void applyFinalPayoutState(Map<String, Object> p, String targetStatus, String bankRef) {
        String payoutId = (String) p.get("payout_id");
        String currentStatus = (String) p.get("status");
        String walletId = (String) p.get("wallet_id");
        long amount = ((Number) p.get("amount")).longValue();

        if ("SUCCEEDED".equals(currentStatus) || "FAILED".equals(currentStatus)) {
            if (!currentStatus.equals(targetStatus)) {
                jdbc.update("UPDATE payouts SET conflict = TRUE, updated_at = NOW() WHERE payout_id = ?", payoutId);
            }
            return; // Idempotent: never release reservation twice!
        }

        if ("SUCCEEDED".equals(targetStatus)) {
            jdbc.update("UPDATE wallets SET balance = balance - ?, reserved = reserved - ?, updated_at = NOW() WHERE wallet_id = ?",
                    amount, amount, walletId);
            jdbc.update("UPDATE payouts SET status = 'SUCCEEDED', bank_ref = COALESCE(?, bank_ref), updated_at = NOW() WHERE payout_id = ?",
                    bankRef, payoutId);
        } else if ("FAILED".equals(targetStatus)) {
            jdbc.update("UPDATE wallets SET reserved = reserved - ?, updated_at = NOW() WHERE wallet_id = ?",
                    amount, walletId);
            jdbc.update("UPDATE payouts SET status = 'FAILED', bank_ref = COALESCE(?, bank_ref), updated_at = NOW() WHERE payout_id = ?",
                    bankRef, payoutId);
        }
    }
}