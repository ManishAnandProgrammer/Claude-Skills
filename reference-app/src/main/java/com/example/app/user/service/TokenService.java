package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Hashes;
// session-version:begin
import com.example.app.common.security.SessionVersions;
// session-version:end
import com.example.app.config.JwtProperties;
import com.example.app.user.domain.RefreshToken;
import com.example.app.user.domain.RevocationReason;
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
import java.util.UUID;

/**
 * Issues, rotates and revokes tokens. Access tokens are short-lived JWTs (not checked against the
 * database); refresh tokens are random, stored hashed, single use.
 *
 * <p>Every issue starts with the account's row, share-locked and ACTIVE ({@code UserService.activeForTokenIssue}),
 * before it touches a refresh token: a suspension or a password change updates that row first, so either the
 * issue commits before the change (which then revokes what it issued) or it reads the change and refuses. Same
 * order everywhere (account, then tokens): no deadlock.</p>
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
    private final UserService users;

    /** Login's tokens, once the password matched: 403 {@code AUTH_ACCOUNT_SUSPENDED} for a suspended account. */
    @Transactional
    TokenResponse issue(User user) {
        return issueTokens(users.activeForTokenIssue(user.getId()));
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
        RefreshToken token = findToken(tokenHash);
        if (token.isRevoked()) {
            refuseRevoked(token, now);
        }
        if (token.isExpired(now)) {
            throw AppException.of(ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }
        // Locked before the token is spent; loaded now, since the revoking update clears the persistence context.
        User user = users.activeForTokenIssue(token.getUser().getId());
        if (repository.revokeIfLive(tokenHash, RevocationReason.ROTATED, now) == 0) {
            refuseRevoked(findToken(tokenHash), now);
        }
        return issueTokens(user);
    }

    /** Idempotent: an unknown or already revoked token is not an error (logout always succeeds). */
    @Transactional
    void revoke(String rawToken) {
        repository.revokeIfLive(Hashes.sha256Hex(rawToken), RevocationReason.LOGOUT, clock.instant());
    }

    /**
     * A password change, in one transaction: the new hash (its update takes the account's row first), every
     * refresh token of the account revoked, and new tokens for the device that changed it.
     *   <!-- session-version:begin -->
     * The session version is raised too, so every other access token is refused from its next request.
     *   <!-- session-version:end -->
     * A suspended account changes nothing: 403 {@code AUTH_ACCOUNT_SUSPENDED}.
     */
    @Transactional
    TokenResponse replacePassword(UUID userId, String newPasswordHash) {
        users.changePassword(userId, newPasswordHash);
        int revoked = repository.revokeAllOfUser(userId, RevocationReason.PASSWORD_CHANGED, clock.instant());
        // session-version:begin
        users.bumpSessionVersion(userId);
        // session-version:end
        log.info("Password changed, every session ended: userId={}, sessions={}", userId, revoked);
        // Read after the bump (the revoking update cleared the persistence context): its number is the new one.
        return issueTokens(users.activeForTokenIssue(userId));
    }

    /** A suspension's revocation, in the suspension's own transaction (hence MANDATORY), after its account update. */
    @Transactional(propagation = Propagation.MANDATORY)
    int revokeForSuspension(UUID userId) {
        return repository.revokeAllOfUser(userId, RevocationReason.SUSPENDED, clock.instant());
    }
    // session-version:begin

    /**
     * Signs the account out everywhere, in one transaction: every refresh token revoked, and the session
     * version raised, so every access token already issued is refused on its next request.
     */
    @Transactional
    void endAllSessions(UUID userId) {
        int revoked = repository.revokeAllOfUser(userId, RevocationReason.LOGOUT, clock.instant());
        users.bumpSessionVersion(userId);
        log.info("Signed out everywhere: userId={}, sessions={}", userId, revoked);
    }
    // session-version:end

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

    private TokenResponse issueTokens(User user) {
        Instant now = clock.instant();
        String refreshToken = newRefreshToken();
        repository.save(RefreshToken.issue(user, Hashes.sha256Hex(refreshToken), now.plus(properties.refreshTokenTtl())));
        return new TokenResponse(accessToken(user, now), TOKEN_TYPE, properties.accessTokenTtl().toSeconds(), refreshToken);
    }

    private String accessToken(User user, Instant now) {
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .subject(user.getId().toString())
                .claim(RolePermissions.ROLES_CLAIM, List.of(user.getRole().name()))
                // session-version:begin
                // From the same row as the role: a token never pairs a newer number with an older role.
                .claim(SessionVersions.CLAIM, user.getSessionVersion())
                // session-version:end
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[REFRESH_TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
