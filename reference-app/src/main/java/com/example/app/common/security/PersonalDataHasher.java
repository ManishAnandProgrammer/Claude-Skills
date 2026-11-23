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
 * HMAC under a server-side pepper: a plain hash wouldn't do, since every IPv4 address can be hashed in seconds
 * and emails fall to a dictionary.
 */
@Component
@RequiredArgsConstructor
public class PersonalDataHasher {

    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final PepperProperties properties;

    public String hash(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            mac.init(new SecretKeySpec(properties.personalDataPepper().getBytes(StandardCharsets.UTF_8), HMAC_SHA_256));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            throw new IllegalStateException("Every Java runtime provides HmacSHA256", impossible);
        }
    }

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
