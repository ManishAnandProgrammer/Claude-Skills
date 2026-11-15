package com.example.app.common.validation;

import java.util.Locale;

/**
 * The one normalisation of an email address, for every write and every lookup by email (house-review
 * DATA-05): a table stores this form (its {@code CHECK (email = lower(trim(email)))}), so a plain unique key
 * is unique as people read an address, and a lookup that normalises the same way finds it through that key.
 *
 * <p>No Unicode normalisation (NFKC): every stored address is ASCII ({@link AsciiOnly} on every request that
 * writes one), where NFKC changes nothing, and on a lookup it would only let a look-alike spelling (a
 * full-width {@code ａ}) find an account.</p>
 */
public final class Emails {

    private Emails() {
    }

    /**
     * Surrounding whitespace dropped and lower-cased in the root locale (under a Turkish default locale,
     * {@code I} would become a dotless {@code ı}).
     */
    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
