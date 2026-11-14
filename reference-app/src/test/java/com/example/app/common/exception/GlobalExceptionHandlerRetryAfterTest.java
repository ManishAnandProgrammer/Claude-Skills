package com.example.app.common.exception;

import com.example.app.common.web.ProblemTypes;
import com.example.app.common.web.RetryAfterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** Every 503 tells the client when to come back, whichever path produced it (ERR-02). */
class GlobalExceptionHandlerRetryAfterTest {

    private static final RetryAfterProperties.Range BUSY = range(1, 3);
    private static final RetryAfterProperties.Range UNAVAILABLE = range(4, 8);

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(messages(), new ProblemTypes("reference"),
            new RetryAfterProperties(BUSY, range(1, 3), range(3, 8),
                    // media:begin
                    range(4, 8),
                    // media:end
                    UNAVAILABLE), new ConstraintViolationCodes(List.of()));
    private final WebRequest request = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/v1/todos"));

    private static RetryAfterProperties.Range range(long minSeconds, long maxSeconds) {
        return new RetryAfterProperties.Range(Duration.ofSeconds(minSeconds), Duration.ofSeconds(maxSeconds));
    }

    private static StaticMessageSource messages() {
        StaticMessageSource messages = new StaticMessageSource();
        for (ErrorCode code : ErrorCode.values()) {
            messages.addMessage(code.name(), Locale.getDefault(), code.name());
        }
        return messages;
    }

    private static long retryAfterSeconds(ResponseEntity<?> response) {
        return Long.parseLong(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("An AppException with a 503 code answers a Retry-After from its code's range")
    void appExceptionWith503CodeCarriesRetryAfter() {
        ResponseEntity<ProblemDetail> busy = handler.handleAppException(AppException.of(ErrorCode.SERVER_BUSY), request);
        ResponseEntity<ProblemDetail> unavailable =
                handler.handleAppException(AppException.of(ErrorCode.DATABASE_UNAVAILABLE), request);

        assertThat(busy.getStatusCode().value()).isEqualTo(503);
        assertThat(retryAfterSeconds(busy)).isBetween(BUSY.min().toSeconds(), BUSY.max().toSeconds());
        assertThat(retryAfterSeconds(unavailable)).isBetween(UNAVAILABLE.min().toSeconds(), UNAVAILABLE.max().toSeconds());
    }

    @Test
    @DisplayName("An async request timeout answers 503 SERVER_BUSY with a Retry-After")
    void asyncTimeoutCarriesRetryAfter() throws Exception {
        ResponseEntity<Object> response = handler.handleException(new AsyncRequestTimeoutException(), request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(((ProblemDetail) response.getBody()).getProperties()).containsEntry("errorCode", "SERVER_BUSY");
        assertThat(retryAfterSeconds(response)).isBetween(BUSY.min().toSeconds(), BUSY.max().toSeconds());
    }

    @Test
    @DisplayName("A client error carries no Retry-After")
    void clientErrorHasNoRetryAfter() {
        ResponseEntity<ProblemDetail> notFound = handler.handleAppException(AppException.of(ErrorCode.USER_NOT_FOUND), request);

        assertThat(notFound.getHeaders().containsHeader(HttpHeaders.RETRY_AFTER)).isFalse();
    }
}
