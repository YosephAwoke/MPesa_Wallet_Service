package com.mpesa.mpesa_wallet;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Supplier;

@Service
public class IdempotencyService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ResponseEntity<Object> execute(String key, String rawBodyHash, Supplier<ResponseEntity<Map<String, Object>>> action) {
        if (key == null || key.isBlank() || key.length() > 64) {
            throw new ApiException(400, "VALIDATION_FAILED", "Invalid Idempotency-Key");
        }

        int inserted = jdbc.update(
                "INSERT INTO idempotency_keys (idem_key, request_hash, status) VALUES (?, ?, 'IN_PROGRESS') ON CONFLICT (idem_key) DO NOTHING",
                key, rawBodyHash
        );

        if (inserted == 0) {
            // Key already exists! Poll briefly in case S1.2 concurrent requests just started
            for (int i = 0; i < 30; i++) {
                Map<String, Object> row = jdbc.queryForMap("SELECT request_hash, status, http_status, response_body FROM idempotency_keys WHERE idem_key = ?", key);
                String existingHash = (String) row.get("request_hash");
                if (!existingHash.equals(rawBodyHash)) {
                    throw new ApiException(409, "IDEMPOTENCY_CONFLICT", "Idempotency key reused with different payload");
                }
                if ("COMPLETED".equals(row.get("status"))) {
                    try {
                        int httpStatus = (Integer) row.get("http_status");
                        Object body = mapper.readValue((String) row.get("response_body"), Object.class);
                        return ResponseEntity.status(httpStatus).body(body);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
                try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            }
            throw new ApiException(409, "REQUEST_IN_PROGRESS", "Original request is still in progress");
        }

        try {
            ResponseEntity<Map<String, Object>> response = action.get();
            String json = mapper.writeValueAsString(response.getBody());
            jdbc.update("UPDATE idempotency_keys SET status = 'COMPLETED', http_status = ?, response_body = ? WHERE idem_key = ?",
                    response.getStatusCode().value(), json, key);
            return ResponseEntity.status(response.getStatusCode()).body(response.getBody());
        } catch (ApiException ex) {
            // Clean up key on business validation failure or record it so retries behave identically
            jdbc.update("DELETE FROM idempotency_keys WHERE idem_key = ?", key);
            throw ex;
        } catch (Exception ex) {
            jdbc.update("DELETE FROM idempotency_keys WHERE idem_key = ?", key);
            throw new RuntimeException(ex);
        }
    }

    public static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
