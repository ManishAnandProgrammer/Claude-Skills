package com.example.app.user;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The session version over HTTP (the session-version piece): signing out everywhere cuts every device's
 * access token at once, while sign-ins and refreshes leave the number alone.
 */
class SessionVersionIntegrationTest extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String SESSIONS = "/api/v1/users/me/sessions";

    private ResultActions refresh(String refreshToken) throws Exception {
        return postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken));
    }

    @Test
    @DisplayName("""
            Signing out everywhere is 204; then both devices' access tokens are 401 SESSION_VERSION_STALE on their \
            next request and both refresh tokens 401; a new sign-in works""")
    void signingOutEverywhereCutsEveryDevice() throws Exception {
        TestUser phone = newUser();
        TestUser laptop = signIn(phone);
        getAs(laptop.accessToken(), ME).andExpect(status().isOk());

        deleteAs(phone.accessToken(), SESSIONS).andExpect(status().isNoContent());

        for (String accessToken : List.of(phone.accessToken(), laptop.accessToken())) {
            getAs(accessToken, ME)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("SESSION_VERSION_STALE"))
                    .andExpect(jsonPath("$.type").value(problemType("SESSION_VERSION_STALE")));
        }
        for (String refreshToken : List.of(phone.refreshToken(), laptop.refreshToken())) {
            refresh(refreshToken)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        }
        getAs(signIn(phone).accessToken(), ME).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Sign-ins and refreshes raise nothing: an account's earlier access token keeps working after both")
    void tokenIssuesLeaveTheVersionAlone() throws Exception {
        TestUser first = newUser();
        TestUser second = signIn(first);

        refresh(second.refreshToken()).andExpect(status().isOk());

        getAs(first.accessToken(), ME).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Signing out everywhere without a token is 401 AUTHENTICATION_REQUIRED")
    void signingOutEverywhereNeedsAToken() throws Exception {
        deleteAs(null, SESSIONS)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("The API docs name SESSION_VERSION_STALE on the signed-in endpoints' 401, not on login's")
    void apiDocsNameTheStaleVersion() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/users/me'].get.responses['401'].description",
                        containsString("SESSION_VERSION_STALE")))
                .andExpect(jsonPath("$.paths['/api/v1/users/me/sessions'].delete.responses['401'].description",
                        containsString("SESSION_VERSION_STALE")))
                .andExpect(jsonPath("$.paths['/api/v1/auth/login'].post.responses['401'].description",
                        not(containsString("SESSION_VERSION_STALE"))));
    }
}
