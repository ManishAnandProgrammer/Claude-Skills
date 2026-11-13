package com.example.app.common.admission;

import com.example.app.common.web.ProblemResponses;
import com.example.app.common.web.ProblemTypes;
import com.example.app.common.web.RetryAfterProperties;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.actuate.autoconfigure.endpoint.web.WebEndpointProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionControlFilterTest {

    private static final String API_PATH = "/api/v1/todos";
    private static final String SIGN_IN_PATH = "/api/v1/auth/login";

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AdmissionControlFilter filter = new AdmissionControlFilter(
            new AdmissionGroups(Map.of("sign-in", "/api/v1/auth/"), Duration.ofSeconds(10)), bulkheads(), meterRegistry,
            problemResponses(), new WebEndpointProperties());

    /** One permit per group, no wait: the second request in a group is refused at once. */
    private static BulkheadRegistry bulkheads() {
        BulkheadConfig onePermit = BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build();
        BulkheadRegistry registry = BulkheadRegistry.ofDefaults();
        registry.bulkhead(AdmissionControlFilter.DEFAULT_GROUP, onePermit);
        registry.bulkhead("sign-in", onePermit);
        return registry;
    }

    private static ProblemResponses problemResponses() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("SERVER_BUSY", Locale.getDefault(), "The service is busy. Please retry shortly.");
        return new ProblemResponses(JsonMapper.builder().build(), messages, new ProblemTypes("reference"), retryAfter());
    }

    private double rejected(String group) {
        return meterRegistry.counter(AdmissionControlFilter.REJECTED_METRIC, AdmissionControlFilter.GROUP_TAG, group).count();
    }

    private static RetryAfterProperties retryAfter() {
        RetryAfterProperties.Range busy = new RetryAfterProperties.Range(Duration.ofSeconds(1), Duration.ofSeconds(3));
        return new RetryAfterProperties(busy, busy, busy, busy);
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    @Test
    @DisplayName("Past the cap a request is refused at once with 503 SERVER_BUSY and a jittered Retry-After; the actuator still answers")
    void refusesPastTheCap() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> holdAPermit(API_PATH, inside, release));
            inside.await();

            MockHttpServletResponse refused = new MockHttpServletResponse();
            filter.doFilter(request(API_PATH), refused, new MockFilterChain());
            MockHttpServletResponse health = new MockHttpServletResponse();
            MockFilterChain healthChain = new MockFilterChain();
            filter.doFilter(request("/actuator/health"), health, healthChain);
            release.countDown();
            first.get();

            assertThat(refused.getStatus()).isEqualTo(503);
            assertThat(refused.getContentAsString()).contains("\"errorCode\":\"SERVER_BUSY\"");
            assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 3);
            assertThat(healthChain.getRequest()).isNotNull();
            assertThat(rejected(AdmissionControlFilter.DEFAULT_GROUP)).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("A full sign-in group refuses sign-ins only, counted under its own tag")
    void aFullSignInGroupRefusesSignInsOnly() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> signingIn = executor.submit(() -> holdAPermit(SIGN_IN_PATH, inside, release));
            inside.await();

            MockHttpServletResponse secondSignIn = new MockHttpServletResponse();
            filter.doFilter(request(SIGN_IN_PATH), secondSignIn, new MockFilterChain());
            MockFilterChain apiChain = new MockFilterChain();
            filter.doFilter(request(API_PATH), new MockHttpServletResponse(), apiChain);
            release.countDown();
            signingIn.get();

            assertThat(secondSignIn.getStatus()).isEqualTo(503);
            assertThat(apiChain.getRequest()).isNotNull();
            assertThat(rejected("sign-in")).isEqualTo(1.0);
            assertThat(rejected(AdmissionControlFilter.DEFAULT_GROUP)).isZero();
        }
    }

    @Test
    @DisplayName("A full api group leaves sign-in open")
    void aFullApiGroupLeavesSignInOpen() throws Exception {
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> busyApi = executor.submit(() -> holdAPermit(API_PATH, inside, release));
            inside.await();

            MockFilterChain signInChain = new MockFilterChain();
            filter.doFilter(request(SIGN_IN_PATH), new MockHttpServletResponse(), signInChain);
            release.countDown();
            busyApi.get();

            assertThat(signInChain.getRequest()).isNotNull();
        }
    }

    @Test
    @DisplayName("A group without a configured bulkhead stops startup instead of running on library defaults")
    void refusesAGroupWithoutABulkhead() {
        AdmissionGroups unknown = new AdmissionGroups(Map.of("reports", "/api/v1/reports/"), Duration.ofSeconds(10));

        assertThatThrownBy(() -> new AdmissionControlFilter(unknown, bulkheads(), meterRegistry, problemResponses(),
                new WebEndpointProperties()))
                .hasMessageContaining("resilience4j.bulkhead.instances.reports");
    }

    private Void holdAPermit(String path, CountDownLatch inside, CountDownLatch release) throws Exception {
        FilterChain holding = (request, response) -> {
            inside.countDown();
            awaitQuietly(release);
        };
        filter.doFilter(request(path), new MockHttpServletResponse(), holding);
        return null;
    }

    @Test
    @DisplayName("A request that fails still gives its permit back")
    void releasesThePermitAfterAFailure() throws Exception {
        FilterChain failing = (request, response) -> {
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> filter.doFilter(request(API_PATH), new MockHttpServletResponse(), failing))
                .hasMessage("boom");

        MockHttpServletResponse next = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(API_PATH), next, chain);

        assertThat(chain.getRequest()).isNotNull();
        assertThat(next.getStatus()).isEqualTo(200);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
