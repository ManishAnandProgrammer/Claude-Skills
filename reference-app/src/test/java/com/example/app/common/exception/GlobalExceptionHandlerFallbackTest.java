package com.example.app.common.exception;

import com.example.app.common.web.ProblemTypes;
import com.example.app.common.web.RetryAfterProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An {@link IllegalArgumentException} has no mapping of its own: the handler can't tell a bad payload from bad
 * internal data, so a request value is refused on purpose with an {@link AppException}.
 */
class GlobalExceptionHandlerFallbackTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(messages(), new ProblemTypes("reference"),
            new RetryAfterProperties(range(), range(), range(),
                    // media:begin
                    range(),
                    // media:end
                    range()), new ConstraintViolationCodes(List.of()));
    private final WebRequest request = new ServletWebRequest(new MockHttpServletRequest("GET", "/api/v1/todos"));

    private static RetryAfterProperties.Range range() {
        return new RetryAfterProperties.Range(Duration.ofSeconds(1), Duration.ofSeconds(3));
    }

    private static StaticMessageSource messages() {
        StaticMessageSource messages = new StaticMessageSource();
        for (ErrorCode code : ErrorCode.values()) {
            messages.addMessage(code.name(), Locale.getDefault(), code.name());
        }
        return messages;
    }

    @Test
    @DisplayName("An IllegalArgumentException reaching the handler is 500 UNEXPECTED_ERROR, its message not shown")
    void illegalArgumentIsAnUnexpectedError() {
        ResponseEntity<ProblemDetail> response =
                handler.handleUnexpected(new IllegalArgumentException("secret internal detail"), request);

        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertThat(response.getBody().getProperties()).containsEntry("errorCode", "UNEXPECTED_ERROR");
        assertThat(response.getBody().getDetail()).doesNotContain("secret internal detail");
    }
}
