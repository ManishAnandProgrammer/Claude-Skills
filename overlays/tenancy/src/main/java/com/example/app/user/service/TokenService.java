package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Hashes;
// session-version:begin
import com.example.app.common.security.SessionVersions;
// session-version:end
import com.example.app.common.tenancy.TenantContext;
import com.example.app.config.JwtProperties;
import com.example.app.tenant.api.Tenants;
import com.example.app.user.domain.PlatformUser;
import com.example.app.user.domain.RefreshToken;
import com.example.app.user.domain.RevocationReason;
import com.example.app.user.domain.TenantMembership;
import com.example.app.user.domain.User;
import com.example.app.user.dto.response.TokenResponse;
import com.example.app.user.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues, rotates and revokes tokens. Access tokens are short-lived JWTs (not checked against the
 * database); refresh tokens are random, stored hashed, single use.
 *
 * <p>With tenancy, a tenant token is for one membership: its {@code tenant_id} claim, the membership's
 * role, audience {@code tenant}. The tenant must be ACTIVE whenever tokens are issued (login, refresh,
 * switch), so a suspension takes effect within one access-token lifetime. Platform staff get an access
 * token with audience {@code platform}, no tenant and no refresh token.</p>
 *
 * <p>Every issue for an account (login, refresh, switch, impersonation, a password change) starts with the
 * account's row, share-locked and ACTIVE ({@code UserService.activeForTokenIssue}), before it touches a
 * refresh token or a membership: a suspension or a password change updates that row first, so either the
 * issue commits before the change (which then revokes what it issued) or it reads the change and refuses. Same
 * order everywhere: no deadlock.</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TokenService {

    /** 256 random bits: unguessable, so a fast hash is enough to store it. */
    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final String TOKEN_TYPE = "Bearer";
    private static final String ROLES_CLAIM = "roles";

    private final SecureRandom secureRandom = new SecureRandom();
    private final RefreshTokenRepository repository;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;
    private final MembershipService memberships;
    private final Tenants tenants;
    private final ImpersonationProperties impersonation;
    private final UserService users;

    /**
     * Login's tokens, once the password matched, for the user's most recently used membership: 403
     * {@code AUTH_ACCOUNT_SUSPENDED} for a suspended account.
     */
    @Transactional
    TokenResponse issue(User user) {
        User account = users.activeForTokenIssue(user.getId());
        return issueTokens(account, memberships.forLogin(account));
    }

    /**
     * Exchanges a refresh token for new tokens and revokes it (rotation).
     *
     * <p>A revoked token presented again:</p>
     * <ul>
     *   <li>rotated within {@code app.jwt.rotation-grace}: a client duplicate. Refused, and the session that
     *       the first refresh started lives on;</li>
     *   <li>rotated earlier: the token leaked. Every session of the user is revoked; that revocation
     *       must commit although the request is refused, hence {@code noRollbackFor};</li>
     *   <li>signed out, or already revoked for reuse: refused.</li>
     * </ul>
     * <p>The token is read without a lock and revoked by a conditional update: of concurrent refreshes
     * (or a refresh and a logout) with one token, only one revokes it; the others find it revoked
     * when they read it again, and take the paths above.</p>
     */
    @Transactional(noRollbackFor = AppException.class)
    TokenResponse refresh(String rawToken) {
        Instant now = clock.instant();
        String tokenHash = Hashes.sha256Hex(rawToken);
        RefreshToken token = usableToken(tokenHash, now);
        // Locked before the token is spent; loaded now, since the revoking update clears the persistence context.
        User user = users.activeForTokenIssue(token.getUser().getId());
        UUID tenantId = token.getTenantId();
        // Before the token is spent: no membership there any more, or a tenant no longer active, is a
        // refusal that keeps it.
        TenantMembership membership = memberships.forTenant(user, tenantId);
        requireIssuedForThisMembership(token, membership);
        tenants.requireActive(tenantId);
        revokeForRotation(tokenHash, now);
        // Read again: the revoking update cleared the persistence context.
        return issueTokens(user, memberships.forTenant(user, tenantId));
    }

    /**
     * Rotates the refresh token into tokens for another of the user's tenants: 404
     * {@code MEMBERSHIP_NOT_FOUND} unless the user is a member there, 403 {@code TENANT_NOT_ACTIVE} unless
     * it is ACTIVE; either way the token stays usable. The token's own session must still stand, as for a
     * refresh: once its holder was removed from the token's tenant (added again or not), it switches
     * nowhere, 401 {@code AUTH_INVALID_REFRESH_TOKEN}.
     */
    @Transactional(noRollbackFor = AppException.class)
    TokenResponse switchTenant(String rawToken, UUID tenantId) {
        Instant now = clock.instant();
        String tokenHash = Hashes.sha256Hex(rawToken);
        RefreshToken token = usableToken(tokenHash, now);
        User user = users.activeForTokenIssue(token.getUser().getId());
        TenantMembership own = memberships.findForTenant(user, token.getTenantId())
                .orElseThrow(AppException.supply(ErrorCode.AUTH_INVALID_REFRESH_TOKEN));
        requireIssuedForThisMembership(token, own);
        memberships.forTenant(user, tenantId);
        // Before the token is spent: switching to a tenant that isn't active keeps the current session.
        tenants.requireActive(tenantId);
        revokeForRotation(tokenHash, now);
        return issueTokens(user, memberships.forTenant(user, tenantId));
    }

    /**
     * Staff acting as a member: a tenant token for the membership, with the staff member as actor
     * ({@code act.sub}, RFC 8693), short-lived and without a refresh token. It doesn't count as the
     * member's use of the tenant ({@code lastUsedAt} stays). A suspended member is 403
     * {@code AUTH_ACCOUNT_SUSPENDED}: staff act as nobody who may not sign in.
     */
    @Transactional(noRollbackFor = AppException.class)
    TokenResponse issueImpersonation(TenantMembership membership, UUID staffId) {
        users.activeForTokenIssue(membership.getUser().getId());
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(TenantContext.TENANT_AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(impersonation.tokenTtl()))
                .subject(membership.getUser().getId().toString())
                .claim(ROLES_CLAIM, List.of(membership.getRole().name()))
                .claim(TenantContext.TENANT_CLAIM, membership.getTenantId().toString())
                .claim(CurrentUser.ACTOR_CLAIM, Map.of(CurrentUser.ACTOR_SUBJECT, staffId.toString()))
                // session-version:begin
                // The member's: a change of their access ends the impersonation too.
                .claim(SessionVersions.CLAIM, membership.getSessionVersion())
                .claim(SessionVersionService.MEMBERSHIP_CLAIM, membership.getId().toString())
                // session-version:end
                .build();
        return new TokenResponse(encode(claims), TOKEN_TYPE, impersonation.tokenTtl().toSeconds(), null);
    }

    /** A platform staff member's access token: audience platform, no tenant, no refresh token. */
    TokenResponse issuePlatform(PlatformUser staff) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(TenantContext.PLATFORM_AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .subject(staff.getId().toString())
                .claim(ROLES_CLAIM, List.of(staff.getRole().name()))
                .build();
        return new TokenResponse(encode(claims), TOKEN_TYPE, properties.accessTokenTtl().toSeconds(), null);
    }

    /** Idempotent: an unknown or already revoked token is not an error (logout always succeeds). */
    @Transactional
    void revoke(String rawToken) {
        repository.revokeIfLive(Hashes.sha256Hex(rawToken), RevocationReason.LOGOUT, clock.instant());
    }

    /**
     * A password change, in one transaction: the new hash (its update takes the account's row first), every
     * refresh token of the account revoked in every tenant, and new tokens for the calling device, in the
     * tenant it is signed in to.
     *   <!-- session-version:begin -->
     * Every membership's session version is raised too, so every other access token is refused from its next
     * request.
     *   <!-- session-version:end -->
     * A suspended account changes nothing: 403 {@code AUTH_ACCOUNT_SUSPENDED}.
     */
    @Transactional
    TokenResponse replacePassword(UUID userId, UUID tenantId, String newPasswordHash) {
        users.changePassword(userId, newPasswordHash);
        int revoked = repository.revokeAllOfUser(userId, RevocationReason.PASSWORD_CHANGED, clock.instant());
        // session-version:begin
        memberships.bumpSessionVersionsOfUser(userId);
        // session-version:end
        log.info("Password changed, every session ended: userId={}, sessions={}", userId, revoked);
        // Read after the bump (the revoking update cleared the persistence context): its numbers are the new ones.
        User user = users.activeForTokenIssue(userId);
        return issueTokens(user, memberships.forTenant(user, tenantId));
    }

    /** A suspension's revocation, every tenant's, in the suspension's own transaction (hence MANDATORY), after its account update. */
    @Transactional(propagation = Propagation.MANDATORY)
    int revokeForSuspension(UUID userId) {
        return repository.revokeAllOfUser(userId, RevocationReason.SUSPENDED, clock.instant());
    }
    // session-version:begin

    /**
     * Signs the user out everywhere, every tenant included, in one transaction: every refresh token revoked,
     * and every membership's session version raised, so every access token already issued is refused on its
     * next request.
     */
    @Transactional
    void endAllSessions(UUID userId) {
        int revoked = repository.revokeAllOfUser(userId, RevocationReason.LOGOUT, clock.instant());
        memberships.bumpSessionVersionsOfUser(userId);
        log.info("Signed out everywhere: userId={}, sessions={}", userId, revoked);
    }
    // session-version:end

    /**
     * A token created before its membership belongs to a membership removed since: adding the member again
     * starts new sessions, it doesn't revive the old ones. Every path that issues from a refresh token
     * checks it (refresh, switch-tenant).
     */
    private static void requireIssuedForThisMembership(RefreshToken token, TenantMembership membership) {
        if (token.getCreatedAt().isBefore(membership.getCreatedAt())) {
            throw AppException.of(ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }
    }

    private RefreshToken findToken(String tokenHash) {
        return repository.findByTokenHash(tokenHash).orElseThrow(AppException.supply(ErrorCode.AUTH_INVALID_REFRESH_TOKEN));
    }

    /**
     * One batch of the purge ({@code RefreshTokenPurgeJob}), in a short transaction of its own: up to
     * {@code batchSize} tokens that expired before {@code expiredBefore}; the rows deleted.
     */
    @Transactional
    int deleteExpiredBefore(Instant expiredBefore, int batchSize) {
        return repository.deleteExpiredBefore(expiredBefore, batchSize);
    }

    /** The live token behind {@code tokenHash}, read without a lock; a revoked or expired one is refused. */
    private RefreshToken usableToken(String tokenHash, Instant now) {
        RefreshToken token = findToken(tokenHash);
        if (token.isRevoked()) {
            refuseRevoked(token, now);
        }
        if (token.isExpired(now)) {
            throw AppException.of(ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }
        return token;
    }

    /** Of concurrent rotations (or a rotation and a logout) with one token, only one revokes it. */
    private void revokeForRotation(String tokenHash, Instant now) {
        if (repository.revokeIfLive(tokenHash, RevocationReason.ROTATED, now) == 0) {
            refuseRevoked(findToken(tokenHash), now);
        }
    }

    private void refuseRevoked(RefreshToken token, Instant now) {
        UUID userId = token.getUser().getId();
        if (token.wasRotatedWithin(properties.rotationGrace(), now)) {
            log.info("Refresh token presented again within the rotation grace: refused, sessions kept: userId={}", userId);
        } else if (token.getRevocationReason() == RevocationReason.ROTATED) {
            int revoked = repository.revokeAllOfUserForReuse(userId, now);
            log.warn("Refresh token reused: every session revoked: userId={}, sessions={}", userId, revoked);
        }
        throw AppException.of(ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
    }

    /** 403 {@code TENANT_NOT_ACTIVE} unless the membership's tenant is ACTIVE: checked on every issue. */
    private TokenResponse issueTokens(User user, TenantMembership membership) {
        tenants.requireActive(membership.getTenantId());
        Instant now = clock.instant();
        memberships.markUsed(membership, now);
        String refreshToken = newRefreshToken();
        repository.save(RefreshToken.issue(user, membership.getTenantId(), Hashes.sha256Hex(refreshToken),
                now.plus(properties.refreshTokenTtl())));
        return new TokenResponse(accessToken(user, membership, now), TOKEN_TYPE, properties.accessTokenTtl().toSeconds(), refreshToken);
    }

    private String accessToken(User user, TenantMembership membership, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(TenantContext.TENANT_AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .subject(user.getId().toString())
                .claim(ROLES_CLAIM, List.of(membership.getRole().name()))
                .claim(TenantContext.TENANT_CLAIM, membership.getTenantId().toString())
                // session-version:begin
                // From the same row as the role: a token never pairs a newer number with an older role.
                .claim(SessionVersions.CLAIM, membership.getSessionVersion())
                .claim(SessionVersionService.MEMBERSHIP_CLAIM, membership.getId().toString())
                // session-version:end
                .build();
        return encode(claims);
    }

    private String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
