package com.example.app.user.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** The real Have I Been Pwned checker, wired as in production, against a local range API. */
class BreachedPasswordConfigTest {

    // SHA-1 of "Password123!" is 49EFE F5F70D47ADC2DB2EB397FBEF5F7BC560E29: the API is asked for the
    // prefix and answers suffixes with their breach counts.
    private static final String BREACHED_PASSWORD = "Password123!";
    private static final String RANGE_49EFE = """
            F5F70D47ADC2DB2EB397FBEF5F7BC560E29:295527
            0018A45C4D1DEF81644B54AB7F969B88D65:1
            """;
    private static final Duration READ_TIMEOUT = Duration.ofMillis(500);

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final CountDownLatch releaseStalledServer = new CountDownLatch(1);
    private final AtomicReference<String> addPadding = new AtomicReference<>();
    private HttpServer server;

    @AfterEach
    void stopServer() {
        releaseStalledServer.countDown();
        if (Objects.nonNull(server)) {
            server.stop(0);
        }
    }

    private String rangeApi(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/range/", handler);
        server.start();
        return "http://127.0.0.1:%d/range/".formatted(server.getAddress().getPort());
    }

    private String rangeApi(int status, String body) throws IOException {
        return rangeApi(exchange -> {
            addPadding.set(exchange.getRequestHeaders().getFirst("Add-Padding"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private void stallAfterTheHeaders(HttpExchange exchange) throws IOException {
        byte[] bytes = RANGE_49EFE.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        OutputStream out = exchange.getResponseBody();
        out.write(bytes, 0, 10);
        out.flush();
        try {
            releaseStalledServer.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        exchange.close();
    }

    private CompromisedPasswordChecker checker(String apiUrl, ObservationRegistry observationRegistry) {
        BreachedPasswordProperties properties = new BreachedPasswordProperties(true, apiUrl,
                Duration.ofSeconds(1), READ_TIMEOUT);
        return new BreachedPasswordConfig().compromisedPasswordChecker(properties, meterRegistry, observationRegistry);
    }

    private CompromisedPasswordChecker checker(String apiUrl) {
        return checker(apiUrl, ObservationRegistry.NOOP);
    }

    private double failures() {
        return meterRegistry.counter(BreachedPasswordCheck.UNAVAILABLE_METRIC).count();
    }

    @Test
    @DisplayName("A password whose hash suffix the range API lists is compromised; another is not; padding is asked for")
    void findsABreachedPasswordThroughTheRangeApi() throws IOException {
        CompromisedPasswordChecker checker = checker(rangeApi(200, RANGE_49EFE));

        assertThat(checker.check(BREACHED_PASSWORD).isCompromised()).isTrue();
        assertThat(checker.check("Qz8-a91f3c-Vw-x7").isCompromised()).isFalse();
        assertThat(failures()).isZero();
        assertThat(addPadding.get()).isEqualTo("true");
    }

    @Test
    @DisplayName("Each lookup is observed as http.client.requests")
    void lookupsAreObserved() throws IOException {
        ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(new DefaultMeterObservationHandler(meterRegistry));

        checker(rangeApi(200, RANGE_49EFE), observationRegistry).check(BREACHED_PASSWORD);

        assertThat(meterRegistry.get("http.client.requests").timer().count()).isOne();
    }

    @Test
    @DisplayName("An error answer or an unreachable service passes the password and is counted")
    void failuresPassThePasswordAndAreCounted() throws IOException {
        assertThat(checker(rangeApi(503, "")).check(BREACHED_PASSWORD).isCompromised()).isFalse();
        assertThat(failures()).isEqualTo(1);

        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertThat(checker("http://127.0.0.1:%d/range/".formatted(closedPort)).check(BREACHED_PASSWORD).isCompromised())
                .isFalse();
        assertThat(failures()).isEqualTo(2);
    }

    @Test
    @DisplayName("A server that sends the headers and then stalls is cut off by the read timeout and counted once")
    void aStalledBodyIsCutOffAndCounted() throws IOException {
        CompromisedPasswordChecker checker = checker(rangeApi(this::stallAfterTheHeaders));
        long started = System.nanoTime();

        boolean compromised = checker.check(BREACHED_PASSWORD).isCompromised();

        Duration took = Duration.ofNanos(System.nanoTime() - started);
        assertThat(compromised).isFalse();
        assertThat(failures()).isEqualTo(1);
        assertThat(took).isLessThan(READ_TIMEOUT.plusSeconds(1));
    }
}
