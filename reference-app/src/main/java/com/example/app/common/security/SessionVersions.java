package com.example.app.common.security;

import org.springframework.security.oauth2.jwt.Jwt;

public interface SessionVersions {

    String CLAIM = "sv";

    /**
     * A token without a session version, or whose account no longer exists, is not current. Throws when the
     * number can be read neither from Redis nor from the database: never an answer of "yes".
     */
    boolean isCurrent(Jwt accessToken);
}
