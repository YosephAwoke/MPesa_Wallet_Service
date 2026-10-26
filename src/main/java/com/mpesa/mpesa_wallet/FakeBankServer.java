package com.mpesa.mpesa_wallet;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class FakeBankServer {

    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(64);
    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    // Configurable rates (B2)
    public volatile double acceptRate = 0.80;
    public volatile double rejectRate = 0.08;
    public volatile double noResponseRate = 0.12;
    public volatile double acceptedFailRate = 0.05;
    public volatile double duplicateCallbackRate = 0.30;

    // State & Ledger (B3)
    private final ConcurrentHashMap<String, BankRecord> records = new ConcurrentHashMap<>();
    private final AtomicLong totalPaidSantim = new AtomicLong(0);

    public record BankRecord(String payoutId, long amount, String bankRef, volatile String status, int httpResultType) {}

    public void start(int port) throws Exception {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        server.createContext("/bank/payouts", this::handlePayouts);
        server.createContext("/bank/internal/stats", this::handleStats);
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
        scheduler.shutdownNow();
    }

    public void reset() {
        records.clear();
        totalPaidSantim.set(0);
        acceptRate = 0.80;
        rejectRate = 0.08;
        noResponseRate = 0.12;
        acceptedFailRate = 0.05;
        duplicateCallbackRate = 0.30;
    }

    public long getTotalPaid() {
        return totalPaidSantim.get();
    }

    private void handleStats(HttpExchange ex) {
        try {
            sendJson(ex, 200, Map.of("total_paid", totalPaidSantim.get()));
        } catch (Exception ignored) {}
    }

    @SuppressWarnings("unchecked")
    private void handlePayouts(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            if ("GET".equals(ex.getRequestMethod()) && path.startsWith("/bank/payouts/")) {
                Thread.sleep(ThreadLocalRandom.current().nextInt(1000, 3000)); // 1-3s latency (4.2)
                String payoutId = path.substring("/bank/payouts/".length());
                BankRecord rec = records.get(payoutId);
                String st = (rec == null) ? "NOT_FOUND" : rec.status();
                sendJson(ex, 200, Map.of("status", st));
                return;
            }

            if ("POST".equals(ex.getRequestMethod()) && "/bank/payouts".equals(path)) {
                Map<String, Object> body = mapper.readValue(ex.getRequestBody(), Map.class);
                String payoutId = (String) body.get("payout_id");
                long amount = ((Number) body.get("amount")).longValue();

                BankRecord existing = records.get(payoutId);
                if (existing != null) {
                    if (existing.amount != amount) {
                        sendJson(ex, 200, Map.of("result", "REJECTED", "reason", "AMOUNT_MISMATCH"));
                        return;
                    }
                    if ("REJECTED".equals(existing.status)) {
                        sendJson(ex, 200, Map.of("result", "REJECTED", "reason", "BENEFICIARY_ACCOUNT_INVALID"));
                    } else {
                        sendJson(ex, 200, Map.of("result", "ACCEPTED", "bank_ref", existing.bankRef));
                    }
                    return;
                }

                double r = ThreadLocalRandom.current().nextDouble();
                String bankRef = "BR" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();

                if (r < rejectRate) {
                    // 8% Rejected
                    Thread.sleep(ThreadLocalRandom.current().nextInt(2000, 6000));
                    records.put(payoutId, new BankRecord(payoutId, amount, bankRef, "REJECTED", 200));
                    sendJson(ex, 200, Map.of("result", "REJECTED", "reason", "BENEFICIARY_ACCOUNT_INVALID"));
                } else if (r < rejectRate + noResponseRate) {
                    // 12% No-response (60s hang -> 504)
                    boolean processedHalf = ThreadLocalRandom.current().nextBoolean();
                    if (processedHalf) {
                        scheduleOutcomeAndCallback(payoutId, amount, bankRef);
                    }
                    Thread.sleep(60000);
                    sendJson(ex, 504, Map.of("error", "GATEWAY_TIMEOUT"));
                } else {
                    // 80% Accepted (2-6s latency, callback scheduled 1-10s later, can arrive early!)
                    scheduleOutcomeAndCallback(payoutId, amount, bankRef);
                    Thread.sleep(ThreadLocalRandom.current().nextInt(2000, 6000));
                    sendJson(ex, 200, Map.of("result", "ACCEPTED", "bank_ref", bankRef));
                }
            }
        } catch (Exception ignored) {}
    }

    private void scheduleOutcomeAndCallback(String payoutId, long amount, String bankRef) {
        records.put(payoutId, new BankRecord(payoutId, amount, bankRef, "IN_PROGRESS", 200));
        boolean fails = ThreadLocalRandom.current().nextDouble() < acceptedFailRate;
        String finalStatus = fails ? "FAILED" : "SUCCEEDED";

        int delayMs = ThreadLocalRandom.current().nextInt(1000, 10000);
        scheduler.schedule(() -> {
            BankRecord prev = records.put(payoutId, new BankRecord(payoutId, amount, bankRef, finalStatus, 200));
            if ("SUCCEEDED".equals(finalStatus) && (prev == null || !"SUCCEEDED".equals(prev.status))) {
                totalPaidSantim.addAndGet(amount);
            }
            String eventId = UUID.randomUUID().toString().substring(0, 13);
            int copies = ThreadLocalRandom.current().nextDouble() < duplicateCallbackRate
                    ? ThreadLocalRandom.current().nextInt(2, 4) : 1;

            for (int i = 0; i < copies; i++) {
                scheduler.submit(() -> sendCallbackWithRetry(eventId, payoutId, bankRef, finalStatus, 0));
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void sendCallbackWithRetry(String eventId, String payoutId, String bankRef, String status, int attempt) {
        if (attempt > 5) return;
        int targetPort = ThreadLocalRandom.current().nextBoolean() ? 8081 : 8082;
        try {
            String json = mapper.writeValueAsString(Map.of(
                    "event_id", eventId,
                    "payout_id", payoutId,
                    "bank_ref", bankRef,
                    "status", status
            ));
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + targetPort + "/mpesa/v1/callbacks/bank"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(2))
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                scheduler.schedule(() -> sendCallbackWithRetry(eventId, payoutId, bankRef, status, attempt + 1), 3, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            scheduler.schedule(() -> sendCallbackWithRetry(eventId, payoutId, bankRef, status, attempt + 1), 3, TimeUnit.SECONDS);
        }
    }

    private void sendJson(HttpExchange ex, int status, Map<String, Object> body) throws Exception {
        byte[] bytes = mapper.writeValueAsBytes(body);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}