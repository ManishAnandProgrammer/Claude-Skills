package com.example.app.user.service;

import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Counts failed breached-password lookups (no connection, no answer within the timeouts, an error
 * status) and logs one WARN for each. Spring's checker answers "not compromised" on a failed request
 * (its own ERROR log is switched off in config), so this is the only place an outage still shows.
 */
@Slf4j
class FailureCountingInterceptor implements ClientHttpRequestInterceptor {

    private final Counter failures;

    FailureCountingInterceptor(Counter failures) {
        this.failures = failures;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        ClientHttpResponse response;
        try {
            response = execution.execute(request, body);
        } catch (IOException e) {
            countFailure(e.toString());
            throw e;
        }
        if (!response.getStatusCode().is2xxSuccessful()) {
            countFailure("status " + response.getStatusCode().value());
        }
        return response;
    }

    private void countFailure(String cause) {
        failures.increment();
        log.warn("Breached-password lookup failed, signup proceeds without the check: {}", cause);
    }
}
