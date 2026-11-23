package com.example.app.user.service;

import com.example.app.common.security.SessionVersions;
import com.example.app.common.tenancy.TenantScope;
import com.example.app.support.IntegrationTest;
// roles:begin
// roles:else
// import com.example.app.user.domain.Role;
// import com.example.app.user.dto.request.TenantMemberUpdateRequest;
// roles:end
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SessionVersionServiceIntegrationTest extends IntegrationTest {

    @Autowired
    private SessionVersionService sessionVersions;

    @Autowired
    private MembershipService memberships;

    @Autowired
    private TokenService tokens;

    @Autowired
    private SessionVersionProperties properties;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID membershipOf(TestUser user, TestTenant tenant) {
        return jdbc.queryForObject("select id from tenant_memberships where user_id = ? and tenant_id = ?",
                UUID.class, user.id(), tenant.id());
    }

    private int storedVersion(UUID membershipId) {
        return jdbc.queryForObject("select session_version from tenant_memberships where id = ?", Integer.class, membershipId);
    }

    private String cached(UUID membershipId) {
        return redis.opsForValue().get(SessionVersionService.key(membershipId));
    }

    private void signOutEverywhere(TestUser user) {
        tokens.endAllSessions(user.id());
    }

    private Jwt decoded(String accessToken) {
        return jwtDecoder.decode(accessToken);
    }

    @Test
    @DisplayName("A miss reads the membership's number from the database and fills Redis, with the configured TTL")
    void aMissFillsRedis() throws Exception {
        TestUser user = newUser();
        UUID membership = membershipOf(user, testTenant());
        redis.delete(SessionVersionService.key(membership));

        assertThat(sessionVersions.isCurrent(decoded(user.accessToken()))).isTrue();

        assertThat(cached(membership)).isEqualTo("0");
        assertThat(redis.getExpire(SessionVersionService.key(membership))).isBetween(1L, properties.cacheTtl().toSeconds());
    }

    @Test
    @DisplayName("""
            The fill race: a request read 0 from the database, a bump commits and publishes 1, then the request's \
            late fill (SET NX) loses: Redis still says 1. Written over instead, the revoked token would pass""")
    void aLateFillLosesToTheBump() throws Exception {
        TestUser user = newUser();
        UUID membership = membershipOf(user, testTenant());
        Jwt revoked = decoded(user.accessToken());
        redis.delete(SessionVersionService.key(membership));
        int readBeforeTheBump = storedVersion(membership);

        signOutEverywhere(user);
        sessionVersions.fillIfAbsent(membership, readBeforeTheBump);

        assertThat(cached(membership)).isEqualTo(Integer.toString(readBeforeTheBump + 1));
        assertThat(sessionVersions.isCurrent(revoked)).isFalse();
        // What the guard prevents: a plain SET of the late read puts the old number back, and the revoked token passes.
        redis.opsForValue().set(SessionVersionService.key(membership), Integer.toString(readBeforeTheBump));
        assertThat(sessionVersions.isCurrent(revoked)).isTrue();
    }

    // roles:begin
    @Test
    @DisplayName("A rolled-back removal raises nothing and leaves Redis alone; the same removal committed raises Redis's copy")
    void aRolledBackRemovalRaisesNothing() throws Exception {
        TestTenant tenant = newTenant();
        TestUser member = newUserIn(tenant, "USER");
        UUID membership = membershipOf(member, tenant);
        assertThat(sessionVersions.isCurrent(decoded(member.accessToken()))).isTrue();
        assertThat(cached(membership)).isEqualTo("0");

        TenantScope.runAs(tenant.id(), () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            memberships.remove(member.id());
            status.setRollbackOnly();
        }));

        assertThat(storedVersion(membership)).isZero();
        assertThat(cached(membership)).isEqualTo("0");
        assertThat(sessionVersions.isCurrent(decoded(member.accessToken()))).isTrue();
        TenantScope.runAs(tenant.id(), () -> new TransactionTemplate(transactionManager)
                .executeWithoutResult(status -> memberships.remove(member.id())));
        assertThat(cached(membership)).isEqualTo("1");
        assertThat(sessionVersions.isCurrent(decoded(member.accessToken()))).isFalse();
    }

    // roles:else
    // @Test
    // @DisplayName("A rolled-back role change raises nothing and leaves Redis alone; the same change committed raises both")
    // void aRolledBackRoleChangeRaisesNothing() throws Exception {
    //     TestTenant tenant = newTenant();
    //     TestUser admin = newUserIn(tenant, "ADMIN");
    //     TestUser member = newUserIn(tenant, "USER");
    //     UUID membership = membershipOf(member, tenant);
    //     assertThat(sessionVersions.isCurrent(decoded(member.accessToken()))).isTrue();
    //     assertThat(cached(membership)).isEqualTo("0");
    //     TenantMemberUpdateRequest promotion = new TenantMemberUpdateRequest(Role.ADMIN, 0L);
    //
    //     TenantScope.runAs(tenant.id(), () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
    //         memberships.changeRole(admin.id(), member.id(), promotion);
    //         status.setRollbackOnly();
    //     }));
    //
    //     assertThat(storedVersion(membership)).isZero();
    //     assertThat(cached(membership)).isEqualTo("0");
    //     assertThat(sessionVersions.isCurrent(decoded(member.accessToken()))).isTrue();
    //     TenantScope.runAs(tenant.id(), () -> memberships.changeRole(admin.id(), member.id(), promotion));
    //     assertThat(storedVersion(membership)).isOne();
    //     assertThat(cached(membership)).isEqualTo("1");
    // }
    //
    // roles:end
    @Test
    @DisplayName("A token newer than Redis's copy (a publish that was lost) is decided by the database: current, so accepted")
    void aTokenNewerThanTheCacheAsksTheDatabase() throws Exception {
        TestUser user = newUser();
        UUID membership = membershipOf(user, testTenant());
        signOutEverywhere(user);
        Jwt current = decoded(signIn(user).accessToken());
        redis.opsForValue().set(SessionVersionService.key(membership), "0");

        assertThat(sessionVersions.isCurrent(current)).isTrue();
    }

    @Test
    @DisplayName("A token without the sv or membership_id claim, or for a membership that doesn't exist, is not current")
    void aTokenWithoutAVersionIsNotCurrent() throws Exception {
        TestUser user = newUser();
        String membership = membershipOf(user, testTenant()).toString();
        Jwt withoutVersion = Jwt.withTokenValue("token").header("alg", "HS256").subject(user.id().toString())
                .claim(SessionVersionService.MEMBERSHIP_CLAIM, membership).build();
        Jwt withoutMembership = Jwt.withTokenValue("token").header("alg", "HS256").subject(user.id().toString())
                .claim(SessionVersions.CLAIM, 0).build();
        Jwt unknownMembership = Jwt.withTokenValue("token").header("alg", "HS256").subject(user.id().toString())
                .claim(SessionVersions.CLAIM, 0).claim(SessionVersionService.MEMBERSHIP_CLAIM, UUID.randomUUID().toString()).build();

        assertThat(sessionVersions.isCurrent(withoutVersion)).isFalse();
        assertThat(sessionVersions.isCurrent(withoutMembership)).isFalse();
        assertThat(sessionVersions.isCurrent(unknownMembership)).isFalse();
        assertThat(sessionVersions.isCurrent(decoded(user.accessToken()))).isTrue();
    }

    @Test
    @DisplayName("""
            Redis down: every check reads the database instead, so a revoked token is still refused and a current \
            one accepted, through the open circuit breaker too; each such check is counted""")
    void redisDownFallsBackToTheDatabase() throws Exception {
        TestUser user = newUser();
        Jwt revoked = decoded(user.accessToken());
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(2).minimumNumberOfCalls(2).build());
        breakers.circuitBreaker(SessionVersionService.CIRCUIT_BREAKER);
        LettuceConnectionFactory unreachable = new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", closedPort()));
        unreachable.afterPropertiesSet();
        unreachable.start();
        try {
            SessionVersionService withoutRedis = new SessionVersionService(memberships, new StringRedisTemplate(unreachable),
                    properties, breakers, meters);

            assertThat(withoutRedis.isCurrent(revoked)).isTrue();
            signOutEverywhere(user);
            assertThat(withoutRedis.isCurrent(revoked)).isFalse();
            assertThat(breakers.circuitBreaker(SessionVersionService.CIRCUIT_BREAKER).getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(withoutRedis.isCurrent(decoded(signIn(user).accessToken()))).isTrue();
            assertThat(meters.counter(SessionVersionService.FALLBACK_METRIC).count()).isEqualTo(3);
        } finally {
            unreachable.destroy();
        }
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
