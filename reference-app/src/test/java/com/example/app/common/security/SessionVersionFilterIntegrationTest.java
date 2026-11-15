package com.example.app.common.security;

import com.example.app.common.web.ProblemResponses;
import com.example.app.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The filter's answer when the session version can't be read at all (Redis missed and the database is
 * down): a 503 the client retries, never a pass (SEC-06). The 401 and the pass are the HTTP tests'.
 */
class SessionVersionFilterIntegrationTest extends IntegrationTest {

    @Autowired
    private ProblemResponses problemResponses;

    @AfterEach
    void clearTheSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signedIn() {
        Jwt token = Jwt.withTokenValue("token").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .claim(SessionVersions.CLAIM, 0).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    @Test
    @DisplayName("An unreadable session version (the database down) is 503 DATABASE_UNAVAILABLE with Retry-After; the request goes no further")
    void anUnreadableVersionIs503() throws Exception {
        SessionVersionFilter filter = new SessionVersionFilter(token -> {
            throw new DataAccessResourceFailureException("connection refused");
        }, problemResponses);
        signedIn();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/users/me"), response, chain);

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isNotBlank();
        String errorCode = JsonPath.read(response.getContentAsString(), "$.errorCode");
        assertThat(errorCode).isEqualTo("DATABASE_UNAVAILABLE");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @DisplayName("Any other failure of the check propagates (a 500), never a pass")
    void anotherFailurePropagates() {
        SessionVersionFilter filter = new SessionVersionFilter(token -> {
            throw new IllegalStateException("a bug");
        }, problemResponses);
        signedIn();
        MockFilterChain chain = new MockFilterChain();

        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/users/me"),
                new MockHttpServletResponse(), chain)).isInstanceOf(IllegalStateException.class);
        assertThat(chain.getRequest()).isNull();
    }
}
