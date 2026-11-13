package com.example.app.user.service;

import com.example.app.common.exception.AppException;
import com.example.app.common.exception.ErrorCode;
import com.example.app.common.security.Hashes;
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
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Issues, rotates and revokes tokens. Access tokens are short-lived JWTs (not checked against the
 * database); refresh tokens are random, stored hashed, single use.
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

    @Transactional
    TokenResponse issue(User user) {
        return issueTokens(user);
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
     * <p>The row lock serialises concurrent refreshes with one token: the second waits, then sees
     * it rotated.</p>
     */
    @Transactional(noRollbackFor = AppException.class)
    TokenResponse refresh(String rawToken) {
        Instant now = clock.instant();
        RefreshToken token = repository.findByTokenHash(Hashes.sha256Hex(rawToken))
                .orElseThrow(AppException.supply(ErrorCode.AUTH_INVALID_REFRESH_TOKEN));
        if (token.isRevoked()) {
            refuseRevoked(token, now);
        }
        if (token.isExpired(now)) {
            throw AppException.of(ErrorCode.AUTH_INVALID_REFRESH_TOKEN);
        }
        token.revoke(RevocationReason.ROTATED, now);
        return issueTokens(token.getUser());
    }

    /** Idempotent: an unknown or already revoked token is not an error (logout always succeeds). */
    @Transactional
    void revoke(String rawToken) {
        repository.findByTokenHash(Hashes.sha256Hex(rawToken))
                .ifPresent(token -> token.revoke(RevocationReason.LOGOUT, clock.instant()));
    }

    /** One batch of the purge; each batch is its own short transaction. */
    @Transactional
    int deleteExpiredBatch(Duration keepAfterExpiry, int batchSize) {
        return repository.deleteExpiredBefore(clock.instant().minus(keepAfterExpiry), batchSize);
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
                .claim(ROLES_CLAIM, List.of(user.getRole().name()))
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
