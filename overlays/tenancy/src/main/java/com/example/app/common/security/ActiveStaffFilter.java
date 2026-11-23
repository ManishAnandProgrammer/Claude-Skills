package com.example.app.common.security;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/**
 * A database outage propagates to its 503: an unreadable row is never a pass. Not a bean, so the servlet container
 * doesn't register it a second time outside the chain.
 */
@Slf4j
@RequiredArgsConstructor
public class ActiveStaffFilter extends OncePerRequestFilter {

    private final StaffAccounts staffAccounts;
    private final ProblemResponses problemResponses;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token && !isActiveStaff(token.getToken().getSubject())) {
            log.info("Platform token refused, the staff member is deactivated: platformUserId={}", token.getToken().getSubject());
            SecurityContextHolder.clearContext();
            problemResponses.write(request, response, ErrorCode.AUTHENTICATION_REQUIRED);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isActiveStaff(String subject) {
        return Objects.nonNull(subject) && staffAccounts.isActive(UUID.fromString(subject));
    }
}
