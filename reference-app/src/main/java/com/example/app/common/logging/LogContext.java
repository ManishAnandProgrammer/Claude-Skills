package com.example.app.common.logging;

import org.slf4j.MDC;

/**
 * The MDC keys a request's context rides on, printed on every log line by
 * {@code logback-spring.xml} and included in production's JSON: which request, which endpoint,
 * from where, for which user. {@code traceId} and {@code spanId} come from Micrometer Tracing
 * (Brave), which keeps those two itself.
 *
 * <p>{@link RequestLogContextFilter} sets the first three when a request arrives and clears them
 * all when it ends; {@link UserLogContextFilter} adds the user once the token is verified. The MDC
 * is thread-local, so clearing every key in one place keeps one request's context off the next.</p>
 */
public final class LogContext {

    public static final String REQUEST_ID = "requestId";
    public static final String ENDPOINT = "endpoint";
    public static final String CLIENT_IP = "clientIp";
    public static final String USER_ID = "userId";

    private LogContext() {
    }

    static void clearRequest() {
        MDC.remove(REQUEST_ID);
        MDC.remove(ENDPOINT);
        MDC.remove(CLIENT_IP);
        MDC.remove(USER_ID);
    }
}
