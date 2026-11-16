package com.example.app.user;

import com.example.app.support.IntegrationTest;
import com.example.app.support.StubBreachedPasswords;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The account lifecycle of the sign-in piece: a password change, a suspension and a reactivation, each
 * against every token path (login, refresh, each request), and a suspension racing a token issue.
 *   <!-- tenancy:begin -->
 * With tenancy, platform staff suspend accounts; the staff endpoint's own rules (the last ADMIN of a tenant,
 * switch-tenant and impersonation of a suspended member) are PlatformUserStatusIntegrationTest's.
 *   <!-- tenancy:end -->
 */
class AccountLifecycleIntegrationTest extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final String PASSWORD_PATH = "/api/v1/users/me/password";
    private static final String NEW_PASSWORD = "Battery-Staple-7";
    // tenancy:begin
    // private static final String ACCOUNTS = "/api/v1/platform/users";
    // tenancy:else
    private static final String ACCOUNTS = "/api/v1/users";
    // tenancy:end

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Value("${app.rate-limit.sign-in.login-per-account.requests}")
    private int loginPerAccount;

    private ResultActions refresh(String refreshToken) throws Exception {
        return postAs(null, "/api/v1/auth/refresh-token", """
                {"refreshToken": "%s"}
                """.formatted(refreshToken));
    }

    private ResultActions changePassword(String accessToken, String currentPassword, String newPassword) throws Exception {
        return postAs(accessToken, PASSWORD_PATH, """
                {"currentPassword": "%s", "newPassword": "%s"}
                """.formatted(currentPassword, newPassword));
    }

    /** The version as the status changer reads it before a change: through the lookup by email, never SQL. */
    private long versionOf(TestUser account) throws Exception {
        Number version = read(lookUp(statusChanger(), account.email()).andExpect(status().isOk()), "$.response[0].version");
        return version.longValue();
    }

    private ResultActions lookUp(String token, String email) throws Exception {
        return mvc.perform(get(ACCOUNTS).param("email", email).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    /** A token that changes account statuses: staff's with tenancy, an application ADMIN's without. */
    private String statusChanger() throws Exception {
        // tenancy:begin
        // return platformToken();
        // tenancy:else
        return newAdmin().accessToken();
        // tenancy:end
    }

    private ResultActions changeStatus(TestUser account, String status) throws Exception {
        return changeStatus(statusChanger(), account.id(), status, versionOf(account));
    }

    private ResultActions changeStatus(TestUser account, String status, long version) throws Exception {
        return changeStatus(statusChanger(), account.id(), status, version);
    }

    private ResultActions changeStatus(String token, UUID userId, String status, long version) throws Exception {
        String body = """
                {"status": "%s", "version": %d}
                """.formatted(status, version);
        return patchAs(token, ACCOUNTS + "/" + userId + "/status", body);
    }

    private int liveRefreshTokensOf(UUID userId) {
        return jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                Integer.class, userId);
    }

    @Test
    @DisplayName("""
            A password change answers new tokens for this device and ends every other session: both devices' \
            refresh tokens are 401; only the new password signs in""")
    void aPasswordChangeEndsEverySession() throws Exception {
        TestUser phone = newUser();
        TestUser laptop = signIn(phone);

        ResultActions changed = changePassword(phone.accessToken(), PASSWORD, NEW_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.response.refreshToken").isNotEmpty());

        getAs(read(changed, "$.response.accessToken"), ME).andExpect(status().isOk());
        for (String refreshToken : List.of(phone.refreshToken(), laptop.refreshToken())) {
            refresh(refreshToken)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        }
        refresh(read(changed, "$.response.refreshToken")).andExpect(status().isOk());
        // session-version:begin
        for (String accessToken : List.of(phone.accessToken(), laptop.accessToken())) {
            getAs(accessToken, ME)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("SESSION_VERSION_STALE"));
        }
        // session-version:else
        // // Without the session version an access token lives out its lifetime (at most 15 minutes).
        // getAs(laptop.accessToken(), ME).andExpect(status().isOk());
        // session-version:end
        login(phone.email(), PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));
        login(phone.email(), NEW_PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A wrong current password is 400 USER_PASSWORD_INCORRECT at #/currentPassword and changes nothing")
    void aWrongCurrentPasswordChangesNothing() throws Exception {
        TestUser user = newUser();

        changePassword(user.accessToken(), "Wrong-Pass-1", NEW_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("USER_PASSWORD_INCORRECT"))
                .andExpect(jsonPath("$.errors[*].pointer", contains("#/currentPassword")));

        refresh(user.refreshToken()).andExpect(status().isOk());
        login(user.email(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("The new password follows signup's policy (400 at #/newPassword) and breached-password check (400)")
    void theNewPasswordFollowsSignupsRules() throws Exception {
        TestUser user = newUser();

        changePassword(user.accessToken(), PASSWORD, "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].pointer").value("#/newPassword"));
        changePassword(user.accessToken(), PASSWORD, "Is-" + StubBreachedPasswords.BREACHED + "-9")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("USER_PASSWORD_BREACHED"));

        login(user.email(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    @DisplayName("""
            Wrong current passwords spend the account's login limit: past it, 429 RATE_LIMITED, and the account's \
            login is limited too""")
    void wrongCurrentPasswordsAreLimitedLikeLogins() throws Exception {
        TestUser user = newUser();

        // newUser's sign-in spent one request of the account's limit.
        for (int attempt = 1; attempt < loginPerAccount; attempt++) {
            changePassword(user.accessToken(), "Wrong-Pass-1", NEW_PASSWORD).andExpect(status().isBadRequest());
        }
        changePassword(user.accessToken(), "Wrong-Pass-1", NEW_PASSWORD)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.errorCode").value("RATE_LIMITED"));
        login(user.email(), PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("""
            A suspension ends every session and refuses every token path: login with the right password 403 \
            AUTH_ACCOUNT_SUSPENDED (a wrong one still 401), both refresh tokens 401, no live refresh token left""")
    void aSuspensionRefusesEveryTokenPath() throws Exception {
        TestUser phone = newUser();
        TestUser laptop = signIn(phone);

        changeStatus(phone, "SUSPENDED")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("SUSPENDED"));

        login(phone.email(), PASSWORD)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_SUSPENDED"));
        login(phone.email(), "Wrong-Pass-1")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_CREDENTIALS"));
        for (String refreshToken : List.of(phone.refreshToken(), laptop.refreshToken())) {
            refresh(refreshToken)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        }
        assertThat(liveRefreshTokensOf(phone.id())).isZero();
        // session-version:begin
        getAs(laptop.accessToken(), ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("SESSION_VERSION_STALE"));
        // session-version:else
        // // The access token lives out its lifetime, but issues nothing: a password change is refused too.
        // changePassword(laptop.accessToken(), PASSWORD, NEW_PASSWORD)
        //         .andExpect(status().isForbidden())
        //         .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_SUSPENDED"));
        // session-version:end
    }

    @Test
    @DisplayName("A refresh token a suspension didn't revoke (one issued in a race) is still refused: 403 AUTH_ACCOUNT_SUSPENDED")
    void aSuspendedAccountsLiveRefreshTokenIsRefused() throws Exception {
        TestUser user = newUser();
        changeStatus(user, "SUSPENDED").andExpect(status().isOk());
        assertThat(sql("update refresh_tokens set revoked_at = null, revocation_reason = null where user_id = ?", user.id()))
                .isPositive();

        refresh(user.refreshToken())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_SUSPENDED"));
        assertThat(liveRefreshTokensOf(user.id())).as("the refused token stays unspent").isEqualTo(1);
    }

    @Test
    @DisplayName("A reactivated account signs in again; the sessions its suspension ended stay ended")
    void aReactivationRevivesNoSession() throws Exception {
        TestUser user = newUser();
        changeStatus(user, "SUSPENDED").andExpect(status().isOk());

        changeStatus(user, "ACTIVE")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.status").value("ACTIVE"));

        refresh(user.refreshToken())
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH_INVALID_REFRESH_TOKEN"));
        // session-version:begin
        getAs(user.accessToken(), ME)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("SESSION_VERSION_STALE"));
        // session-version:end
        getAs(signIn(user).accessToken(), ME).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A stale version is 409 OPTIMISTIC_LOCK_CONFLICT; the current status again is 200 and moves nothing")
    void versionsAndNoOps() throws Exception {
        TestUser user = newUser();
        long before = versionOf(user);

        changeStatus(user, "ACTIVE", before)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.version").value(before));
        refresh(user.refreshToken()).andExpect(status().isOk());
        changeStatus(user, "SUSPENDED", before).andExpect(status().isOk());
        changeStatus(user, "ACTIVE", before)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"));
    }

    @Test
    @DisplayName("""
            A refresh racing a suspension waits for it and is refused (403 AUTH_ACCOUNT_SUSPENDED): no token of \
            the account outlives the suspension""")
    void aSuspensionRacingARefreshLeavesNoToken() throws Exception {
        TestUser user = newUser();

        List<ResultActions> answers = whileASuspensionIsInProgress(user, () -> refresh(user.refreshToken()));

        answers.getFirst().andExpect(status().isOk());
        answers.getLast()
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_SUSPENDED"));
        assertThat(liveRefreshTokensOf(user.id())).isZero();
    }

    @Test
    @DisplayName("""
            A login racing a suspension waits for it and is refused (403 AUTH_ACCOUNT_SUSPENDED): no token of \
            the account outlives the suspension""")
    void aSuspensionRacingALoginLeavesNoToken() throws Exception {
        TestUser user = newUser();

        List<ResultActions> answers = whileASuspensionIsInProgress(user, () -> login(user.email(), PASSWORD));

        answers.getFirst().andExpect(status().isOk());
        answers.getLast()
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("AUTH_ACCOUNT_SUSPENDED"));
        assertThat(liveRefreshTokensOf(user.id())).isZero();
    }

    /**
     * Starts the suspension of {@code account} and holds it after its account update, at its revocation (a
     * SHARE lock on refresh_tokens), then sends {@code tokenRequest} and waits until it waits on a lock too:
     * the account's row, which the issue share-locks first. Without that lock the request would read the
     * account as still ACTIVE and get past it. Then lets both go; returns the suspension's answer, then the
     * request's.
     */
    private List<ResultActions> whileASuspensionIsInProgress(TestUser account, Callable<ResultActions> tokenRequest) throws Exception {
        // The changer signs in before the gate shuts: its own sign-in writes a refresh token.
        String changer = statusChanger();
        long version = versionOf(account);
        Callable<ResultActions> suspension = () -> changeStatus(changer, account.id(), "SUSPENDED", version);
        CountDownLatch gateClosed = new CountDownLatch(1);
        CountDownLatch openGate = new CountDownLatch(1);
        try (ExecutorService executor = newExecutor()) {
            Future<?> gate = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.execute("lock table refresh_tokens in share mode");
                gateClosed.countDown();
                awaitQuietly(openGate);
            }));
            awaitLatch(gateClosed);
            Future<ResultActions> suspended;
            Future<ResultActions> requested;
            try {
                long deadline = lockWaitDeadline();
                suspended = executor.submit(suspension);
                awaitLockWaiters(1, suspended, deadline);
                requested = executor.submit(tokenRequest);
                awaitLockWaiters(2, requested, deadline);
                assertThat(requested.isDone()).as("the token request waits for the suspension").isFalse();
            } finally {
                openGate.countDown();
            }
            within(gate);
            return List.of(within(suspended), within(requested));
        }
    }
    @Test
    @DisplayName("""
            The status changer finds an account by its address, ignoring case and surrounding spaces: id, status and \
            version; an address nobody has finds an empty list""")
    void anAccountIsFoundByItsAddress() throws Exception {
        TestUser user = newUser();
        String changer = statusChanger();

        lookUp(changer, "  " + user.email().toUpperCase(Locale.ROOT) + " ")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.response[0].id").value(user.id().toString()))
                .andExpect(jsonPath("$.response[0].email").value(user.email()))
                .andExpect(jsonPath("$.response[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.response[0].version").isNumber());
        changeStatus(user, "SUSPENDED").andExpect(status().isOk());
        lookUp(changer, user.email()).andExpect(jsonPath("$.response[0].status").value("SUSPENDED"));
        lookUp(changer, "nobody-" + user.email())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response").isEmpty());
    }

    @Test
    @DisplayName("The lookup needs an address of at most the configured length: missing or one longer is 400 naming email")
    void theLookupsAddressIsBounded() throws Exception {
        String changer = statusChanger();
        String longest = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".io";

        lookUp(changer, longest).andExpect(status().isOk()).andExpect(jsonPath("$.response").isEmpty());
        lookUp(changer, "x" + longest)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("email"))
                .andExpect(jsonPath("$.errors[0].detail").value("must be at most 254 characters"));
        mvc.perform(get(ACCOUNTS).header(HttpHeaders.AUTHORIZATION, "Bearer " + changer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].parameter").value("email"));
    }
    // tenancy:begin
    // tenancy:else

    @Test
    @DisplayName("The lookup is an ADMIN's: a USER's is 403 ACCESS_DENIED, none without a token")
    void onlyAnAdminLooksUpAnAccount() throws Exception {
        TestUser user = newUser();

        lookUp(user.accessToken(), user.email())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        mvc.perform(get(ACCOUNTS).param("email", user.email()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTHENTICATION_REQUIRED"));
        lookUp(newAdmin().accessToken(), user.email()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("A USER's status change is 403 ACCESS_DENIED; an unknown account 404 USER_NOT_FOUND")
    void onlyAnAdminChangesAStatus() throws Exception {
        TestUser user = newUser();
        TestUser other = newUser();

        patchAs(user.accessToken(), "/api/v1/users/" + other.id() + "/status", """
                {"status": "SUSPENDED", "version": %d}
                """.formatted(versionOf(other)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        patchAs(newAdmin().accessToken(), "/api/v1/users/" + UUID.randomUUID() + "/status", """
                {"status": "SUSPENDED", "version": 0}
                """)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("USER_NOT_FOUND"));
        refresh(other.refreshToken()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("An ADMIN never suspends their own account: 403 USER_OWN_STATUS_CHANGE, whatever the state")
    void anAdminNeverSuspendsThemselves() throws Exception {
        TestUser admin = newAdmin();

        patchAs(admin.accessToken(), "/api/v1/users/" + admin.id() + "/status", """
                {"status": "SUSPENDED", "version": %d}
                """.formatted(versionOf(admin)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("USER_OWN_STATUS_CHANGE"));
        refresh(admin.refreshToken()).andExpect(status().isOk());
    }

    @Test
    @DisplayName("""
            Two ADMINs suspending each other at once: exactly one is suspended, the other's change is refused \
            (it is no ACTIVE ADMIN by then), so an ADMIN always remains""")
    void twoAdminsSuspendingEachOtherLeaveOne() throws Exception {
        TestUser first = newAdmin();
        TestUser second = newAdmin();
        String firstBody = """
                {"status": "SUSPENDED", "version": %d}
                """.formatted(versionOf(second));
        String secondBody = """
                {"status": "SUSPENDED", "version": %d}
                """.formatted(versionOf(first));
        CountDownLatch start = new CountDownLatch(1);
        List<Answer> answers;
        try (ExecutorService executor = newExecutor()) {
            Callable<Answer> firstSuspends = () -> {
                awaitLatch(start);
                return answerOf(patchAs(first.accessToken(), "/api/v1/users/" + second.id() + "/status", firstBody));
            };
            Callable<Answer> secondSuspends = () -> {
                awaitLatch(start);
                return answerOf(patchAs(second.accessToken(), "/api/v1/users/" + first.id() + "/status", secondBody));
            };
            Future<Answer> firstAnswer = executor.submit(firstSuspends);
            Future<Answer> secondAnswer = executor.submit(secondSuspends);
            start.countDown();
            answers = List.of(within(firstAnswer), within(secondAnswer));
        }

        assertThat(answers).filteredOn(answer -> answer.status() == 200).hasSize(1);
        // session-version:begin
        // A suspension that committed first raises the other's session version: its token is refused at once.
        assertRefusalsAre(answers, 401, "SESSION_VERSION_STALE");
        // session-version:end
        assertRefusalsAre(answers, 403, "ACCESS_DENIED");
        Integer suspended = jdbc.queryForObject("select count(*) from users where id in (?, ?) and status = 'SUSPENDED'",
                Integer.class, first.id(), second.id());
        assertThat(suspended).isEqualTo(1);
    }
    // tenancy:end
}
