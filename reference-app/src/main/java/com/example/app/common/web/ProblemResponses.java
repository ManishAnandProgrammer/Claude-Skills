package com.example.app.common.web;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.ErrorCodeHttpStatusMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Writes Problem Details from servlet filters (the security chain, admission control), where
 * {@code GlobalExceptionHandler} can't reach: the same members, so a client parses a 401 from the
 * filter chain like a 401 from a controller, and a 503 carries a jittered {@code Retry-After} from its
 * code's range. Written from a map, so it needs no Jackson mix-in.
 */
@Component
@RequiredArgsConstructor
public class ProblemResponses {

    private final ObjectMapper objectMapper;
    private final MessageSource messageSource;
    private final ProblemTypes problemTypes;
    private final RetryAfterProperties retryAfter;

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        HttpStatus status = ErrorCodeHttpStatusMapper.resolve(code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problemTypes.typeOf(code).toString());
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", messageSource.getMessage(code.name(), null, LocaleContextHolder.getLocale()));
        body.put("instance", request.getRequestURI());
        body.put(ProblemTypes.ERROR_CODE, code.name());
        Optional<RetryAfterProperties.Range> wait = retryAfter.rangeFor(code);
        if (wait.isPresent()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, wait.get().pickSeconds());
        }
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
