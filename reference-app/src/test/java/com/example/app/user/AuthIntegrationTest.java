package com.example.app.user;

import com.example.app.support.DistinctClientAddresses;
import com.example.app.support.IntegrationTest;
import com.example.app.support.StubBreachedPasswords;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.util.concurrent.Future;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuthIntegrationTest extends IntegrationTest {

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private BulkheadRegistry bulkheads;

    @Value("${app.jwt.issuer}")
    private String issuer;

    @Value("${app.rate-limit.sign-in.login-per-ip.requests}")
    private int loginPerIp;

    @Value("${app.rate-limit.sign-in.login-per-account.requests}")
    private int loginPerAccount;

    @Value("${app.rate-limit.sign-in.signup-per-ip.requests}")
    private int signupPerIp;

    @Value("${app.rate-limit.sign-in.refresh-per-ip.requests}")
    private int refreshPerIp;

    @Value("${app.rate-limit.sign-in.logout-per-ip.requests}")
    private int logoutPerIp;

    private ResultActions refresh(String refreshToken) throws Exception {
        return postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken));
    }

    /** A request from one pinned client address, to trip a per-IP limit (see DistinctClientAddresses). */
    private ResultActions postFrom(String clientIp, String path, String json) throws Exception {
        return mvc.perform(post(path).remoteAddress(clientIp).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions loginFrom(String clientIp, String email, String password) throws Exception {
        return postFrom(clientIp, "/api/v1/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password));
    }

    @FunctionalInterface
    private interface Attempt {
        ResultActions send() throws Exception;
    }

    /**
     * Sends the attempt until it is refused and returns how many got through first. The bucket
     * refills gradually (a 20-a-minute limit gains a token every 3 seconds), so a slow run may get a
     * few more through than the limit, never fewer.
     */
    private static int attemptsBeforeTheLimit(int limit, Attempt attempt) throws Exception {
        for (int sent = 0; sent < 2 * limit; sent++) {
            ResultActions result = attempt.send();
            if (result.andReturn().getResponse().getStatus() == HttpStatus.TOO_MANY_REQUESTS.value()) {
                assertRateLimited(result);
                return sent;
            }
        }
        throw new AssertionError("Not rate limited after %d attempts".formatted(2 * limit));
    }

    private static void assertRateLimited(ResultActions result) throws Exception {
        result.andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));
    }

    private double breachCheckUnavailable() {
        return meterRegistry.counter(StubBreachedPasswords.UNAVAILABLE_METRIC).count();
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
        try (ExecutorService executor = newExecutor()) {
            for (int attempt = 0; attempt < attempts; attempt++) {
                Callable<Integer> signupOnce = () -> {
                    awaitLatch(start);
                    return signup(email, PASSWORD).andReturn().getResponse().getStatus();
                };
                statuses.add(executor.submit(signupOnce));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(within(status));
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
    @DisplayName("Signup refuses a 7-character password with a message naming the minimum, not a Java bound")
    void signupWithAShortPasswordNamesTheMinimum() throws Exception {
        signup(uniqueEmail("short"), "Sh0rt!x")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.failed[0]").value("password: must be at least 8 characters"));
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
    @DisplayName("Signup with a password from a known breach is 400 USER_PASSWORD_BREACHED and creates nothing")
    void signupWithABreachedPasswordIs400() throws Exception {
        String email = uniqueEmail("breached");

        signup(email, StubBreachedPasswords.BREACHED + "-Horse-9")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("USER_PASSWORD_BREACHED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isZero();

        signup(email, PASSWORD).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email)).isOne();
    }

    @Test
    @DisplayName("While the breached-password check is unreachable, signup proceeds and the outage is counted")
    void signupProceedsWhileTheBreachCheckIsUnavailable() throws Exception {
        double before = breachCheckUnavailable();

        signup(uniqueEmail("unreachable"), StubBreachedPasswords.UNREACHABLE + "-Horse-9").andExpect(status().isCreated());

        assertThat(breachCheckUnavailable()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("While the breached-password bulkhead is full, signup skips the check at once and counts it")
    void signupSkipsTheBreachCheckWhileTheBulkheadIsFull() throws Exception {
        Bulkhead bulkhead = bulkheads.bulkhead("breached-passwords");
        assertThat(bulkhead.getBulkheadConfig().getMaxWaitDuration()).isZero();
        int permits = bulkhead.getBulkheadConfig().getMaxConcurrentCalls();
        double before = breachCheckUnavailable();
        for (int held = 0; held < permits; held++) {
            assertThat(bulkhead.tryAcquirePermission()).isTrue();
        }
        try {
            signup(uniqueEmail("bulkhead"), StubBreachedPasswords.BREACHED + "-Horse-9").andExpect(status().isCreated());
        } finally {
            for (int held = 0; held < permits; held++) {
                bulkhead.onComplete();
            }
        }

        assertThat(breachCheckUnavailable()).isEqualTo(before + 1);
        signup(uniqueEmail("bulkhead"), StubBreachedPasswords.BREACHED + "-Horse-9").andExpect(status().isBadRequest());
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
    @DisplayName("Login is limited per account: one past the limit is 429 with Retry-After")
    void loginIsRateLimitedPerAccount() throws Exception {
        String email = uniqueEmail("limited");
        signup(email, PASSWORD).andExpect(status().isCreated());
        for (int attempt = 0; attempt < loginPerAccount; attempt++) {
            login(email, "Wrong-Password-1").andExpect(status().isUnauthorized());
        }

        assertRateLimited(login(email, PASSWORD));
    }

    @Test
    @DisplayName("Another account still logs in from the same address while one account is limited")
    void aLimitedAccountDoesNotLimitOthers() throws Exception {
        String clientIp = DistinctClientAddresses.next();
        String limited = uniqueEmail("limited");
        String other = uniqueEmail("other");
        signup(limited, PASSWORD).andExpect(status().isCreated());
        signup(other, PASSWORD).andExpect(status().isCreated());
        for (int attempt = 0; attempt < loginPerAccount; attempt++) {
            loginFrom(clientIp, limited, "Wrong-Password-1").andExpect(status().isUnauthorized());
        }
        assertRateLimited(loginFrom(clientIp, limited, PASSWORD));

        loginFrom(clientIp, other, PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.accessToken").isNotEmpty());
    }

    @Test
    @DisplayName("Login is limited per client address, across accounts: once the limit is used up, 429 with Retry-After")
    void loginIsRateLimitedPerIp() throws Exception {
        String clientIp = DistinctClientAddresses.next();
        int accepted = attemptsBeforeTheLimit(loginPerIp,
                () -> loginFrom(clientIp, uniqueEmail("nobody"), "Wrong-Password-1"));

        assertThat(accepted).isGreaterThanOrEqualTo(loginPerIp);
    }

    @Test
    @DisplayName("Signup is limited per client address: once the limit is used up, 429 with Retry-After")
    void signupIsRateLimitedPerIp() throws Exception {
        String clientIp = DistinctClientAddresses.next();
        String email = uniqueEmail("flood");
        String body = signupBody(email, "Test User", PASSWORD);
        postFrom(clientIp, "/api/v1/auth/signup", body).andExpect(status().isCreated());

        int accepted = 1 + attemptsBeforeTheLimit(signupPerIp, () -> postFrom(clientIp, "/api/v1/auth/signup", body));

        assertThat(accepted).isGreaterThanOrEqualTo(signupPerIp);
    }

    @Test
    @DisplayName("Refresh is limited per client address: once the limit is used up, 429 with Retry-After")
    void refreshIsRateLimitedPerIp() throws Exception {
        String clientIp = DistinctClientAddresses.next();
        String body = """
                {"refreshToken": "unknown-token"}
                """;
        int accepted = attemptsBeforeTheLimit(refreshPerIp, () -> postFrom(clientIp, "/api/v1/auth/refresh-token", body));

        assertThat(accepted).isGreaterThanOrEqualTo(refreshPerIp);
    }

    @Test
    @DisplayName("Logout is limited per client address: once the limit is used up, 429 with Retry-After")
    void logoutIsRateLimitedPerIp() throws Exception {
        String clientIp = DistinctClientAddresses.next();
        String body = """
                {"refreshToken": "unknown-token"}
                """;
        int accepted = attemptsBeforeTheLimit(logoutPerIp, () -> postFrom(clientIp, "/api/v1/auth/logout", body));

        assertThat(accepted).isGreaterThanOrEqualTo(logoutPerIp);
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
        refresh(user.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
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
                .andExpect(jsonPath("$.type").value(problemType("AUTHENTICATION_REQUIRED")));
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
        try (ExecutorService executor = newExecutor()) {
            for (int attempt = 0; attempt < 5; attempt++) {
                Callable<Integer> refreshOnce = () -> {
                    awaitLatch(start);
                    return refresh(user.refreshToken()).andReturn().getResponse().getStatus();
                };
                statuses.add(executor.submit(refreshOnce));
            }
            start.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> status : statuses) {
                results.add(within(status));
            }
            assertThat(results).containsOnlyOnce(200).containsOnly(200, 401);
        }
        assertThat(liveRefreshTokens(user)).isEqualTo(1);
    }

    @Test
    @DisplayName("Logout racing a refresh with one token: logout is 204, the refresh 200 or 401, and at most the refresh's new session lives")
    void logoutRacingARefreshRevokesTheTokenOnce() throws Exception {
        for (int round = 0; round < 5; round++) {
            TestUser user = newUser();
            CountDownLatch start = new CountDownLatch(1);
            Answer logout;
            Answer refreshed;
            try (ExecutorService executor = newExecutor()) {
                Future<Answer> logoutOnce = executor.submit(() -> {
                    awaitLatch(start);
                    return answerOf(postAs(null, "/api/v1/auth/logout", """
                            {"refreshToken": "%s"}
                            """.formatted(user.refreshToken())));
                });
                Future<Answer> refreshOnce = executor.submit(() -> {
                    awaitLatch(start);
                    return answerOf(refresh(user.refreshToken()));
                });
                start.countDown();
                logout = within(logoutOnce);
                refreshed = within(refreshOnce);
            }

            // Whichever revokes the token first wins; the other's conditional update changes nothing.
            assertThat(logout.status()).as(logout.body()).isEqualTo(204);
            assertThat(refreshed.status()).as(refreshed.body()).isIn(200, 401);
            assertRefusalsAre(List.of(refreshed), 401, "AUTH_INVALID_REFRESH_TOKEN");
            assertThat(liveRefreshTokens(user)).isEqualTo(refreshed.status() == 200 ? 1 : 0);
        }
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
        String expired = accessToken(user, issuer, now.minus(Duration.ofHours(1)), now.minus(Duration.ofMinutes(1)));
        String foreign = accessToken(user, "someone-else", now, now.plus(Duration.ofMinutes(5)));

        getAs(expired, "/api/v1/users/me")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(foreign, "/api/v1/users/me")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        getAs(accessToken(user, issuer, now, now.plus(Duration.ofMinutes(5))), "/api/v1/users/me")
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
                // tenancy:begin
                // .audience(List.of("tenant"))
                // .claim("tenant_id", testTenant().id().toString())
                // tenancy:end
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
}
