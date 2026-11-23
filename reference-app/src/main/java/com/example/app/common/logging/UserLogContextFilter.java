package com.example.app.common.logging;

import com.example.app.common.security.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Not a bean, so the servlet container doesn't register it a second time outside the chain. */
@RequiredArgsConstructor
public class UserLogContextFilter extends OncePerRequestFilter {

    private final CurrentUser currentUser;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        currentUser.findId().map(UUID::toString).ifPresent(userId -> MDC.put(LogContext.USER_ID, userId));
        filterChain.doFilter(request, response);
    }
}
