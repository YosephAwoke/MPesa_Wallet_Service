package com.mpesa.mpesa_wallet;


import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WalletE2ETest {

    private static ConfigurableApplicationContext app1;
    private static ConfigurableApplicationContext app2;
    private static FakeBankServer fakeBank;
    private static final HttpClient client = HttpClient.newHttpClient();
    private static final ObjectMapper mapper = new ObjectMapper();

    @BeforeAll
    static void startCluster() throws Exception {
        fakeBank = new FakeBankServer();
        fakeBank.start(9090);

        app1 = new SpringApplicationBuilder(WalletServiceApplication.class)
                .properties("server.port=8081").run();
        app2 = new SpringApplicationBuilder(WalletServiceApplication.class)
                .properties("server.port=8082").run();
    }

    @AfterAll
    static void stopCluster() {
        if (app1 != null) app1.close();
        if (app2 != null) app2.close();
        if (fakeBank != null) fakeBank.stop();
    }

    @BeforeEach
    void resetAll() throws Exception {
        fakeBank.reset();
        post(8081, "/mpesa/internal/reset", null, Map.of());
    }

    // S1.1: 200 concurrent transfers of 10,000 from W001 to random wallets across both instances
    @Test
    @Order(1)
    void testS1_1_ConcurrentOverdraftProtection() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger c201 = new AtomicInteger();
        AtomicInteger c422 = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    int port = (idx % 2 == 0) ? 8081 : 8082;
                    String target = String.format("W%03d", (idx % 99) + 2);
                    HttpResponse<String> r = post(port, "/mpesa/v1/p2p/transfers", "key-s11-" + idx,
                            Map.of("from_wallet", "W001", "to_wallet", target, "amount", 10000, "reference", "ref"));
                    if (r.statusCode() == 201) c201.incrementAndGet();
                    else if (r.statusCode() == 422) c422.incrementAndGet();
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
        }
        latch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertEquals(100, c201.get());
        assertEquals(100, c422.get());
        assertEquals(0L, getBalance("W001"));
    }

    // S1.2: 20 concurrent identical requests with one Idempotency-Key across both instances
    @Test
    @Order(2)
    void testS1_2_ConcurrentIdempotency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(1);
        Set<String> transferIds = ConcurrentHashMap.newKeySet();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            final int port = (i % 2 == 0) ? 8081 : 8082;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    HttpResponse<String> r = post(port, "/mpesa/v1/p2p/transfers", "same-key-s12",
                            Map.of("from_wallet", "W001", "to_wallet", "W002", "amount", 10000, "reference", "idem"));
                    if (r.statusCode() == 201) {
                        Map<?, ?> b = mapper.readValue(r.body(), Map.class);
                        transferIds.add((String) b.get("transfer_id"));
                    }
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
        }
        latch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        HttpResponse<String> finalReq = post(8082, "/mpesa/v1/p2p/transfers", "same-key-s12",
                Map.of("from_wallet", "W001", "to_wallet", "W002", "amount", 10000, "reference", "idem"));
        assertEquals(201, finalReq.statusCode());
        Map<?, ?> b = mapper.readValue(finalReq.body(), Map.class);
        transferIds.add((String) b.get("transfer_id"));

        assertEquals(1, transferIds.size());
        assertEquals(990000L, getBalance("W001"));
    }

    // S1.3: 50 transfers W001->W002 and 50 W002->W001 of 1,000, all concurrent (Deadlock test)
    @Test
    @Order(3)
    void testS1_3_BidirectionalNoDeadlock() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(50);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger c201 = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    String from = (idx < 50) ? "W001" : "W002";
                    String to = (idx < 50) ? "W002" : "W001";
                    int port = (idx % 2 == 0) ? 8081 : 8082;
                    HttpResponse<String> r = post(port, "/mpesa/v1/p2p/transfers", "key-s13-" + idx,
                            Map.of("from_wallet", from, "to_wallet", to, "amount", 1000, "reference", "bi"));
                    if (r.statusCode() == 201) c201.incrementAndGet();
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
        }
        latch.countDown();
        for (Future<?> f : futures) f.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(100, c201.get());
        assertEquals(1000000L, getBalance("W001"));
        assertEquals(1000000L, getBalance("W002"));
    }

    // S2.1: 30 concurrent reversals of one transfer across both instances
    @Test
    @Order(4)
    void testS2_1_ConcurrentReversals() throws Exception {
        HttpResponse<String> tr = post(8081, "/mpesa/v1/p2p/transfers", "rev-init",
                Map.of("from_wallet", "W001", "to_wallet", "W002", "amount", 5000, "reference", "r1"));
        String transferId = (String) mapper.readValue(tr.body(), Map.class).get("transfer_id");

        ExecutorService pool = Executors.newFixedThreadPool(30);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger c200 = new AtomicInteger();
        AtomicInteger c409 = new AtomicInteger();

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            final int port = (i % 2 == 0) ? 8081 : 8082;
            futures.add(pool.submit(() -> {
                try {
                    latch.await();
                    HttpResponse<String> r = post(port, "/mpesa/v1/p2p/transfers/" + transferId + "/reversal", null,
                            Map.of("reason", "dispute"));
                    if (r.statusCode() == 200) c200.incrementAndGet();
                    else if (r.statusCode() == 409) c409.incrementAndGet();
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
        }
        latch.countDown();
        for (Future<?> f : futures) f.get();
        pool.shutdown();

        assertEquals(1, c200.get());
        assertEquals(29, c409.get());
        assertEquals(1000000L, getBalance("W001"));
    }

    // Helper methods
    private static HttpResponse<String> post(int port, String path, String idemKey, Map<String, Object> body) throws Exception {
        String json = mapper.writeValueAsString(body);
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (idemKey != null) b.header("Idempotency-Key", idemKey);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static long getBalance(String walletId) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:8081/mpesa/v1/wallets/" + walletId))
                .GET().build();
        HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
        return ((Number) mapper.readValue(r.body(), Map.class).get("balance")).longValue();
    }
}