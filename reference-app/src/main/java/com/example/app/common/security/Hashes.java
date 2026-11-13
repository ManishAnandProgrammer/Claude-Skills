package com.example.app.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 for high-entropy secrets (random tokens), so they can be looked up without being stored.
 * Never for personal data such as addresses or emails, which can be guessed back: use
 * {@link PersonalDataHasher}.
 */
public final class Hashes {

    private Hashes() {
    }

    /** Lower-case hex SHA-256 of the UTF-8 bytes: 64 characters. */
    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Every Java runtime provides SHA-256", impossible);
        }
    }
}
