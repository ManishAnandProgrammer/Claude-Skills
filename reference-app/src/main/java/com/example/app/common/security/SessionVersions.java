package com.example.app.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The session version check (the session-version piece), for {@link SessionVersionFilter}: an access
 * token is accepted only while its {@code sv} claim is the current session version of what it was issued
 * for. Implemented by the module that owns the number (the user module), so common knows no feature.
 * Every access change raises that number in its own transaction, so every token issued before it, on
 * every device, is refused on its next request (401 {@code SESSION_VERSION_STALE}) and the client
 * refreshes: the refresh reads the access as it is now.
 */
public interface SessionVersions {

    /** The access token's claim with the session version it was issued under. */
    String CLAIM = "sv";

    /**
     * Whether {@code accessToken} (signature, expiry and issuer already verified) carries the current
     * session version. A token without one, or whose account no longer exists, is not current. Throws
     * when the number can be read neither from Redis nor from the database: never an answer of "yes".
     */
    boolean isCurrent(Jwt accessToken);
}
