package com.mpesa.mpesa_wallet;


import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InternalController {

    private final JdbcTemplate jdbc;

    public InternalController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/mpesa/internal/reset")
    @Transactional
    public void reset() {
        jdbc.execute("TRUNCATE TABLE idempotency_keys, transfers, payouts, wallets");

        for (int i = 1; i <= 100; i++) {
            String id = String.format("W%03d", i);
            jdbc.update("INSERT INTO wallets (wallet_id, status, balance, reserved) VALUES (?, 'ACTIVE', 1000000, 0)", id);
        }
        for (int i = 101; i <= 105; i++) {
            String id = String.format("W%03d", i);
            jdbc.update("INSERT INTO wallets (wallet_id, status, balance, reserved) VALUES (?, 'FROZEN', 1000000, 0)", id);
        }
        jdbc.update("INSERT INTO wallets (wallet_id, status, balance, reserved) VALUES ('M001', 'ACTIVE', 50000000, 0)");
    }
}
