package com.example.app.common.logging;

import com.example.app.common.security.CurrentUser;
import com.example.app.common.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Names the signed-in user (their id, never their email) and, with tenancy, their tenant on the
 * request's log lines. Runs inside
 * the security chain, right after the bearer token is verified; {@link RequestLogContextFilter}
 * clears it when the request ends. Not a bean, so the servlet container doesn't register it a
 * second time outside the chain.
 */
@RequiredArgsConstructor
public class UserLogContextFilter extends OncePerRequestFilter {

    private final CurrentUser currentUser;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        currentUser.findId().map(UUID::toString).ifPresent(userId -> MDC.put(LogContext.USER_ID, userId));
        TenantContext.findId().map(UUID::toString).ifPresent(tenantId -> MDC.put(LogContext.TENANT_ID, tenantId));
        currentUser.actorId().map(UUID::toString).ifPresent(actorId -> MDC.put(LogContext.ACTOR_ID, actorId));
        filterChain.doFilter(request, response);
    }
}
