package com.example.app.common.security;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Keys personal data (client addresses, emails) for logs and rate-limit buckets without keeping it:
 * HMAC-SHA256 under a server-side pepper ({@code app.security.personal-data-pepper}). A plain hash
 * wouldn't do: every IPv4 address can be hashed in seconds, and emails fall to a dictionary.
 * Random tokens use {@link Hashes#sha256Hex} instead.
 */
@Component
@RequiredArgsConstructor
public class PersonalDataHasher {

    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final PepperProperties properties;

    /** Lower-case hex HMAC of the UTF-8 bytes: 64 characters. */
    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(properties.personalDataPepper().getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("Every Java runtime provides HmacSHA256", impossible);
        }
    }

    /**
     * {@code app.security.*}. The pepper has no default: startup fails naming
     * {@code PERSONAL_DATA_PEPPER} when it is missing or shorter than 32 bytes.
     */
    @ConfigurationProperties(prefix = "app.security")
    public record PepperProperties(String personalDataPepper) {

        private static final int MIN_BYTES = 32;

        public PepperProperties {
            if (Objects.isNull(personalDataPepper) || personalDataPepper.getBytes(StandardCharsets.UTF_8).length < MIN_BYTES) {
                throw new IllegalArgumentException("app.security.personal-data-pepper (PERSONAL_DATA_PEPPER) must be at least 32 bytes");
            }
        }
    }
}
