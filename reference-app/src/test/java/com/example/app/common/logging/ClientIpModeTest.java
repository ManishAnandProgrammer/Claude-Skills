package com.example.app.common.logging;

import com.example.app.common.security.PersonalDataHasher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClientIpModeTest {

    private final PersonalDataHasher hasher = new PersonalDataHasher(
            new PersonalDataHasher.PepperProperties("a-pepper-that-is-at-least-thirty-two-bytes"));

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "203.0.113.57, 203.0.113.0",
            "2001:db8:abcd:12:34:56:78:9a, 2001:db8:abcd:0:0:0:0:0",
            "not-an-address, invalid"})
    @DisplayName("TRUNCATED zeroes the last IPv4 octet and cuts IPv6 to its /48")
    void truncatedKeepsOnlyTheNetwork(String address, String expected) {
        assertThat(ClientIpMode.TRUNCATED.render(address, hasher)).isEqualTo(expected);
    }

    @Test
    @DisplayName("HASHED is a stable 16-character keyed hash, NONE is empty, RAW is the address")
    void otherModes() {
        assertThat(ClientIpMode.HASHED.render("203.0.113.57", hasher))
                .hasSize(16)
                .isEqualTo(ClientIpMode.HASHED.render("203.0.113.57", hasher))
                .doesNotContain("203");
        assertThat(ClientIpMode.NONE.render("203.0.113.57", hasher)).isEmpty();
        assertThat(ClientIpMode.RAW.render("203.0.113.57", hasher)).isEqualTo("203.0.113.57");
    }
}
