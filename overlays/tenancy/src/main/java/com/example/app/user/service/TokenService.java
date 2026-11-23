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
 * Every issue starts with the account's row, share-locked and ACTIVE ({@code UserService.activeForTokenIssue}),
 * before it touches a refresh token or a membership: a suspension or a password change updates that row first, so
 * either the issue commits before the change (which then revokes what it issued) or it reads the change and refuses.
 * Same order everywhere (account, then tokens): no deadlock.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TokenService {

    /** 256 random bits: unguessable, so a fast hash is enough to store it. */
    private static final int REFRESH_TOKEN_BYTES = 32;
    private static final String TOKEN_TYPE = "Bearer";

    private final SecureRandom secureRandom = new SecureRandom();
    private final RefreshTokenRepository repository;
    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;
    private final MembershipService memberships;
    private final Tenants tenants;
    private final ImpersonationProperties impersonation;
    private final UserService users;
    // session-version:begin
    private final PlatformStaffService staff;
    // session-version:end

    @Transactional
    TokenResponse issue(User user) {
        User account = users.activeForTokenIssue(user.getId());
        return issueTokens(account, memberships.forLogin(account));
    }

    /** A reused token revokes every session of the user, which must commit although the request is refused. */
    @Transactional(noRollbackFor = AppException.class)
    TokenResponse refresh(String rawToken) {
        Instant now = clock.instant();
        String tokenHash = Hashes.sha256Hex(rawToken);
        RefreshToken token = usableToken(tokenHash, now);
        // Locked before the token is spent; loaded now, since the revoking update clears the persistence context.
        User user = users.activeForTokenIssue(token.getUser().getId());
        UUID tenantId = token.getTenantId();
        // Before the token is spent: a refusal here keeps it.
        TenantMembership membership = memberships.forTenant(user, tenantId);
        requireIssuedForThisMembership(token, membership);
        tenants.requireActive(tenantId);
        revokeForRotation(tokenHash, now);
        // Read again: the revoking update cleared the persistence context.
        return issueTokens(user, memberships.forTenant(user, tenantId));
    }

    @Transactional(noRollbackFor = AppException.class)
    TokenResponse switchTenant(String rawToken, UUID tenantId) {
        Instant now = clock.instant();
        String tokenHash = Hashes.sha256Hex(rawToken);
        RefreshToken token = usableToken(tokenHash, now);
        User user = users.activeForTokenIssue(token.getUser().getId());
        TenantMembership own = memberships.findForTenant(user, token.getTenantId())
                .orElseThrow(AppException.supply(ErrorCode.AUTH_INVALID_REFRESH_TOKEN));
        requireIssuedForThisMembership(token, own);
        // Before the token is spent: a refusal here keeps it.
        memberships.requireMembership(user, tenantId);
        tenants.requireActive(tenantId);
        revokeForRotation(tokenHash, now);
        return issueTokens(user, memberships.forTenant(user, tenantId));
    }

    @Transactional(noRollbackFor = AppException.class)
    TokenResponse issueImpersonation(TenantMembership membership, UUID staffId) {
        // session-version:begin
        staff.requireActiveForImpersonation(staffId);
        // session-version:end
        // Staff act as nobody who may not sign in.
        users.activeForTokenIssue(membership.getUser().getId());
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(TenantContext.TENANT_AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(impersonation.tokenTtl()))
                .subject(membership.getUser().getId().toString())
                // roles:begin
                // roles:else
                // .claim(RolePermissions.ROLES_CLAIM, List.of(membership.getRole().name()))
                // roles:end
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

    TokenResponse issuePlatform(PlatformUser staff) {
        Instant now = clock.instant();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(TenantContext.PLATFORM_AUDIENCE))
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .subject(staff.getId().toString())
                // roles:begin
                // roles:else
                // .claim(RolePermissions.ROLES_CLAIM, List.of(staff.getRole().name()))
                // roles:end
                .build();
        return new TokenResponse(encode(claims), TOKEN_TYPE, properties.accessTokenTtl().toSeconds(), null);
    }

    @Transactional
    void revoke(String rawToken) {
        repository.revokeIfLive(Hashes.sha256Hex(rawToken), RevocationReason.LOGOUT, clock.instant());
    }

    @Transactional
    TokenResponse replacePassword(UUID userId, UUID tenantId, String newPasswordHash) {
        users.changePassword(userId, newPasswordHash);
        int revoked = repository.revokeAllOfUser(userId, RevocationReason.PASSWORD_CHANGED, clock.instant());
        // session-version:begin
        memberships.bumpSessionVersionsOfUser(userId);
        // The membership is read again below, after the bump (the revoking update cleared the persistence context):
        // its token carries the new number.
        // session-version:end
        log.info("Password changed, every session ended: userId={}, sessions={}", userId, revoked);
        User user = users.activeForTokenIssue(userId);
        return issueTokens(user, memberships.forTenant(user, tenantId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    int revokeForSuspension(UUID userId) {
        return repository.revokeAllOfUser(userId, RevocationReason.SUSPENDED, clock.instant());
    }
    // session-version:begin

    @Transactional
    void endAllSessions(UUID userId) {
        int revoked = repository.revokeAllOfUser(userId, RevocationReason.LOGOUT, clock.instant());
        memberships.bumpSessionVersionsOfUser(userId);
        log.info("Signed out everywhere: userId={}, sessions={}", userId, revoked);
    }
    // session-version:end

    /** A token created before its membership belongs to one removed since: adding the member back doesn't revive it. */
    private static void requireIssuedForThisMembership(RefreshToken token, TenantMembership membership) {
        if (token.getCreatedAt().isBefore(membership.getCreatedAt())) {
            throw AppException.of(ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }
    }

    private RefreshToken findToken(String tokenHash) {
        return repository.findByTokenHash(tokenHash).orElseThrow(AppException.supply(ErrorCode.AUTH_INVALID_REFRESH_TOKEN));
    }

    @Transactional
    int deleteExpiredBefore(Instant expiredBefore, int batchSize) {
        return repository.deleteExpiredBefore(expiredBefore, batchSize);
    }

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
                // roles:begin
                // roles:else
                // .claim(RolePermissions.ROLES_CLAIM, List.of(membership.getRole().name()))
                // roles:end
                .claim(TenantContext.TENANT_CLAIM, membership.getTenantId().toString())
                // session-version:begin
                // roles:begin
                // roles:else
                // // From the same row as the role: a token never pairs a newer number with an older role.
                // roles:end
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
