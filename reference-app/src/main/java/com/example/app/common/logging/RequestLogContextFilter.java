package com.example.app.common.logging;

import com.example.app.common.security.PersonalDataHasher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The endpoint is the method and path, never the query string, which can carry a token. The address is
 * {@code getRemoteAddr()}: with {@code server.forward-headers-strategy: native} that is the client behind the
 * trusted proxy. {@code X-Forwarded-For} is never read here, since any client can write anything into it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
class RequestLogContextFilter extends OncePerRequestFilter {

    static final String HEADER_NAME = "X-Request-ID";
    /** Letters, digits, dot, underscore and dash: nothing that can forge or garble a log line or header. */
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");

    private final LoggingProperties properties;
    private final PersonalDataHasher hasher;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = requestIdFrom(request);
        response.setHeader(HEADER_NAME, requestId);
        MDC.put(LogContext.REQUEST_ID, requestId);
        MDC.put(LogContext.ENDPOINT, request.getMethod() + " " + request.getRequestURI());
        MDC.put(LogContext.CLIENT_IP, properties.clientIp().render(request.getRemoteAddr(), hasher));
        try {
            filterChain.doFilter(request, response);
        } finally {
            LogContext.clearRequest();
        }
    }

    private static String requestIdFrom(HttpServletRequest request) {
        String requestId = request.getHeader(HEADER_NAME);
        boolean safe = Objects.nonNull(requestId) && SAFE_REQUEST_ID.matcher(requestId).matches();
        return safe ? requestId : UUID.randomUUID().toString();
    }
}
