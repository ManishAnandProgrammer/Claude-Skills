package com.example.app.common.security;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.exception.TransientDatabaseFailure;
import com.example.app.common.web.ProblemResponses;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * A session version that can't be read is never a pass: a database outage behind a Redis miss answers its 503,
 * anything else propagates. The refusal is written here, as the security chain's other errors are: no handler
 * runs yet. Not a bean, so the servlet container doesn't register it a second time outside the chain.
 */
@Slf4j
@RequiredArgsConstructor
public class SessionVersionFilter extends OncePerRequestFilter {

    private final SessionVersions sessionVersions;
    private final ProblemResponses problemResponses;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            Optional<ErrorCode> refusal = refusalOf(token.getToken());
            if (refusal.isPresent()) {
                SecurityContextHolder.clearContext();
                problemResponses.write(request, response, refusal.get());
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    private Optional<ErrorCode> refusalOf(Jwt token) {
        try {
            if (sessionVersions.isCurrent(token)) {
                return Optional.empty();
            }
        } catch (RuntimeException failure) {
            Optional<TransientDatabaseFailure> outage = TransientDatabaseFailure.of(failure);
            if (outage.isEmpty()) {
                throw failure;
            }
            log.warn("Session version unreadable, request refused: code={}, cause={}", outage.get().errorCode(), failure.toString());
            return Optional.of(outage.get().errorCode());
        }
        log.info("Access token refused, its session version is not current: userId={}, sv={}",
                token.getSubject(), token.getClaims().get(SessionVersions.CLAIM));
        return Optional.of(ErrorCode.SESSION_VERSION_STALE);
    }
}
