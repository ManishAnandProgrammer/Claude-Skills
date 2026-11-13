package com.example.app.user.service;

import io.micrometer.core.instrument.Counter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Counts failed breached-password lookups and logs one WARN for each. Spring's checker answers "not
 * compromised" on a failed request (its own ERROR log is switched off in config), so this is the
 * only place an outage still shows. A failure while reading the body (a stalled server cut off by
 * the read timeout) is counted too, once per response.
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
            return response;
        }
        return new BodyFailureCountingResponse(response);
    }

    private void countFailure(String cause) {
        failures.increment();
        log.warn("Breached-password lookup failed, signup proceeds without the check: {}", cause);
    }

    private final class BodyFailureCountingResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private boolean failed;

        private BodyFailureCountingResponse(ClientHttpResponse delegate) {
            this.delegate = delegate;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            return new FilterInputStream(delegate.getBody()) {

                @Override
                public int read() throws IOException {
                    try {
                        return super.read();
                    } catch (IOException e) {
                        onReadFailure(e);
                        throw e;
                    }
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    try {
                        return super.read(buffer, offset, length);
                    } catch (IOException e) {
                        onReadFailure(e);
                        throw e;
                    }
                }
            };
        }

        private void onReadFailure(IOException e) {
            if (!failed) {
                failed = true;
                countFailure(e.toString());
            }
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
