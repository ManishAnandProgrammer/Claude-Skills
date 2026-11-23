package com.example.app.common.logging;

import org.slf4j.MDC;

/**
 * The MDC is thread-local, so clearing every key in one place keeps one request's context off the next.
 * {@code traceId} and {@code spanId} are Micrometer Tracing's (Brave), which keeps those two itself.
 */
public final class LogContext {

    public static final String REQUEST_ID = "requestId";
    public static final String ENDPOINT = "endpoint";
    public static final String CLIENT_IP = "clientIp";
    public static final String USER_ID = "userId";
    public static final String TENANT_ID = "tenantId";
    public static final String ACTOR_ID = "actorId";

    private LogContext() {
    }

    static void clearRequest() {
        MDC.remove(REQUEST_ID);
        MDC.remove(ENDPOINT);
        MDC.remove(CLIENT_IP);
        MDC.remove(USER_ID);
        MDC.remove(TENANT_ID);
        MDC.remove(ACTOR_ID);
    }
}
