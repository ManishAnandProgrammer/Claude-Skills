package com.example.app.user;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTest {

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MeterRegistry meterRegistry;

    private ResultActions refresh(String refreshToken) throws Exception {
        return postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken));
    }

    @Test
    @DisplayName("Signup answers 201 with the account, Location, and the email stored lower-case")
    void signupCreatesTheAccount() throws Exception {
        String email = uniqueEmail("Signup").toUpperCase();

        signup(email, PASSWORD)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/users/me"))
                .andExpect(jsonPath("$.response.email").value(email.toLowerCase()))
                .andExpect(jsonPath("$.response.role").value("USER"))
                .andExpect(jsonPath("$.response.version").value(0))
                .andExpect(jsonPath("$.response.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.response.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("Signup with an email already registered, in any case, is 409 USER_EMAIL_TAKEN")
    void signupWithATakenEmailIs409() throws Exception {
        String email = uniqueEmail("taken");
        signup(email, PASSWORD).andExpect(status().isCreated());

        signup(email.toUpperCase(), PASSWORD)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("USER_EMAIL_TAKEN"))
                .andExpect(jsonPath("$.detail").value(not(containsString("uq_users_email"))));
    }

    @Test
    @DisplayName("Concurrent signups with one email create exactly one account")
    void concurrentSignupsWithOneEmailCreateOneAccount() throws Exception {
        String email = uniqueEmail("race");
        int attempts = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                Callable<Integer> signupOnce = () -> {
                    start.await();
                    return signup(email, PASSWORD).andReturn().getResponse().getStatus();
                };
                statuses.add(executor.submit(signupOnce));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(status.get());
            }
            assertThat(results).containsOnlyOnce(201).containsOnly(201, 409);
        }
        Integer accounts = jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email);
        assertThat(accounts).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Sh0rt!", "alllowercase1!", "ALLUPPERCASE1!", "NoDigits!!", "NoSpecial123"})
    @DisplayName("Signup refuses a password that doesn't meet the rules, naming the field")
    void signupWithAWeakPasswordIs400(String password) throws Exception {
        signup(uniqueEmail("weak"), password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value(containsString("password")));
    }

    @Test
    @DisplayName("Signup refuses a password over 72 bytes, even when it is under 72 characters")
    void signupWithAPasswordOver72BytesIs400() throws Exception {
        String password = "Aa1!" + "ਪ".repeat(23);

        signup(uniqueEmail("long"), password)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.failed[0]").value(containsString("password")));
    }

    @Test
    @DisplayName("Signup refuses an unknown field instead of ignoring it")
    void signupWithAnUnknownFieldIs400() throws Exception {
        postAs(null, "/api/v1/auth/signup", """
                {"email": "%s", "fullName": "Test User", "password": "%s", "role": "ADMIN"}
                """.formatted(uniqueEmail("unknown"), PASSWORD))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_UNREADABLE"));
    }

    @Test
    @DisplayName("Login returns an access token that opens /users/me, and a refresh token")
    void loginReturnsTokens() throws Exception {
        TestUser user = newUser();

        getAs(user.accessToken(), "/api/v1/users/me")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.id").value(user.id().toString()))
                .andExpect(jsonPath("$.response.email").value(user.email()));
        assertThat(user.refreshToken()).hasSizeGreaterThan(40);
    }

    @Test
    @DisplayName("A wrong password and an unknown email get the same 401 AUTH_INVALID_CREDENTIALS")
    void loginRefusalsLookTheSame() throws Exception {
        TestUser user = newUser();

        String wrongPassword = login(user.email(), "Wrong-Password-1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = login(uniqueEmail("nobody"), "Wrong-Password-1").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"))
                .andReturn().getResponse().getContentAsString();
        assertThat(wrongPassword).isEqualTo(unknownEmail);
    }

    @Test
    @DisplayName("Login is limited per account: the sixth attempt in a minute is 429 with Retry-After")
    void loginIsRateLimitedPerAccount() throws Exception {
        String email = uniqueEmail("limited");
        signup(email, PASSWORD).andExpect(status().isCreated());
        for (int attempt = 0; attempt < 5; attempt++) {
            login(email, "Wrong-Password-1").andExpect(status().isUnauthorized());
        }

        login(email, PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    @Test
    @DisplayName("Refresh rotates: new tokens work, the used refresh token is refused")
    void refreshRotatesTheToken() throws Exception {
        TestUser user = newUser();

        ResultActions rotated = refresh(user.refreshToken()).andExpect(status().isOk());
        String newAccess = read(rotated, "$.response.accessToken");
        getAs(newAccess, "/api/v1/users/me").andExpect(status().isOk());
        refresh(user.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("Reusing a rotated refresh token after the grace window revokes every session of the account")
    void reusingARefreshTokenRevokesEverySession() throws Exception {
        TestUser user = newUser();
        String secondSession = read(login(user.email(), PASSWORD).andExpect(status().isOk()), "$.response.refreshToken");
        refresh(user.refreshToken()).andExpect(status().isOk());
        sql("update refresh_tokens set revoked_at = revoked_at - interval '1 minute' where revocation_reason = 'ROTATED' and user_id = ?",
                user.id());

        refresh(user.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));

        refresh(secondSession)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
    }

    @Test
    @DisplayName("Logout revokes the refresh token and answers 204, also for an unknown token")
    void logoutRevokesTheRefreshToken() throws Exception {
        TestUser user = newUser();

        postAs(null, "/api/v1/auth/logout", """
                {"refreshToken": "%s"}
                """.formatted(user.refreshToken())).andExpect(status().isNoContent());
        refresh(user.refreshToken()).andExpect(status().isUnauthorized());
        postAs(null, "/api/v1/auth/logout", """
                {"refreshToken": "unknown-token"}
                """).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("/users/me without a token, or with a forged one, is 401 AUTHENTICATION_REQUIRED")
    void meNeedsAValidToken() throws Exception {
        TestUser user = newUser();
        String forged = user.accessToken().substring(0, user.accessToken().length() - 4) + "AAAA";

        getAs(null, "/api/v1/users/me")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.type").value("urn:reference:error:AUTHENTICATION_REQUIRED"));
        getAs(forged, "/api/v1/users/me")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    @DisplayName("The API docs list the sign-in endpoints")
    void apiDocsDescribeSignIn() throws Exception {
        getAs(null, "/v3/api-docs")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/auth/signup'].post.responses['409']").exists())
                .andExpect(jsonPath("$.tags[*].name", hasItem("Sign-in")));
    }

    @Test
    @DisplayName("Concurrent refreshes with one token: exactly one succeeds, and the account keeps one new session")
    void concurrentRefreshesWithOneTokenSucceedOnce() throws Exception {
        TestUser user = newUser();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> statuses = new ArrayList<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int attempt = 0; attempt < 5; attempt++) {
                Callable<Integer> refreshOnce = () -> {
                    start.await();
                    return refresh(user.refreshToken()).andReturn().getResponse().getStatus();
                };
                statuses.add(executor.submit(refreshOnce));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(status.get());
            }
            assertThat(results).containsOnlyOnce(200).containsOnly(200, 401);
        }
        assertThat(liveRefreshTokens(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("A duplicate refresh within the grace window is refused but keeps the new session")
    void duplicateRefreshWithinGraceKeepsTheSession() throws Exception {
        TestUser user = newUser();
        String rotated = read(refresh(user.refreshToken()).andExpect(status().isOk()), "$.response.refreshToken");

        refresh(user.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        refresh(rotated).andExpect(status().isOk());
    }

    @Test
    @DisplayName("Expired, or signed by another issuer: the access token is refused with 401")
    void expiredOrForeignAccessTokensAreRefused() throws Exception {
        TestUser user = newUser();
        Instant now = Instant.now();
        String expired = accessToken(user, "reference", now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(1)));
        String foreign = accessToken(user, "someone-else", now, now.plus(Duration.ofMinutes(5)));

        getAs(expired, "/api/v1/users/me")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(foreign, "/api/v1/users/me")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(accessToken(user, "reference", now, now.plus(Duration.ofMinutes(5))), "/api/v1/users/me")
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("An expired refresh token is 401 AUTH_INVALID_REFRESH_TOKEN")
    void expiredRefreshTokenIsRefused() throws Exception {
        TestUser user = newUser();
        sql("update refresh_tokens set expires_at = now() - interval '1 minute' where user_id = ?", user.id());

        refresh(user.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSignups")
    @DisplayName("Signup refuses each invalid field with 400 VALIDATION_FAILED naming it")
    void signupRefusesInvalidFields(String field, String body) throws Exception {
        postAs(null, "/api/v1/auth/signup", body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed", hasItem(startsWith(field + ":"))));
    }

    static Stream<Arguments> invalidSignups() {
        String valid = uniqueEmail("valid");
        return Stream.of(
                Arguments.of("email", signupBody("not-an-email", "Test User", PASSWORD)),
                Arguments.of("email", signupBody("ännä@example.com", "Test User", PASSWORD)),
                Arguments.of("email", signupBody("a".repeat(250) + "@example.com", "Test User", PASSWORD)),
                Arguments.of("fullName", signupBody(valid, " ", PASSWORD)),
                Arguments.of("fullName", signupBody(valid, "n".repeat(101), PASSWORD)));
    }

    private static String signupBody(String email, String fullName, String password) {
        return """
                {"email": "%s", "fullName": "%s", "password": "%s"}
                """.formatted(email, fullName, password);
    }

    @Test
    @DisplayName("Signup accepts the edges: an 8-character password, and one of exactly 72 bytes")
    void signupAcceptsThePasswordEdges() throws Exception {
        signup(uniqueEmail("eight"), "Abcde1!x").andExpect(status().isCreated());
        signup(uniqueEmail("seventytwo"), "Aa1!" + "ਪ".repeat(22) + "xx").andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Refused logins are counted on auth.login.refused")
    void refusedLoginsAreCounted() throws Exception {
        double before = meterRegistry.counter("auth.login.refused").count();

        login(uniqueEmail("nobody"), "Wrong-Password-1").andExpect(status().isUnauthorized());

        assertThat(meterRegistry.counter("auth.login.refused").count()).isEqualTo(before + 1);
    }

    private int liveRefreshTokens(TestUser user) {
        Integer live = jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, user.id());
        return live;
    }

    private String accessToken(TestUser user, String issuer, Instant issuedAt, Instant expiresAt) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(user.id().toString())
                .claim("roles", List.of("USER"))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
