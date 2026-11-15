package com.example.app.common.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class EmailsTest {

    /** The longest address the house shape accepts: 64 before the @, labels of at most 63, 254 in all. */
    static final String LONGEST = "A".repeat(64) + "@" + "B".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(58) + ".io";

    @Test
    @DisplayName("Mixed case is lower-cased: one address, one spelling")
    void mixedCase() {
        assertThat(Emails.normalize("Asha.Kaur@Example.COM")).isEqualTo("asha.kaur@example.com");
    }

    @Test
    @DisplayName("Surrounding spaces and tabs are dropped; the address itself is untouched")
    void surroundingWhitespace() {
        assertThat(Emails.normalize(" \tasha@example.com\n ")).isEqualTo("asha@example.com");
    }

    @Test
    @DisplayName("A 254-character address keeps every character, only lower-cased")
    void longestAddress() {
        assertThat(LONGEST).hasSize(254);
        assertThat(Emails.normalize(LONGEST)).hasSize(254).isEqualTo(LONGEST.toLowerCase(Locale.ROOT));
    }

    @Test
    @DisplayName("Under a Turkish default locale an I is still an i, not a dotless one")
    void independentOfTheDefaultLocale() {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThat(Emails.normalize("INFO@EXAMPLE.COM")).isEqualTo("info@example.com");
        } finally {
            Locale.setDefault(before);
        }
    }
}
