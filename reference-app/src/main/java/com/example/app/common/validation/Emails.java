package com.example.app.common.validation;

import java.util.Locale;

/**
 * No Unicode normalisation (NFKC): every stored address is ASCII ({@link AsciiOnly}), where NFKC changes
 * nothing, and on a lookup it would only let a look-alike spelling (a full-width {@code ａ}) find an account.
 */
public final class Emails {

    private Emails() {
    }

    /** The root locale: under a Turkish default locale, {@code I} would become a dotless {@code ı}. */
    public static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }
}
