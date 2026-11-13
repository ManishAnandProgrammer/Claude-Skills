package com.example.app.common.admission;

import com.example.app.common.web.ProblemResponses;
import com.example.app.common.web.ProblemTypes;
import com.example.app.common.web.RetryAfterProperties;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionControlFilterTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final AdmissionControlFilter filter = new AdmissionControlFilter(
            new AdmissionProperties(1, Duration.ZERO), meterRegistry, problemResponses(), retryAfter(), new WebEndpointProperties());

    private static ProblemResponses problemResponses() {
        StaticMessageSource messages = new StaticMessageSource();
        messages.addMessage("SERVER_BUSY", Locale.getDefault(), "The service is busy. Please retry shortly.");
        return new ProblemResponses(JsonMapper.builder().build(), messages, new ProblemTypes("reference"));
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
        FilterChain holding = (request, response) -> {
            inside.countDown();
            awaitQuietly(release);
        };
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> first = executor.submit(() -> {
                filter.doFilter(request("/api/v1/todos"), new MockHttpServletResponse(), holding);
                return null;
            });
            inside.await();

            MockHttpServletResponse refused = new MockHttpServletResponse();
            filter.doFilter(request("/api/v1/todos"), refused, new MockFilterChain());
            MockHttpServletResponse health = new MockHttpServletResponse();
            MockFilterChain healthChain = new MockFilterChain();
            filter.doFilter(request("/actuator/health"), health, healthChain);
            release.countDown();
            first.get();

            assertThat(refused.getStatus()).isEqualTo(503);
            assertThat(refused.getContentAsString()).contains("\"errorCode\":\"SERVER_BUSY\"");
            assertThat(Integer.parseInt(refused.getHeader("Retry-After"))).isBetween(1, 3);
            assertThat(healthChain.getRequest()).isNotNull();
            assertThat(meterRegistry.counter(AdmissionControlFilter.REJECTED_METRIC).count()).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("A request that fails still gives its permit back")
    void releasesThePermitAfterAFailure() throws Exception {
        FilterChain failing = (request, response) -> {
            throw new IllegalStateException("boom");
        };
        assertThatThrownBy(() -> filter.doFilter(request("/api/v1/todos"), new MockHttpServletResponse(), failing))
                .hasMessage("boom");

        MockHttpServletResponse next = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request("/api/v1/todos"), next, chain);

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
