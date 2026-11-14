package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Hashes;
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
import org.hibernate.Hibernate;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
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

    /** Login's tokens; with tenancy, for the user's most recently used membership. */
    @Transactional
    TokenResponse issue(User user) {
        return issueTokens(user, memberships.forLogin(user));
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
        // Loaded now: the revoking update clears the persistence context, which detaches the lazy user.
        User user = Hibernate.unproxy(token.getUser(), User.class);
        UUID tenantId = token.getTenantId();
        // Before the token is spent: no membership there any more is a refusal that keeps it.
        memberships.forTenant(user, tenantId);
        revokeForRotation(tokenHash, now);
        // Read again: the revoking update cleared the persistence context.
        return issueTokens(user, memberships.forTenant(user, tenantId));
    }

    /**
     * Rotates the refresh token into tokens for another of the user's tenants: 404
     * {@code MEMBERSHIP_NOT_FOUND} (the token stays usable) unless the user is a member there.
     */
    @Transactional(noRollbackFor = AppException.class)
    TokenResponse switchTenant(String rawToken, UUID tenantId) {
        Instant now = clock.instant();
        String tokenHash = Hashes.sha256Hex(rawToken);
        User user = Hibernate.unproxy(usableToken(tokenHash, now).getUser(), User.class);
        memberships.forTenant(user, tenantId);
        revokeForRotation(tokenHash, now);
        return issueTokens(user, memberships.forTenant(user, tenantId));
    }

    /**
     * Staff acting as a member: a tenant token for the membership, with the staff member as actor
     * ({@code act.sub}, RFC 8693), short-lived and without a refresh token. It doesn't count as the
     * member's use of the tenant ({@code lastUsedAt} stays).
     */
    TokenResponse issueImpersonation(TenantMembership membership, UUID staffId) {
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

    private RefreshToken findToken(String tokenHash) {
        return repository.findByTokenHash(tokenHash).orElseThrow(AppException.supply(ErrorCode.AUTH_INVALID_REFRESH_TOKEN));
    }

    /** One batch of the purge; each batch is its own short transaction. */
    @Transactional
    int deleteExpiredBatch(Duration keepAfterExpiry, int batchSize) {
        return repository.deleteExpiredBefore(clock.instant().minus(keepAfterExpiry), batchSize);
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
        membership.markUsed(now);
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
